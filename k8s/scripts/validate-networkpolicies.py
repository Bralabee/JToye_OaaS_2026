#!/usr/bin/env python3
"""
Validate NetworkPolicy manifests in k8s/base/networkpolicies/, k8s/components/,
and the rendered k8s/goldens/*.yaml (#979).

Two checks, both offline (no kubectl / cluster access required):

  1. YAML syntax — every file parses under PyYAML safe_load_all.
  2. Label-reference consistency — every `podSelector.matchLabels` key/value
     referenced in a NetworkPolicy corresponds to a real workload label in
     k8s/base/, k8s/components/, or the declared pod-label contract. Unknown
     labels fail the check.

Note: INV-7 in `k8s/scripts/check-render-invariants.sh` is the CI authority on
exact per-policy ingress/egress tuples across every rendered overlay.

Exit codes:
  0 — all checks pass
  1 — YAML parse error or unknown pod-label reference

Usage:
  python3 k8s/scripts/validate-networkpolicies.py [--selftest]

Run from the repo root.
"""

from __future__ import annotations

import glob
import sys
import tempfile
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
NETPOL_DIR = REPO_ROOT / "k8s" / "base" / "networkpolicies"
COMPONENTS_DIR = REPO_ROOT / "k8s" / "components"
GOLDENS_DIR = REPO_ROOT / "k8s" / "goldens"
WORKLOAD_GLOBS = [
    str(REPO_ROOT / "k8s" / "base" / "*-deployment.yaml"),
    str(REPO_ROOT / "k8s" / "base" / "pg-backup-cronjob.yaml"),
    str(REPO_ROOT / "k8s" / "components" / "**" / "*.yaml"),
]

# External pod-label references (not Deployments we ship) that should be
# recognised as valid. These are either K8s-built-in (kube-dns) or
# placeholders explicitly documented in 50-observability.yaml.
#
# Phase 29 (plan 29-02): the POD-LABEL CONTRACT the rewritten allow-lists bind to
# (k8s/base/networkpolicies/README.md "The pod-label contract"). These workloads
# are shipped by plans 29-10, 29-12, 29-22, 29-23 and 29-24, not as
# k8s/base/*-deployment.yaml files, or their label is set by an operator
# (RabbitMQ cluster operator, cert-manager), so they are declared here. Changing
# one is a coordinated change across those plans, the policies and INV-7.
KNOWN_EXTERNAL_LABELS: dict[str, set[str]] = {
    "k8s-app": {"kube-dns"},
    "app": {
        "nonexistent-placeholder",  # 50-observability placeholder
        "keycloak",
        "redis",
        "mailpit",
        "prometheus",
        "alertmanager",
        "grafana",
        "postgres-exporter",
        "redis-exporter",
        "secret-sync",
    },
    # Set by the RabbitMQ cluster operator on a RabbitmqCluster named rabbitmq,
    # and by cert-manager on its controller pod (scraped by prometheus-allow on 9402).
    "app.kubernetes.io/name": {"rabbitmq", "cert-manager"},
    # Set by cert-manager on its ACME HTTP-01 solver pods.
    "acme.cert-manager.io/http01-solver": {"true"},
}


def _collect_workload_labels() -> dict[str, set[str]]:
    """Return {label_key: {label_value, ...}} for all Deployments + CronJobs."""
    labels: dict[str, set[str]] = {}
    for pattern in WORKLOAD_GLOBS:
        for path in glob.glob(pattern, recursive=True):
            with open(path, encoding="utf-8") as fh:
                try:
                    docs = list(yaml.safe_load_all(fh))
                except yaml.YAMLError:
                    continue
                for doc in docs:
                    if not isinstance(doc, dict):
                        continue
                    kind = doc.get("kind")
                    if kind not in ("Deployment", "CronJob", "Service", "Job"):
                        continue
                    # Labels from metadata.labels
                    meta_labels = (doc.get("metadata") or {}).get("labels") or {}
                    for k, v in meta_labels.items():
                        labels.setdefault(k, set()).add(str(v))
                    # Labels from spec.template.metadata.labels (Deployment / Job)
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


