#!/bin/sh
# Opt-in local pre-commit hook: scan staged changes for secrets.
#
# This hook is NOT installed automatically. To enable it on your workstation:
#
#   ln -sf pre-commit-gitleaks.sh scripts/pre-commit
#   git config core.hooksPath scripts
#
# (Git requires the hook filename to be exactly `pre-commit`, hence the symlink.)
#
# Requires the gitleaks CLI — install from:
#   https://github.com/gitleaks/gitleaks#installing
#
# This hook reads `.gitleaks.toml` at the repo root so its allowlist
# matches the CI workflow in `.github/workflows/gitleaks.yml`.

if ! command -v gitleaks >/dev/null 2>&1; then
  echo "gitleaks not installed — skipping. Install: https://github.com/gitleaks/gitleaks#installing"
  exit 0
fi

echo "Running gitleaks on staged changes…"
if ! gitleaks protect --staged --redact --config .gitleaks.toml; then
  echo ""
  echo "gitleaks found a potential secret in your staged changes."
  echo "Fix the issue or add the file to .gitleaks.toml allowlist if it's a false positive."
  exit 1
fi
