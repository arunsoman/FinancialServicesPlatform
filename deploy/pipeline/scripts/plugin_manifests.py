"""Shared by observe.py (drift) and register-plugins.py (registration): the EXACT manifest an
environment registers for a shipped plugin = the committed manifest + this environment's live
public client key (client.jwks), read from the running plugin. One definition, so what Grafana
calls "in sync" is byte-for-byte what the deploy registers."""
import json
import pathlib
import re
import subprocess


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


def bundle_config(bundle: pathlib.Path) -> dict:
    return {**read_env(bundle / "params.env"), **read_env(bundle / "plugins.env")}


def base_url(cfg: dict, code: str) -> str:
    """The plugin's address on the environment's own Docker network (alias plugin-<code>)."""
    return f"http://plugin-{code}:{cfg.get(f'FSP_PLUGIN_{code.upper()}_PORT', '')}"


def shipped_manifest(bundle: pathlib.Path, code: str) -> dict:
    return json.loads((bundle / "plugins" / f"{code}.manifest.json").read_text())


def expected_manifest(bundle: pathlib.Path, cfg: dict, code: str) -> tuple[dict, dict | None]:
    """(shipped, expected) — expected is None when the plugin's key cannot be read (plugin down)."""
    shipped = shipped_manifest(bundle, code)
    port = cfg.get(f"FSP_PLUGIN_{code.upper()}_PORT", "")
    live = sh(["docker", "exec", f"{cfg['FSP_PREFIX']}-plugin-{code}", "wget", "-qO-", "-T", "5",
               f"http://127.0.0.1:{port}/.well-known/jwks.json"])
    if not live:
        return shipped, None
    expected = dict(shipped)
    expected["client"] = dict(shipped.get("client", {}), jwks=json.loads(live))
    return shipped, expected


def manifest_text(expected: dict) -> str:
    """The published file (what a person pastes when registering by hand)."""
    return json.dumps(expected, indent=2, ensure_ascii=False) + "\n"
