#!/usr/bin/env python3
"""Release + plugin-drift observer. Runs ON THE SERVER after every deploy (deploy.sh) and every
5 minutes from cron (deploy/monitoring/bin/fsp-ops.sh), read-only against the running stack.

    observe.py <bundle-dir>          (normally $FSP_HOME/current)

Writes Prometheus textfile metrics (shown in Grafana → "FSP releases & plugins"):
    fsp_release_info / fsp_release_available        what runs, what can be rolled back to
    fsp_plugin_drift / fsp_plugin_unrouted_operations
    fsp_plugin_manifest_available                    versions an admin can register (upgrade to)
and, per environment, the EXACT manifest to register for each shipped plugin version:
    $FSP_MANIFEST_ROOT/<env>/<code>-<version>.json   = committed manifest + this environment's
                                                       live public key (client.jwks)
Registration itself stays a human four-eyes act in the admin portal (AGENTS.md §2.1 —
machine actors are never maker or checker). This script only says what is waiting.
"""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import time

DRIFT_HELP = {
    "in_sync": "registered manifest == shipped manifest (with this environment's key)",
    "not_registered": "plugin process runs but no version is registered — no routes",
    "behind": "an older version is registered — new operations are not routed",
    "ahead": "a newer version is registered (rollback?) — some routed operations may 404",
    "key_mismatch": "same version, but the registered key is not this environment's key",
    "content_mismatch": "same version number, different content",
    "pending_approval": "the shipped version is waiting for the checker",
    "plugin_down": "plugin process not reachable — cannot read its key",
}


def read_env(path: pathlib.Path) -> dict:
    env = {}
    if not path.is_file():
        return env
    for line in path.read_text().splitlines():
        m = re.match(r"^([A-Z0-9_]+)=(.*)$", line.strip())
        if m:
            env[m.group(1)] = m.group(2).strip().strip('"')
    return env


def sh(args: list[str], timeout: int = 20) -> str | None:
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return r.stdout if r.returncode == 0 else None
    except (subprocess.TimeoutExpired, OSError):
        return None


def vtuple(v: str) -> tuple:
    return tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.\-+]", v or "0"))


