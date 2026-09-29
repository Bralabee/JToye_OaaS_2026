#!/usr/bin/env bash
#
# object-store-residue-selftest.sh — hermetic arms for scripts/check-no-object-store-residue.sh
#
# WHY THIS EXISTS
#
#   A residue gate that has only ever been seen PASSING may be incapable of failing. This repo
#   has shipped exactly that more than once: an already-0 grep, a pattern that never matched, a
#   gate that fired on its own definition, a guard that failed open under pipefail. So every
#   behaviour the gate promises is re-proved here on every CI run, not once in a SUMMARY.
#
#   Each arm builds a THROWAWAY git repository under mktemp, copies the gate into it, plants one
#   condition (a residue token in a fresh file, a token on the line NEXT TO an allowlisted line,
#   a blank-reason entry, a stale entry, an empty scan, ...) and asserts the gate's exit code and
#   the file:line it names. Nothing here reads or writes the project tree, so the arms cannot
#   leave residue behind and cannot be fooled by the project's own allowlist.
#
#   Files are STAGED in the throwaway repository and never committed: the gate's contract is
#   "tracked files" (git ls-files), which the index satisfies, and a commit would run whatever
#   global commit hooks the machine has.
#
# OUTPUT
#
#   TAP ("ok N - name" / "not ok N - name", then "# tests / # pass / # fail"), so a failing arm is
#   named, not just counted.
#
# EXIT CODES
#
#   0  every arm behaved as asserted
#   1  at least one arm did not
#   2  VOID — the gate script or git is missing, or the throwaway repository could not be built
#
# This file necessarily contains every token the gate forbids, so the gate excludes it by path,
# exactly as it excludes itself and its allowlist.

set -uo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="${GATE:-$REPO_ROOT/scripts/check-no-object-store-residue.sh}"

void() { echo "VOID: $*" >&2; exit 2; }

command -v git >/dev/null 2>&1 || void "git not found"
[ -f "$GATE" ] || void "gate not found: $GATE"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/residue-selftest.XXXXXX")" || void "mktemp failed"
trap 'rm -rf "$WORK"' EXIT
R="$WORK/repo"

TESTS=0
PASSED=0
FAILED=0

# ---- fixture -------------------------------------------------------------------------------------
# A small tree with ONE historical directory (prefix entry), ONE live file with a single allowed
# line (line entry), ONE live file with an allowed two-line region, and a file of negative
# controls. The gate is copied in at its real path so its self-exclusion is exercised too.
setup_repo() {
    rm -rf "$R"
    mkdir -p "$R/scripts/gates" "$R/docs/hist" || void "cannot create $R"
    git -C "$R" init -q || void "git init failed"
    cp "$GATE" "$R/scripts/check-no-object-store-residue.sh" || void "cannot copy the gate"

    printf '%s\n' 'a dated record' 'the MinIO console of 2026-07' 'more history' > "$R/docs/hist/old.md"
    printf '%s\n' 'live line one' 'the retired s3-media-credentials Secret (kept on purpose)' 'live line three' 'live line four' > "$R/live.md"
    printf '%s\n' 'region line one' 'fenced: s3://jtoye-db-backups/x' 'fenced: aws s3 cp' 'region line four' > "$R/region.md"
    printf '%s\n' \
        'smtp host email-smtp.eu-west-2.amazonaws.com' \
        "img-src 'self' blob: data:" \
        'container jtoye-images' \
        'container jtoye-db-backups' \
        'see UI-SPEC S3 for the layout' > "$R/neg.md"

    write_allowlist \
        'docs/hist/|dated point-in-time records' \
        'live.md:2|the one deliberately historical line in a live file' \
        'region.md:2-3|a verbatim fenced record'
    stage
}

write_allowlist() {
    {
        echo '# fixture allowlist'
        local e
        for e in "$@"; do printf '%s\n' "$e"; done
    } > "$R/scripts/gates/object-store-residue-allowlist.conf"
}

stage() {
    # Explicit paths only. -f so nothing on the machine's global excludes can hide a planted file.
    local f files=()
    while IFS= read -r f; do files+=("$f"); done < <(cd "$R" && find . -type f -not -path './.git/*' | sed 's#^\./##' | sort)
    git -C "$R" add -f -- "${files[@]}" || void "git add failed in the throwaway repository"
}

