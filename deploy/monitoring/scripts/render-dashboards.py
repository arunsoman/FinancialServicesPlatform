#!/usr/bin/env python3
"""Render the dashboards for ONE environment's ops console (Grafana at https://<env host>/ops/).

    render-dashboards.py <src-dir> <dst-dir> <staging|production>

Each environment's Grafana shows only that environment: the `env` variable becomes a hidden
constant, every fsp_* / container query without an env matcher gets one, and the cross-environment
comparison panel is dropped. (Soft boundary: the datasources are shared — see docs/plugin-registration.md.)
"""
import json
import pathlib
import re
import sys

UNLABELLED = {"fsp_dir_bytes"}   # host-level metrics with no env label (disk per FSP directory)
SEL = re.compile(r'\b((?:fsp|container|machine)_[a-z_]+)(\{([^}]*)\})?')


def scope(expr: str, env: str) -> str:
    def add(m):
        name, inner = m.group(1), m.group(3)
        if name in UNLABELLED:
            return m.group(0)
        if inner is not None and re.search(r'\benv\s*(=|=~|!=)', inner):
            return re.sub(r'\benv\s*(=~|=|!=)\s*"[^"]*"', f'env="{env}"', m.group(0))
        if inner is None:
            return f'{name}{{env="{env}"}}'
        return f'{name}{{env="{env}",{inner}}}' if inner.strip() else f'{name}{{env="{env}"}}'
    return SEL.sub(add, expr)


def render(d: dict, env: str) -> dict:
    for v in d.get("templating", {}).get("list", []):
        if v.get("name") == "env":
            v.clear()
            v.update({"name": "env", "type": "constant", "hide": 2, "query": env,
                      "current": {"text": env, "value": env}})
    keep = []
    for p in d.get("panels", []):
        if "Staging vs production" in (p.get("title") or ""):
            continue
        for t in p.get("targets", []):
            if "expr" in t and "$env" not in t["expr"]:
                t["expr"] = scope(t["expr"], env)
        keep.append(p)
    d["panels"] = keep
    d["title"] = f'{d.get("title", "")} — {env}'
    return d


def main() -> None:
    src, dst, env = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
    assert env in ("staging", "production"), env
    dst.mkdir(parents=True, exist_ok=True)
    for old in dst.glob("*.json"):
        old.unlink()
    for f in sorted(src.glob("*.json")):
        (dst / f.name).write_text(json.dumps(render(json.loads(f.read_text()), env), indent=2, ensure_ascii=False) + "\n")
    print(f"dashboards for {env} → {dst}")


if __name__ == "__main__":
    main()