def _discover_policy_files() -> list[Path]:
    """Return base networkpolicies, component files carrying NetworkPolicy, and goldens."""
    files: list[Path] = sorted(NETPOL_DIR.glob("*.yaml"))
    for path in sorted(COMPONENTS_DIR.rglob("*.yaml")):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        if "kind: NetworkPolicy" in text:
            files.append(path)
    for path in sorted(GOLDENS_DIR.glob("*.yaml")):
        files.append(path)
    return files


def validate_files(files: list[Path], workload_labels: dict[str, set[str]], quiet: bool = False) -> int:
    if not files:
        if not quiet:
            print("[validate] ERROR: no .yaml files found", file=sys.stderr)
        return 1

    yaml_ok = True
    label_errors: list[str] = []
    policies_seen = 0

    for path in files:
        try:
            rel = path.relative_to(REPO_ROOT)
        except ValueError:
            rel = path
        try:
            with open(path, encoding="utf-8") as fh:
                docs = list(yaml.safe_load_all(fh))
        except yaml.YAMLError as exc:  # pragma: no cover - defensive
            if not quiet:
                print(f"[validate] YAML ERROR in {rel}: {exc}", file=sys.stderr)
            yaml_ok = False
            continue

        pod_selector_refs: list[tuple[str, str]] = []
        file_policies = 0
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            if doc.get("kind") != "NetworkPolicy":
                continue
            file_policies += 1
            policies_seen += 1
            _walk_pod_selectors(doc.get("spec") or {}, pod_selector_refs)

        if not quiet:
            print(
                f"[validate] {rel}: parsed OK, {file_policies} NetworkPolicy doc(s), "
                f"{len(pod_selector_refs)} podSelector matchLabels refs"
            )

        for key, value in pod_selector_refs:
            known = workload_labels.get(key, set())
            if value not in known:
                label_errors.append(
                    f"{rel}: podSelector matchLabels references "
                    f"{key}={value!r} which is not a known workload label. "
                    f"Known values for {key!r}: {sorted(known) or '(none)'}"
                )

    if not yaml_ok:
        if not quiet:
            print("[validate] FAIL: YAML parse errors", file=sys.stderr)
        return 1
    if label_errors:
        if not quiet:
            print("[validate] FAIL: unknown pod-label references:", file=sys.stderr)
            for err in label_errors:
                print(f"  - {err}", file=sys.stderr)
        return 1

    if not quiet:
        print(
            f"[validate] PASS: {len(files)} files ({policies_seen} NetworkPolicy docs), "
            "all pod-label refs resolve."
        )
    return 0


def selftest() -> int:
    workload_labels = _collect_workload_labels()
    real_files = _discover_policy_files()
    rc_real = validate_files(real_files, workload_labels, quiet=True)
    if rc_real != 0:
        print("FAIL real-policies: expected rc=0", file=sys.stderr)
        return 1
    print("PASS real-policies")

    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=True, encoding="utf-8") as tmp:
        tmp.write(
            "apiVersion: networking.k8s.io/v1\n"
            "kind: NetworkPolicy\n"
            "metadata:\n"
            "  name: bogus-allow\n"
            "spec:\n"
            "  podSelector:\n"
            "    matchLabels:\n"
            "      app: bogus-workload-979\n"
        )
        tmp.flush()
        rc_bad = validate_files([Path(tmp.name)], workload_labels, quiet=True)
        if rc_bad != 1:
            print(f"FAIL unknown-label: expected rc=1, got rc={rc_bad}", file=sys.stderr)
            return 1
    print("PASS unknown-label")
    return 0


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        return selftest()

    files = _discover_policy_files()
    workload_labels = _collect_workload_labels()
    print(
        f"[validate] Found {sum(len(v) for v in workload_labels.values())} workload labels "
        f"across {len(workload_labels)} keys"
    )
    return validate_files(files, workload_labels)


if __name__ == "__main__":
    sys.exit(main())
