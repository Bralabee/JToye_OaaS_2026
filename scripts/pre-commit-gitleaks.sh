#!/bin/sh
# Opt-in local pre-commit hook: scan staged changes for secrets.
#
# This hook is NOT installed automatically.
#
# DO NOT ENABLE IT WITH `git config core.hooksPath scripts`. That instruction used
# to be here and it is actively harmful: a repo-level core.hooksPath REPLACES the
# global dispatcher directory, so setting it here silently disables this repo's
# `prepare-commit-msg` (which strips the Co-Authored-By trailers this project's git
# policy forbids) and its `pre-push` gate. scripts/install-hooks.sh exists partly to
# REMOVE exactly that override — see its header, which measured the damage.
#
# NOR can it currently be enabled the way this repo's other hooks are. Every hook
# here is reached through a GLOBAL dispatcher that delegates to `.githooks/<name>`,
# and measured 2026-08-24 that set contains exactly three members:
#
#     ~/.git-hooks/post-merge  ~/.git-hooks/prepare-commit-msg  ~/.git-hooks/pre-push
#
# There is NO pre-commit dispatcher. A committed `.githooks/pre-commit` would
# therefore be picked up by nothing and skipped in silence — the same failure mode
# install-hooks.sh guards for the executable bit, where "the hook is present" reads
# as coverage while nothing runs. Do not add that file expecting it to fire.
#
# Enabling this script needs a `pre-commit` dispatcher installed at the MACHINE
# level alongside the other three, which is a workstation concern and outside this
# repo's control.
#
# None of that is urgent, because `.githooks/pre-push` (P-3) already scans the
# pushed commit range, and that is the boundary that actually matters: this repo is
# public, so a secret is compromised the moment it lands on the remote, not when it
# is committed locally. A pre-commit scan would be an earlier, cheaper catch — not
# the load-bearing gate.
#
# Requires the gitleaks CLI — install from:
#   https://github.com/gitleaks/gitleaks#installing
#
# This hook reads `.gitleaks.toml` at the repo root so its allowlist
# matches the CI workflow in `.github/workflows/gitleaks.yml`.
#
# `gitleaks protect` is an UNDOCUMENTED alias as of 8.30.1 — absent from
# `gitleaks --help` yet still functional (verified 2026-08-24: it returned rc=1 on a
# planted staged credential, same verdict as `gitleaks git --staged`). An alias the
# CLI no longer advertises can be dropped without notice, and this script would then
# fail open on a missing subcommand. If that day comes, the replacement is
# `gitleaks git --staged`.

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