OUT=""
RC=0
run_gate() {
    OUT="$(cd "$R" && env "$@" bash scripts/check-no-object-store-residue.sh 2>&1)"; RC=$?
}

# check <name> <expected-rc> [<substring that must appear>] [!<substring that must NOT appear>]
check() {
    local name="$1" want="$2"; shift 2
    local ok=1 why="" s
    TESTS=$((TESTS + 1))
    [ "$RC" -eq "$want" ] || { ok=0; why="rc=$RC, expected $want"; }
    for s in "$@"; do
        if [ "${s:0:1}" = "!" ]; then
            if grep -qF -- "${s:1}" <<< "$OUT"; then ok=0; why="${why:+$why; }output names '${s:1}'"; fi
        else
            grep -qF -- "$s" <<< "$OUT" || { ok=0; why="${why:+$why; }output lacks '$s'"; }
        fi
    done
    if [ "$ok" -eq 1 ]; then
        PASSED=$((PASSED + 1)); echo "ok $TESTS - $name"
    else
        FAILED=$((FAILED + 1)); echo "not ok $TESTS - $name"
        echo "  # $why"
        sed 's/^/  #   | /' <<< "$(tail -n 12 <<< "$OUT")"
    fi
}

echo "TAP version 13"

# ---- 1. the fixture is clean: allowlisted residue + negative controls -> 0 -------------------------
setup_repo; run_gate
check "fixture with only allowlisted residue and negative controls exits 0" 0 "PASS"

# ---- 2. the five planted residue shapes, each in a FRESH file and NEXT TO an allowed line ----------
TOKENS=(
    'MinIO:the MinIO console'
    'S3_ env name:S3_BUCKET=jtoye-images'
    'old origin:http://localhost:9000/jtoye-images/a.webp'
    'retired image:image: quay.io/minio/minio:RELEASE.2025'
    'retired Secret name:kubectl delete secret s3-backup-credentials'
)
for t in "${TOKENS[@]}"; do
    label="${t%%:*}"; token="${t#*:}"

    setup_repo
    printf '%s\n' 'clean' "$token" > "$R/fresh.md"; stage; run_gate
    check "planted $label in a fresh tracked file exits 1 naming file:line" 1 "VIOLATION fresh.md:2:"

    setup_repo
    # live.md:2 is allowlisted; line 3 is its neighbour and must NOT be covered by that entry.
    printf '%s\n' 'live line one' 'the retired s3-media-credentials Secret (kept on purpose)' "$token" 'live line four' > "$R/live.md"
    stage; run_gate
    check "planted $label on the line next to an allowlisted line exits 1 naming that line only" 1 "VIOLATION live.md:3:" "!VIOLATION live.md:2:"
done

# ---- 3. case-insensitivity of the product name -----------------------------------------------------
setup_repo
printf '%s\n' 'minio lower' 'MinIO mixed' 'MINIO_ROOT_USER upper' > "$R/fresh.md"; stage; run_gate
check "minio / MinIO / MINIO are all caught" 1 "VIOLATION fresh.md:1:" "VIOLATION fresh.md:2:" "VIOLATION fresh.md:3:"

# ---- 4. the rest of the R-1 token classes and the R-2 prose rule -----------------------------------
CLASSES=(
    'SDK coordinate:implementation("software.amazon.awssdk:s3")'
    'SDK artifact name:awssdk bump'
    'SDK client type:S3Client client = S3Client.builder()'
    'SDK exception type:catch (NoSuchKeyException e)'
    'path-style flag:.forcePathStyle(true)'
    's3 URL:s3://jtoye-db-backups/backups/x.dump'
    'retired config key:storage.s3.bucket: jtoye-images'
    'retired dotted key:s3.public-url=http://x'
    'loopback origin:http://127.0.0.1:9000/jtoye-images/'
    'AWS CLI:aws s3 cp x y'
    'AWS CLI package:apk add aws-cli'
    'AWS credential env:AWS_SECRET_ACCESS_KEY=x'
    'AWS region env:AWS_DEFAULT_REGION=eu-west-2'
    'S3 host:https://s3.eu-west-2.amazonaws.com/b'
    'prefixed S3_ env:AWS_S3_BUCKET=x'
    'S3 as the store in prose:media lives in an S3 bucket'
)
for t in "${CLASSES[@]}"; do
    label="${t%%:*}"; token="${t#*:}"
    setup_repo
    printf '%s\n' "$token" > "$R/fresh.md"; stage; run_gate
    check "planted $label exits 1" 1 "VIOLATION fresh.md:1:"
