#!/usr/bin/env python3
"""keyvault-chain.py: the Key Vault secret chain of a RENDERED kustomize build (plan 29-12, D-23).

RED STAGE (TDD): this first version validates rule (c) ONLY (every secretObjects objectName is in
its SPC's parameters.objects). The selftest in k8s/scripts/check-keyvault-chain.sh must report the
other planted breaks as NOT caught against it; the GREEN commit implements the full chain.
"""

import sys

try:
    import yaml
except ImportError:
    sys.stderr.write("VOID: PyYAML is not importable; the render cannot be read.\n")
    sys.exit(2)


def main(argv):
    if len(argv) < 3 or argv[1] not in ("check", "objects"):
        sys.stderr.write("usage: keyvault-chain.py check|objects <render.yaml> [--verbose]\n")
        return 2
    docs = [d for d in yaml.safe_load_all(open(argv[2])) if isinstance(d, dict)]
    errors, objects = [], set()
    for d in docs:
        if d.get("kind") != "SecretProviderClass":
            continue
        spec = d.get("spec") or {}
        raw = (spec.get("parameters") or {}).get("objects") or ""
        names = {(yaml.safe_load(i) or {}).get("objectName") for i in (yaml.safe_load(raw) or {}).get("array") or []}
        objects |= names
        for so in spec.get("secretObjects") or []:
            for item in so.get("data") or []:
                if item.get("objectName") not in names:
                    errors.append(f"SecretProviderClass {d['metadata']['name']}: secretObjects "
                                  f"{so.get('secretName')}/{item.get('key')} syncs from objectName "
                                  f"{item.get('objectName')!r}, which is not in its parameters.objects")
    if errors:
        for e in errors:
            sys.stderr.write(f"FAIL: {e}\n")
        return 1
    if argv[1] == "objects":
        for o in sorted(objects):
            print(o)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
