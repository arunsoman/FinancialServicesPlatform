#!/usr/bin/env python3
"""CI gate for plugin manifests — the plugin equivalent of expand/contract migrations.

    check-plugin-manifests.py <base-git-ref>     (run from the repo root)

For every backend/plugins/<code>/plugin-manifest.json:
  * folder name == manifest code; deploy.json present (gen-plugins.py validates its content)
  * content changed  → version must go UP (semver)
  * minor/patch bump → may only ADD operations / approvals / grants; nothing removed or changed.
    Removing or changing one (method, path, permission, grant kind) needs a MAJOR bump.
  * a changed client.jwks (key rotation) is allowed on any bump

Why: after a rollback the gateway still routes the newer REGISTERED manifest. If newer versions
only ever add, the older process merely lacks the newest operations (observe.py reports them);
nothing that used to work changes meaning.
"""
import json
import pathlib
import subprocess
import sys

errors: list[str] = []


def git_show(ref: str, path: str):
    r = subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True, text=True)
    return json.loads(r.stdout) if r.returncode == 0 else None


def semver(v: str) -> tuple[int, int, int]:
    parts = (v or "").split(".")
    if len(parts) != 3 or not all(p.isdigit() for p in parts):
        raise ValueError(v)
    return int(parts[0]), int(parts[1]), int(parts[2])


def keyed(items, key):
    return {key(i): i for i in items or []}


def removed_or_changed(old: dict, new: dict) -> list[str]:
    out = []
    groups = [
        ("operation", "operations", lambda o: o.get("id"), lambda o: (o.get("method"), o.get("path"), o.get("permission"))),
        ("approval", "approvals", lambda a: a.get("action") or json.dumps(a, sort_keys=True), lambda a: json.dumps(a, sort_keys=True)),
    ]
    for label, field, k, sig in groups:
        o, n = keyed(old.get(field), k), keyed(new.get(field), k)
        for key, item in o.items():
            if key not in n:
                out.append(f"{label} '{key}' removed")
            elif sig(item) != sig(n[key]):
                out.append(f"{label} '{key}' changed")
    og = {(g.get("permission"), g.get("kind")) for g in (old.get("client") or {}).get("grants", [])}
    ng = {(g.get("permission"), g.get("kind")) for g in (new.get("client") or {}).get("grants", [])}
    out += [f"grant {p} ({k}) removed" for p, k in sorted(og - ng)]
    return out


def main() -> None:
    base = sys.argv[1] if len(sys.argv) > 1 else ""
    for path in sorted(pathlib.Path(".").glob("backend/plugins/*/plugin-manifest.json")):
        code = path.parent.name
        new = json.loads(path.read_text())
        rel = str(path)
        if new.get("code") != code:
            errors.append(f"{rel}: code '{new.get('code')}' must equal folder '{code}'")
        if not (path.parent / "deploy.json").is_file():
            errors.append(f"{rel}: missing deploy.json next to it")
        try:
            nv = semver(new.get("version"))
        except ValueError:
            errors.append(f"{rel}: version '{new.get('version')}' is not MAJOR.MINOR.PATCH")
            continue
        old = git_show(base, rel) if base else None
        if old is None or old == new:
            print(f"ok  {code} {new['version']}" + (" (new plugin)" if base and old is None else ""))
            continue
        try:
            ov = semver(old.get("version"))
        except ValueError:
            ov = (0, 0, 0)
        if nv <= ov:
            errors.append(f"{rel}: content changed but version {new['version']} is not above {old.get('version')}")
            continue
        breaking = removed_or_changed(old, new)
        if breaking and nv[0] == ov[0]:
            errors.append(f"{rel}: {old['version']} → {new['version']} is a minor/patch bump but: "
                          + "; ".join(breaking) + " — bump MAJOR, or keep the old entries")
            continue
        print(f"ok  {code} {old['version']} → {new['version']}" + (" (major, breaking)" if breaking else ""))
    if errors:
        print("\n".join(f"::error::{e}" for e in errors))
        sys.exit(1)


if __name__ == "__main__":
    main()
