#!/usr/bin/env python3
"""Plugins ship as code — runs ON THE SERVER at the end of every successful deploy (deploy.sh).

    register-plugins.py <bundle-dir> <release-name>

Registers in core exactly the plugin versions this release ships (manifest = committed manifest +
this environment's live client key, see plugin_manifests.py) and suspends registered plugins the
release no longer ships. AGENTS.md §2.1 exception (docs/plugin-registration.md): the four eyes are
the pull-request review and, for production, the deploy approval — the people are taken from
provenance.json (written by GitHub Actions) and stored with the registration. No FSP machine actor
is maker or checker; nothing here is a person.

Refuses (registers nothing, says why) when:
  * FSP_PLUGIN_AUTOREGISTER != 1, or the release credential is not provisioned
  * provenance.json is missing (bundle not built by the pipeline)
  * FSP_PLUGIN_AUTOREG_SECOND_PERSON=1 (production) and nobody other than the PR author reviewed
    the PR or approved the deploy
A plugin whose key cannot be read is left alone, and then nothing is suspended for being missing.
Exit 1 only when core refused the request — the deploy itself is already live.
"""
import json
import pathlib
import sys
import urllib.error
import urllib.request

from plugin_manifests import base_url, bundle_config, expected_manifest, read_env, sh


def say(msg: str) -> None:
    print(msg, flush=True)


def second_people(prov: dict) -> list[str]:
    author = (prov.get("author") or "").lower()
    people = {p for p in (prov.get("reviewers") or []) + (prov.get("deployApprovers") or []) if p}
    return sorted(p for p in people if p.lower() != author)


def core_url(cfg: dict) -> str | None:
    ip = sh(["docker", "inspect", "-f",
             "{{(index .NetworkSettings.Networks \"" + cfg["FSP_NETWORK"] + "\").IPAddress}}", f"{cfg['FSP_PREFIX']}-core"])
    ip = (ip or "").strip()
    return f"http://{ip}:8081" if ip else None


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: register-plugins.py <bundle-dir> <release-name>", file=sys.stderr)
        return 2
    bundle = pathlib.Path(sys.argv[1]).resolve()
    release = sys.argv[2]
    cfg = bundle_config(bundle)
    env = cfg["FSP_ENV"]
    say(f"plugin registration ({env}):")
    if cfg.get("FSP_PLUGIN_AUTOREGISTER") != "1":
        say("  off (FSP_PLUGIN_AUTOREGISTER != 1) — register in Grafana › Plugins")
        return 0
    secrets = read_env(pathlib.Path(cfg["FSP_CONF_DIR"]) / ".env")
    release_cred, internal_cred = secrets.get("FSP_RELEASE_CREDENTIAL"), secrets.get("FSP_INTERNAL_CREDENTIAL")
    if not release_cred or not internal_cred:
        say(f"  off — FSP_RELEASE_CREDENTIAL is not provisioned in {cfg['FSP_CONF_DIR']}/.env")
        return 0
    pfile = bundle / "provenance.json"
    if not pfile.is_file():
        say("  skipped — no provenance.json (bundle not built by the pipeline); register in Grafana › Plugins")
        return 0
    prov = json.loads(pfile.read_text())
    if cfg.get("FSP_PLUGIN_AUTOREG_SECOND_PERSON") == "1" and not second_people(prov):
        say(f"  skipped — needs a second person: nobody but the author ({prov.get('author') or 'unknown'}) "
            "reviewed the PR or approved the deploy. Register in Grafana › Plugins (maker + checker).")
        return 0

    plugins, unreadable = [], []
    for code in cfg.get("FSP_PLUGINS", "").split():
        _, expected = expected_manifest(bundle, cfg, code)
        if expected is None:
            unreadable.append(code)
            continue
        plugins.append({"code": code, "version": expected.get("version"), "baseUrl": base_url(cfg, code),
                        "manifest": expected})
    for code in unreadable:
        say(f"  {code}: key not readable (plugin down?) — left as it is")

    url = core_url(cfg)
    if not url:
        say("  FAILED — core container has no address on " + cfg["FSP_NETWORK"])
        return 1
    body = json.dumps({"release": release, "provenance": prov, "plugins": plugins,
                       "suspendMissing": not unreadable}).encode()
    req = urllib.request.Request(f"{url}/internal/release/plugins", data=body, method="POST", headers={
        "Content-Type": "application/json",
        "X-FSP-Internal-Credential": internal_cred,
        "X-FSP-Release-Credential": release_cred,
        "X-Correlation-Id": f"release-{release}",
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            res = json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        say(f"  FAILED — core answered {e.code}: {e.read().decode(errors='replace')[:400]}")
        return 1
    except (urllib.error.URLError, TimeoutError) as e:
        say(f"  FAILED — core unreachable: {e}")
        return 1
    who = ", ".join(filter(None, [f"PR #{prov['pr']}" if prov.get("pr") else "",
                                  f"author {prov['author']}" if prov.get("author") else "",
                                  ("reviewed/approved by " + ", ".join(second_people(prov))) if second_people(prov) else ""]))
    for o in res.get("plugins", []):
        say(f"  {o['code']} {o['version']}: {o['action']}" + (f" — {o['detail']}" if o.get("detail") and o["action"] != "registered" else "")
            + (f" · installation: {o['installation']}" if o.get("installation") else ""))
    say(f"  recorded as release {release}" + (f" ({who})" if who else "") + "; the gateway routes changes within 60 s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