def canon(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"))


def esc(v) -> str:
    return str(v).replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ")


def labels(**kv) -> str:
    return "{" + ",".join(f'{k}="{esc(v)}"' for k, v in kv.items()) + "}"


def write_atomic(path: pathlib.Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text)
    os.replace(tmp, path)


def release_metrics(cfg: dict, env: str) -> list[str]:
    home = pathlib.Path(cfg["FSP_HOME"])
    current = (home / "current").resolve() if (home / "current").exists() else None
    history = {}
    hist = home / "deploy-history.log"
    if hist.is_file():
        for line in hist.read_text().splitlines():
            parts = line.split()
            if len(parts) >= 3:
                history[parts[2]] = parts[0]          # last deploy time per release name
    out = ["# HELP fsp_release_info Release currently running in an environment.",
           "# TYPE fsp_release_info gauge",
           "# HELP fsp_release_available Releases kept on the server (roll back with rollback.yml).",
           "# TYPE fsp_release_available gauge"]
    for d in sorted((home / "releases").glob("*/"), key=lambda p: p.stat().st_mtime, reverse=True):
        rel = read_env(d / "release.env")
        lab = labels(env=env, release=d.name, commit=rel.get("FSP_RELEASE_COMMIT", "")[:12],
                     deployed_at=history.get(d.name, ""), current=str(current == d.resolve()).lower())
        out.append(f"fsp_release_available{lab} 1")
        if current == d.resolve():
            out.append("fsp_release_info" + labels(env=env, release=d.name,
                       commit=rel.get("FSP_RELEASE_COMMIT", "")[:12], deployed_at=history.get(d.name, "")) + " 1")
    return out


def drift_metrics(bundle: pathlib.Path, cfg: dict, env: str) -> tuple[list[str], list[str]]:
    prefix = cfg["FSP_PREFIX"]
    plugins = cfg.get("FSP_PLUGINS", "").split()
    root = pathlib.Path(cfg.get("FSP_MANIFEST_ROOT", "/tmp/fsp-manifests")) / env
    host = root.parent.parent.name                      # /var/www/<host>/manifests
    base_url = f"https://{host}/manifests/{env}"

    raw = sh(["docker", "exec", f"{prefix}-postgres", "psql", "-U", "fsp", "-d", "fsp", "-Atc",
              "select coalesce(json_agg(json_build_object('code',code,'version',version,'status',status,"
              "'manifest',manifest)),'[]') from plugin_types where status in ('REGISTERED','PENDING_APPROVAL')"])
    rows = json.loads(raw) if raw else None

    metrics = ["# HELP fsp_plugin_drift 0 = registered manifest equals the shipped one; 1 = action needed (see state).",
               "# TYPE fsp_plugin_drift gauge",
               "# HELP fsp_plugin_unrouted_operations Shipped operations the gateway does not route yet.",
               "# TYPE fsp_plugin_unrouted_operations gauge",
               "# HELP fsp_plugin_manifest_available Manifest versions an admin can register (upgrade/downgrade to).",
               "# TYPE fsp_plugin_manifest_available gauge"]
    summary = []
    for code in plugins:
        shipped = json.loads((bundle / "plugins" / f"{code}.manifest.json").read_text())
        version = shipped.get("version", "")
        port = cfg.get(f"FSP_PLUGIN_{code.upper()}_PORT", "")
        live = sh(["docker", "exec", f"{prefix}-plugin-{code}", "wget", "-qO-", "-T", "5",
                   f"http://127.0.0.1:{port}/.well-known/jwks.json"])
        url, sha, state, unrouted = "", "", "plugin_down", 0
        if live:
            expected = dict(shipped)
            expected["client"] = dict(shipped.get("client", {}), jwks=json.loads(live))
            text = json.dumps(expected, indent=2, ensure_ascii=False) + "\n"
            sha = hashlib.sha256(text.encode()).hexdigest()
            name = f"{code}-{version}.json"
            write_atomic(root / name, text)
            write_atomic(root / f"{name}.sha256", f"{sha}  {name}\n")
            url = f"{base_url}/{name}"
            if rows is None:
                state = "plugin_down"
            else:
                reg = next((r for r in rows if r["code"] == code and r["status"] == "REGISTERED"), None)
                pend = next((r for r in rows if r["code"] == code and r["status"] == "PENDING_APPROVAL"
                             and canon(r["manifest"]) == canon(expected)), None)
                reg_ops = {o.get("id") for o in (reg or {}).get("manifest", {}).get("operations", [])}
                unrouted = len([o for o in shipped.get("operations", []) if o.get("id") not in reg_ops])
                if reg and canon(reg["manifest"]) == canon(expected):
                    state, unrouted = "in_sync", 0
                elif pend:
                    state = "pending_approval"
                elif not reg:
                    state = "not_registered"
                elif vtuple(reg["version"]) < vtuple(version):
                    state = "behind"
                elif vtuple(reg["version"]) > vtuple(version):
                    state = "ahead"
                elif canon(reg["manifest"].get("client", {}).get("jwks")) != canon(expected["client"]["jwks"]):
                    state = "key_mismatch"
                else:
                    state = "content_mismatch"
        reg_v = next((r["version"] for r in (rows or []) if r["code"] == code and r["status"] == "REGISTERED"), "")
        metrics.append("fsp_plugin_drift" + labels(env=env, code=code, shipped_version=version,
                       registered_version=reg_v or "none", state=state, manifest_url=url,
                       manifest_sha256=sha[:16]) + f" {0 if state == 'in_sync' else 1}")
        metrics.append("fsp_plugin_unrouted_operations" + labels(env=env, code=code) + f" {unrouted}")
        summary.append(f"  {code}: shipped {version}, registered {reg_v or 'none'} → {state}"
                       + (f" ({unrouted} operation(s) not routed)" if unrouted else "")
                       + (f"\n      register: {url}  sha256 {sha}" if state not in ("in_sync", "plugin_down") else "")
                       + f"\n      {DRIFT_HELP[state]}")
    for f in sorted(root.glob("*.json")):
        m = re.match(r"^([a-z0-9]+)-(.+)\.json$", f.name)
        if m:
            metrics.append("fsp_plugin_manifest_available" + labels(env=env, code=m.group(1),
                           version=m.group(2), manifest_url=f"{base_url}/{f.name}") + " 1")
    return metrics, summary


def main() -> None:
    if len(sys.argv) != 2:
        print("usage: observe.py <bundle-dir>", file=sys.stderr)
        sys.exit(2)
    bundle = pathlib.Path(sys.argv[1]).resolve()
    cfg = {**read_env(bundle / "params.env"), **read_env(bundle / "plugins.env")}
    env = cfg["FSP_ENV"]
    textfile = pathlib.Path(cfg.get("FSP_MONITOR_TEXTFILE", "/tmp"))
    rel = release_metrics(cfg, env)
    drift, summary = drift_metrics(bundle, cfg, env)
    now = int(time.time())
    write_atomic(textfile / f"fsp_release_{env}.prom", "\n".join(rel) + "\n")
    write_atomic(textfile / f"fsp_plugins_{env}.prom", "\n".join(drift
                 + [f"fsp_observe_timestamp_seconds{labels(env=env)} {now}"]) + "\n")
    print(f"plugin drift ({env}):")
    print("\n".join(summary) or "  (no plugins)")


if __name__ == "__main__":
    main()