done

# ---- 5. negative controls: the gate must not fire on its neighbours --------------------------------
setup_repo
cp "$R/neg.md" "$R/fresh-neg.md"; stage; run_gate
check "a fresh file of only negative controls (SES host, blob:, jtoye-images, jtoye-db-backups, UI-SPEC S3) exits 0" 0 "PASS" "!fresh-neg.md"

setup_repo; run_gate GATE_SCAN_PATHSPEC=neg.md
check "scanning only the negative-control file exits 0 (entries outside the scope are not judged)" 0 "PASS" "!VIOLATION" "were NOT judged"

# ---- 6. region entries cover their lines and nothing past them -------------------------------------
setup_repo
printf '%s\n' 'region line one' 'fenced: s3://jtoye-db-backups/x' 'fenced: aws s3 cp' 'fenced: MinIO' > "$R/region.md"; stage; run_gate
check "a token one line past an allowlisted region exits 1 naming that line only" 1 "VIOLATION region.md:4:" "!VIOLATION region.md:3:"

# ---- 7. allowlist hygiene --------------------------------------------------------------------------
setup_repo
write_allowlist 'docs/hist/|dated point-in-time records' 'live.md:2|the one deliberately historical line in a live file' 'region.md:2-3|a verbatim fenced record' 'docs/archive/|'
mkdir -p "$R/docs/archive"; printf '%s\n' 'old MinIO note' > "$R/docs/archive/x.md"; stage; run_gate
check "an entry with a blank reason exits 1" 1 "blank reason"

setup_repo
write_allowlist 'docs/hist/|dated point-in-time records' 'live.md:2|the one deliberately historical line in a live file' 'region.md:2-3|a verbatim fenced record' 'live.md:2|the same line again'
stage; run_gate
check "a duplicate entry exits 1" 1 "duplicate"

setup_repo
write_allowlist 'docs/hist/|dated point-in-time records' 'live.md:2|the one deliberately historical line in a live file' 'region.md:2-3|a verbatim fenced record' 'gone.md|a file that no longer exists'
stage; run_gate
check "an entry for a path with no hits (stale) exits 1" 1 "stale" "gone.md"

setup_repo
write_allowlist 'docs/hist/|dated point-in-time records' 'live.md:2|the one deliberately historical line in a live file' 'region.md:2-3|a verbatim fenced record' 'live.md:3|a line that holds no residue'
stage; run_gate
check "a line entry pointing at a clean line (stale) exits 1" 1 "stale" "live.md:3"

setup_repo
write_allowlist 'docs/hist/|dated point-in-time records' 'live.md:2|the one deliberately historical line in a live file' 'region.md:2-3|a verbatim fenced record' 'docs/|a broad prefix shadowed by the narrower docs/hist/ entry'
stage; run_gate
check "an entry fully shadowed by a more specific one matches nothing and exits 1" 1 "stale" "docs/"

# ---- 8. VOID: an empty scan, and an allowlist that cannot be trusted -------------------------------
setup_repo; run_gate GATE_SCAN_PATHSPEC=no-such-dir/
check "a scan pathspec matching no tracked files exits 2" 2 "VOID"

setup_repo; rm -f "$R/scripts/gates/object-store-residue-allowlist.conf"; run_gate
check "a missing allowlist exits 2" 2 "VOID"

setup_repo; write_allowlist; stage; run_gate
check "an allowlist with no entries exits 2" 2 "VOID"

setup_repo; write_allowlist 'docs/hist/ dated records, no separator'; stage; run_gate
check "an unparseable allowlist line exits 2" 2 "VOID"

# ---- 9. the contract is TRACKED files ---------------------------------------------------------------
setup_repo; printf '%s\n' 'MinIO' > "$R/untracked.md"; run_gate
check "an untracked file is outside the contract (exits 0, not named)" 0 "PASS" "!untracked.md"

echo "# tests $TESTS"
echo "# pass $PASSED"
echo "# fail $FAILED"
[ "$TESTS" -gt 0 ] || void "no arms ran"
[ "$FAILED" -eq 0 ]
