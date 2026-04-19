#!/usr/bin/env python3
"""
Validate NetworkPolicy manifests in k8s/base/networkpolicies/.

Two checks, both offline (no kubectl / cluster access required):

  1. YAML syntax — every file parses under PyYAML safe_load_all.
  2. Label-reference consistency — every `app=<name>` value referenced in
     a NetworkPolicy `matchLabels` corresponds to a real Deployment /
     CronJob label in k8s/base/. Unknown labels fail the check.

Exit codes:
  0 — all checks pass
  1 — YAML parse error or unknown pod-label reference

Usage:
  python3 k8s/scripts/validate-networkpolicies.py

Run from the repo root.

Live `kubectl` validation is a separate step — see
`k8s/base/networkpolicies/README.md` §Applying + verifying.
"""

from __future__ import annotations

import glob
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
NETPOL_DIR = REPO_ROOT / "k8s" / "base" / "networkpolicies"
WORKLOAD_GLOBS = [
    str(REPO_ROOT / "k8s" / "base" / "*-deployment.yaml"),
    str(REPO_ROOT / "k8s" / "base" / "pg-backup-cronjob.yaml"),
]

# External pod-label references (not Deployments we ship) that should be
# recognised as valid. These are either K8s-built-in (kube-dns) or
# placeholders explicitly documented in 50-observability.yaml.
KNOWN_EXTERNAL_LABELS: dict[str, set[str]] = {
    "k8s-app": {"kube-dns"},
    "app": {"nonexistent-placeholder"},  # 50-observability placeholder
}


def _collect_workload_labels() -> dict[str, set[str]]:
    """Return {label_key: {label_value, ...}} for all Deployments + CronJobs."""
    labels: dict[str, set[str]] = {}
    for pattern in WORKLOAD_GLOBS:
        for path in glob.glob(pattern):
            with open(path) as fh:
                for doc in yaml.safe_load_all(fh):
                    if not doc:
                        continue
                    kind = doc.get("kind")
                    if kind not in ("Deployment", "CronJob", "Service"):
                        continue
                    # Labels from metadata.labels
                    meta_labels = (doc.get("metadata") or {}).get("labels") or {}
                    for k, v in meta_labels.items():
                        labels.setdefault(k, set()).add(str(v))
                    # Labels from spec.template.metadata.labels (Deployment)
                    tpl = (
                        (doc.get("spec") or {})
                        .get("template", {})
                        .get("metadata", {})
                        .get("labels")
                        or {}
                    )
                    for k, v in tpl.items():
                        labels.setdefault(k, set()).add(str(v))
                    # CronJob jobTemplate labels
                    job_tpl = (
                        (doc.get("spec") or {})
                        .get("jobTemplate", {})
                        .get("spec", {})
                        .get("template", {})
                        .get("metadata", {})
                        .get("labels")
                        or {}
                    )
                    for k, v in job_tpl.items():
                        labels.setdefault(k, set()).add(str(v))
    # Merge in known external labels (kube-dns, etc.)
    for k, vs in KNOWN_EXTERNAL_LABELS.items():
        labels.setdefault(k, set()).update(vs)
    return labels


def _walk_pod_selectors(node, collector: list[tuple[str, str]]) -> None:
    """Recursively collect (key, value) pairs from every podSelector.matchLabels."""
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "podSelector" and isinstance(v, dict):
                ml = v.get("matchLabels") or {}
                for lk, lv in ml.items():
                    collector.append((lk, str(lv)))
            _walk_pod_selectors(v, collector)
    elif isinstance(node, list):
        for item in node:
            _walk_pod_selectors(item, collector)


def main() -> int:
    print(f"[validate] NetworkPolicies in {NETPOL_DIR.relative_to(REPO_ROOT)}")
    files = sorted(NETPOL_DIR.glob("*.yaml"))
    if not files:
        print("[validate] ERROR: no .yaml files found", file=sys.stderr)
        return 1

    workload_labels = _collect_workload_labels()
    print(
        f"[validate] Found {sum(len(v) for v in workload_labels.values())} workload labels "
        f"across {len(workload_labels)} keys"
    )

    yaml_ok = True
    label_errors: list[str] = []

    for path in files:
        rel = path.relative_to(REPO_ROOT)
        try:
            with open(path) as fh:
                docs = list(yaml.safe_load_all(fh))
        except yaml.YAMLError as exc:  # pragma: no cover - defensive
            print(f"[validate] YAML ERROR in {rel}: {exc}", file=sys.stderr)
            yaml_ok = False
            continue

        pod_selector_refs: list[tuple[str, str]] = []
        for doc in docs:
            if not doc:
                continue
            if doc.get("kind") != "NetworkPolicy":
                continue
            _walk_pod_selectors(doc.get("spec") or {}, pod_selector_refs)

        print(f"[validate] {rel}: parsed OK, {len(pod_selector_refs)} podSelector matchLabels refs")

        for key, value in pod_selector_refs:
            known = workload_labels.get(key, set())
            if value not in known:
                label_errors.append(
                    f"{rel}: podSelector matchLabels references "
                    f"{key}={value!r} which is not a known workload label. "
                    f"Known values for {key!r}: {sorted(known) or '(none)'}"
                )

    if not yaml_ok:
        print("[validate] FAIL: YAML parse errors", file=sys.stderr)
        return 1
    if label_errors:
        print("[validate] FAIL: unknown pod-label references:", file=sys.stderr)
        for err in label_errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print(f"[validate] PASS: {len(files)} files, all pod-label refs resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
