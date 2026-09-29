#!/usr/bin/env bash
#
# Gate: no retired object-store residue in any tracked file (Phase 36, SC6 / BLOB-09).
#
# WHY THIS EXISTS
#
#   Phase 36 retired the S3-API object store (the self-hosted product, its SDK, its env names,
#   its credential Secrets, its :9000 origin and the AWS CLI that fed backups to it) and moved
#   every environment to Azure Blob Storage, with Azurite locally. The removal was proven by
#   searches. A search run once proves nothing about next month: a copied snippet, a restored
#   doc, a reverted file or a new script written from memory brings the retired store back, and
#   nothing would say so. This gate makes the removal a property every pull request re-proves.
#
# WHAT IT ASSERTS
#
#   Over `git ls-files` (TRACKED files; binaries skipped with -I), no line matches:
#
#     R-1  (case-insensitive) the retired product name; the retired SDK (awssdk,
#          software.amazon, S3Client, S3Exception, NoSuchKeyException, forcePathStyle);
#          s3:// URLs; the retired config keys (storage.s3, s3.endpoint|region|bucket|
#          public-url|backup); the retired credential Secrets (s3-media-credentials,
#          s3-backup-credentials); the old origin (localhost:9000, 127.0.0.1:9000); the AWS CLI
#          (aws s3, aws-cli, awscli); AWS credential env names (AWS_ACCESS_KEY*, AWS_SECRET*,
#          AWS_DEFAULT_REGION); S3 hostnames (s3.<region>.amazonaws.com, s3-<...>.amazonaws.com)
#     R-1b (case-sensitive) an S3_ env name, including a prefixed one such as AWS_S3_BUCKET
#     R-2  (case-sensitive prose) the word S3, except in the "UI-SPEC S3" section label
#
#   ...unless the hit is covered by an entry in scripts/gates/object-store-residue-allowlist.conf.
#
#   It must NOT fire on the legitimate neighbours, and the self-test below proves it does not:
#   the SES SMTP host (email-smtp.<region>.amazonaws.com), the CSP "blob:" scheme, the retained
#   container names jtoye-images and jtoye-db-backups (D-07), and "UI-SPEC S3".
#
# THE ALLOWLIST
#
#   One entry per line, `<target>|<reason>`, full-line # comments and blank lines ignored.
#   <target> is one of:
#
#     path/file:N        exactly line N of that file              (line-level)
#     path/file:N-M      lines N..M of that file, inclusive       (a verbatim block)
#     path/file          every line of that file                  (only for records the plan
#                                                                   classes as historical)
#     path/dir/          every file under that directory          (dated record directories)
#
#   A hit is attributed to the MOST SPECIFIC entry that covers it (line/region, then file, then
#   the longest directory). Every entry must be earning its place:
#
#     - a blank reason                      -> FAIL (an exemption nobody can justify)
#     - the same target twice               -> FAIL
#     - an entry that covers no current hit -> FAIL (STALE: the line moved, was fixed, or the
#                                              entry is speculative, or it is shadowed by a more
#                                              specific entry). A list that can rot into a
#                                              blanket pass is not a list.
#     - a line with no `|`, an empty target, a bad range, or no entries at all -> VOID
#
#   Line entries are deliberately unforgiving: an edit that moves an allowed line makes the old
#   number stale AND the moved line a violation, so the gate goes red and the entry is renumbered
#   in the same change. That review is the point — a line allowance must never slide onto its
#   neighbour.
#
# SCOPE
#
#   `git ls-files` from the repository root, narrowed by GATE_SCAN_PATHSPEC (default "."; used by
#   the arms). With a narrowed scope, an entry whose path holds no scanned file is reported as
#   "not judged" instead of stale. Three files are excluded by path because they must NAME every
#   token they forbid: this script, its allowlist, and its hermetic arms
#   (scripts/gates/object-store-residue-selftest.sh). Untracked files are outside the contract.
#
# EXIT CODES
#
#   0  no violation, and every allowlist entry is reasoned, unique and live
#   1  a violation (named file:line), or an allowlist entry that is blank, duplicated or stale
#   2  VOID — git missing, `git grep -P` unsupported or unable to see a planted positive control,
#      zero tracked files in scope, a git grep error, or an allowlist that is missing, empty or
#      unparseable. "Found nothing" must never render as "there is nothing".
#
# TWO SELF-TRAPS THIS SCRIPT AVOIDS
#
#   1. Firing on its own definition. A gate that forbids a token must write that token, so this
#      file, the allowlist and the arms are excluded by exact path — never by a pattern that could
#      also hide a real file.
#   2. A pattern that never matches. Before scanning, every pattern class is run through the SAME
#      engine and flags as the scan (`git grep -P`, with and without -i) against synthetic
#      positive controls, each of which must match, and the negative controls, none of which may.
#      A miss in either direction is a VOID, not a pass. Search is `git ls-files` / `git grep`
#      only: the shell's grep/rg on a workstation may be .gitignore-honouring functions that do
#      not even exist inside `bash script.sh`, and a recursive search would silently under-read.
#      Every git grep exit code is captured on its own statement (1 = no match, >1 = error ->
#      VOID), and matching is done on data in hand with here-strings, never `cmd | grep -q`
#      (which inverts on a match under pipefail).

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

SELF_REL="scripts/check-no-object-store-residue.sh"
ALLOWLIST_REL="scripts/gates/object-store-residue-allowlist.conf"
SELFTEST_REL="scripts/gates/object-store-residue-selftest.sh"
SCOPE="${GATE_SCAN_PATHSPEC:-.}"

void() { echo "VOID: $*" >&2; exit 2; }

echo "Object-store residue gate (Phase 36)"

command -v git >/dev/null 2>&1 || void "git not found"
cd "$REPO_ROOT" || void "cannot enter $REPO_ROOT"
inside="$(git rev-parse --is-inside-work-tree 2>/dev/null)"; rc=$?
[ "$rc" -eq 0 ] && [ "$inside" = "true" ] || void "$REPO_ROOT is not a git work tree"

# ---- the patterns -------------------------------------------------------------------------------
R1='minio|awssdk|software\.amazon|S3Client|S3Exception|NoSuchKeyException|forcePathStyle|s3://|storage\.s3\b|\bs3\.(endpoint|region|bucket|public-url|backup)|s3-(media|backup)-credentials|(localhost|127\.0\.0\.1):9000|\baws s3\b|aws-cli|awscli|AWS_ACCESS_KEY|AWS_SECRET|AWS_DEFAULT_REGION|\bs3[.-][a-z0-9.-]*amazonaws\.com'
R1B='(?<![A-Za-z0-9])S3_[A-Z][A-Z_]*'
R2='(?<!UI-SPEC )\bS3\b'

# ---- self-test: the same engine and flags as the scan, on synthetic controls ---------------------
ST="$(mktemp -d "${TMPDIR:-/tmp}/residue-gate-selftest.XXXXXX")" || void "mktemp failed"
trap 'rm -rf "$ST"' EXIT

# count_matches <file> <case: i|s> <pattern> -> number of matching lines; VOIDs on an engine error
count_matches() {
    local file="$1" mode="$2" pat="$3" out rc
    if [ "$mode" = i ]; then
        out="$(cd "$ST" && git grep --no-index -h -n -I -i -P -e "$pat" -- "$file" 2>&1)"; rc=$?
    else
        out="$(cd "$ST" && git grep --no-index -h -n -I -P -e "$pat" -- "$file" 2>&1)"; rc=$?
    fi
    case "$rc" in
        0) grep -c '' <<< "$out" ;;
        1) echo 0 ;;
        *) void "self-test: git grep -P failed (rc=$rc) — PCRE lookbehind unsupported or the pattern is invalid: $out" ;;
    esac
}

printf '%s\n' \
    'the MinIO console' 'minio lower' 'MINIO_ROOT_USER' \
    'implementation("software.amazon.awssdk:s3")' 'awssdk' 'S3Client.builder()' 'S3Exception' \
    'NoSuchKeyException' '.forcePathStyle(true)' 's3://jtoye-db-backups/x' 'storage.s3.bucket' \
    's3.public-url=x' 's3.backup.endpoint' 's3-media-credentials' 's3-backup-credentials' \
    'http://localhost:9000/x' 'http://127.0.0.1:9000/x' 'aws s3 cp a b' 'apk add aws-cli' \
    'pip install awscli' 'AWS_ACCESS_KEY_ID' 'AWS_SECRET_ACCESS_KEY' 'AWS_DEFAULT_REGION' \
    'https://s3.eu-west-2.amazonaws.com/b' 'https://s3-eu-west-1.amazonaws.com/b' > "$ST/r1-pos.txt"
printf '%s\n' 'S3_BUCKET=x' '${S3_ENDPOINT}' 'AWS_S3_BUCKET=x' > "$ST/r1b-pos.txt"
printf '%s\n' 'an S3 bucket' 'Blob/S3' '(S3)' > "$ST/r2-pos.txt"
printf '%s\n' \
    'email-smtp.eu-west-2.amazonaws.com' "img-src 'self' blob: data:" 'jtoye-images' \
    'jtoye-db-backups' 'see UI-SPEC S3 for the layout' 's3cret' 'S3X' > "$ST/neg.txt"

for spec in "r1-pos.txt|i|$R1" "r1b-pos.txt|s|$R1B" "r2-pos.txt|s|$R2"; do
    f="${spec%%|*}"; rest="${spec#*|}"; mode="${rest%%|*}"; pat="${rest#*|}"
    want="$(grep -c '' "$ST/$f")"
    got="$(count_matches "$f" "$mode" "$pat")" || exit 2
    [ "$got" = "$want" ] || void "self-test: $f matched $got of $want positive controls — a pattern class cannot see what it exists to catch"
    got="$(count_matches neg.txt "$mode" "$pat")" || exit 2
    [ "$got" = 0 ] || void "self-test: pattern for $f matched $got negative control line(s) — the gate would fire on a legitimate neighbour"
done
echo "  self-test  : 3 pattern classes see all $(( $(grep -c '' "$ST/r1-pos.txt") + $(grep -c '' "$ST/r1b-pos.txt") + $(grep -c '' "$ST/r2-pos.txt") )) positive controls and none of $(grep -c '' "$ST/neg.txt") negative controls"

# ---- scope -----------------------------------------------------------------------------------------
EXCLUDES=(":(exclude)$SELF_REL" ":(exclude)$ALLOWLIST_REL" ":(exclude)$SELFTEST_REL")
# NUL-delimited through a FILE: a bash variable silently drops NUL bytes, and without -z git
# quotes unusual paths, so the names would not match what `git grep -z` prints.
git ls-files -z -- "$SCOPE" "${EXCLUDES[@]}" > "$ST/files" 2> "$ST/ls.err"; rc=$?
[ "$rc" -eq 0 ] || void "git ls-files failed (rc=$rc): $(cat "$ST/ls.err")"
declare -a SCOPE_FILES=()
mapfile -t -d '' SCOPE_FILES < "$ST/files"
N_FILES="${#SCOPE_FILES[@]}"
echo "  scope      : '$SCOPE' -> $N_FILES tracked file(s) (excluding the gate, its allowlist and its arms)"
[ "$N_FILES" -gt 0 ] || void "zero tracked files in scope '$SCOPE' — refusing to report clean over an empty scan"

# ---- allowlist -------------------------------------------------------------------------------------
ALLOWLIST="$REPO_ROOT/$ALLOWLIST_REL"
[ -f "$ALLOWLIST" ] && [ -r "$ALLOWLIST" ] || void "allowlist missing or unreadable: $ALLOWLIST_REL"

declare -a E_TARGET=() E_KIND=() E_PATH=() E_FROM=() E_TO=() E_REASON=() E_SRC=()
declare -A SEEN_TARGET=()
HYGIENE=0
lineno=0
while IFS= read -r raw || [ -n "$raw" ]; do
    lineno=$((lineno + 1))
    line="${raw%$'\r'}"
    trimmed="${line#"${line%%[![:space:]]*}"}"
    case "$trimmed" in ''|\#*) continue ;; esac
    [[ "$line" == *"|"* ]] || void "$ALLOWLIST_REL:$lineno is unparseable (no '|' separator): '$line'"
    target="${line%%|*}"; reason="${line#*|}"
    target="${target#"${target%%[![:space:]]*}"}"; target="${target%"${target##*[![:space:]]}"}"
    reason="${reason#"${reason%%[![:space:]]*}"}"; reason="${reason%"${reason##*[![:space:]]}"}"
    [ -n "$target" ] || void "$ALLOWLIST_REL:$lineno has an empty target"
    if [[ "$target" =~ ^(.+):([0-9]+)(-([0-9]+))?$ ]]; then
        path="${BASH_REMATCH[1]}"; from="${BASH_REMATCH[2]}"; to="${BASH_REMATCH[4]:-$from}"
        [ "$from" -ge 1 ] && [ "$to" -ge "$from" ] || void "$ALLOWLIST_REL:$lineno has a bad line range: '$target'"
        kind=line
    elif [[ "$target" == */ ]]; then
        path="$target"; from=0; to=0; kind=dir
    else
        path="$target"; from=0; to=0; kind=file
    fi
    if [ -z "$reason" ]; then
        echo "  ALLOWLIST $ALLOWLIST_REL:$lineno: blank reason for '$target' — an exemption must say why" >&2
        HYGIENE=$((HYGIENE + 1))
    fi
    if [ -n "${SEEN_TARGET[$target]+set}" ]; then
        echo "  ALLOWLIST $ALLOWLIST_REL:$lineno: duplicate entry '$target' (first at line ${SEEN_TARGET[$target]})" >&2
        HYGIENE=$((HYGIENE + 1))
        continue
    fi
    SEEN_TARGET["$target"]="$lineno"
    E_TARGET+=("$target"); E_KIND+=("$kind"); E_PATH+=("$path"); E_FROM+=("$from"); E_TO+=("$to")
    E_REASON+=("$reason"); E_SRC+=("$lineno")
done < "$ALLOWLIST"
N_ENTRIES="${#E_TARGET[@]}"
[ "$N_ENTRIES" -gt 0 ] || void "$ALLOWLIST_REL declares no entries — an empty allowlist is a broken file, not a clean tree"

# Entry indexes by kind, so a hit is checked against the few entries that could cover it.
declare -A LINE_IDX=() FILE_IDX=()
declare -a DIR_IDX=()
for ((i = 0; i < N_ENTRIES; i++)); do
    case "${E_KIND[$i]}" in
        line) LINE_IDX["${E_PATH[$i]}"]+="$i " ;;
        file) FILE_IDX["${E_PATH[$i]}"]=$i ;;
        dir)  DIR_IDX+=("$i") ;;
    esac
done

# ---- scan ------------------------------------------------------------------------------------------
declare -A HIT_CLASSES=() HIT_TEXT=()
declare -a HIT_ORDER=()
RAW_HITS=0

# scan <class-label> <git grep flags...> -- collects path/line hits; VOIDs on a git grep error
scan() {
    local label="$1"; shift
    local rc p n c key
    # -z output goes to a FILE (path NUL line NUL text): NUL bytes cannot live in a variable.
    git grep -z -n -I "$@" -- "$SCOPE" "${EXCLUDES[@]}" > "$ST/hits" 2> "$ST/grep.err"; rc=$?
    case "$rc" in
        0) ;;
        1) return 0 ;;
        *) void "git grep failed for $label (rc=$rc): $(cat "$ST/grep.err")" ;;
    esac
    while IFS= read -r -d '' p && IFS= read -r -d '' n && IFS= read -r c; do
        RAW_HITS=$((RAW_HITS + 1))
        key="$p"$'\x1f'"$n"
        if [ -z "${HIT_CLASSES[$key]+set}" ]; then
            HIT_ORDER+=("$key"); HIT_CLASSES["$key"]="$label"; HIT_TEXT["$key"]="$c"
        else
            HIT_CLASSES["$key"]+=",$label"
        fi
    done < "$ST/hits"
}
scan R-1  -i -P -e "$R1"
scan R-1b -P -e "$R1B"
scan R-2  -P -e "$R2"

# ---- classify --------------------------------------------------------------------------------------
declare -a E_COUNT=()
for ((i = 0; i < N_ENTRIES; i++)); do E_COUNT[$i]=0; done
VIOLATIONS=0
ALLOWED=0
for key in "${HIT_ORDER[@]}"; do
    p="${key%%$'\x1f'*}"; n="${key#*$'\x1f'}"
    best=-1
    for i in ${LINE_IDX[$p]:-}; do
        if [ "$n" -ge "${E_FROM[$i]}" ] && [ "$n" -le "${E_TO[$i]}" ]; then best=$i; break; fi
    done
    if [ "$best" -lt 0 ] && [ -n "${FILE_IDX[$p]+set}" ]; then best="${FILE_IDX[$p]}"; fi
    if [ "$best" -lt 0 ]; then
        bestlen=0
        for i in "${DIR_IDX[@]}"; do
            d="${E_PATH[$i]}"
            if [ "${p#"$d"}" != "$p" ] && [ "${#d}" -gt "$bestlen" ]; then best=$i; bestlen="${#d}"; fi
        done
    fi
    if [ "$best" -ge 0 ]; then
        E_COUNT[$best]=$(( E_COUNT[best] + 1 )); ALLOWED=$((ALLOWED + 1))
    else
        text="${HIT_TEXT[$key]}"; text="${text#"${text%%[![:space:]]*}"}"
        [ "${#text}" -gt 160 ] && text="${text:0:160}..."
        echo "  VIOLATION $p:$n: [${HIT_CLASSES[$key]}] $text" >&2
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
done

# ---- stale entries ----------------------------------------------------------------------------------
# Under the default scope every zero-count entry is stale. Under a narrowed scope, an entry whose
# path holds no scanned file was simply not looked at, and is reported as such rather than judged.
declare -A IN_SCOPE=()
for f in "${SCOPE_FILES[@]}"; do IN_SCOPE["$f"]=1; done
entry_in_scope() {
    local i="$1" f d
    [ "$SCOPE" = "." ] && return 0
    if [ "${E_KIND[$i]}" = dir ]; then
        d="${E_PATH[$i]}"
        for f in "${SCOPE_FILES[@]}"; do [ "${f#"$d"}" != "$f" ] && return 0; done
        return 1
    fi
    [ -n "${IN_SCOPE[${E_PATH[$i]}]+set}" ]
}

STALE=0
NOT_JUDGED=0
echo "  allowlist  : $N_ENTRIES entr(ies) in $ALLOWLIST_REL"
for ((i = 0; i < N_ENTRIES; i++)); do
    if [ "${E_COUNT[$i]}" -gt 0 ]; then
        printf '    %5d  %s\n' "${E_COUNT[$i]}" "${E_TARGET[$i]}"
    elif entry_in_scope "$i"; then
        hint=""
        if [ "${E_KIND[$i]}" = line ]; then
            now=""
            for key in "${HIT_ORDER[@]}"; do
                [ "${key%%$'\x1f'*}" = "${E_PATH[$i]}" ] && now+="${key#*$'\x1f'} "
            done
            hint=" (hits in ${E_PATH[$i]} are now at line(s): ${now:-none})"
        fi
        echo "  ALLOWLIST $ALLOWLIST_REL:${E_SRC[$i]}: stale entry '${E_TARGET[$i]}' covers no current hit$hint — delete it, or renumber it in the same change that moved the line" >&2
        STALE=$((STALE + 1))
    else
        printf '    %5s  %s  (not judged: outside scope %s)\n' "-" "${E_TARGET[$i]}" "$SCOPE"
        NOT_JUDGED=$((NOT_JUDGED + 1))
    fi
done

echo "SUMMARY files_scanned=$N_FILES raw_hits=$RAW_HITS unique_lines=${#HIT_ORDER[@]} allowlisted=$ALLOWED violations=$VIOLATIONS stale_entries=$STALE hygiene_failures=$HYGIENE entries_not_judged=$NOT_JUDGED"

if [ "$VIOLATIONS" -gt 0 ] || [ "$STALE" -gt 0 ] || [ "$HYGIENE" -gt 0 ]; then
    echo "FAIL: $VIOLATIONS residue line(s), $STALE stale and $HYGIENE malformed allowlist entr(ies)." >&2
    [ "$VIOLATIONS" -gt 0 ] && echo "      Rewrite the line to the current storage (Azure Blob Storage; Azurite locally). Allowlist it only if it is a historical record, line by line, with the reason." >&2
    exit 1
fi

JUDGED=$((N_ENTRIES - NOT_JUDGED))
if [ "$NOT_JUDGED" -gt 0 ]; then
    # A narrowed scope must not read as a whole-tree pass: say how much of the allowlist was
    # actually exercised, so an arm's green line cannot be quoted as the repository's.
    echo "PASS (narrowed scope '$SCOPE'): no retired object-store residue in $N_FILES tracked file(s); $ALLOWED hit line(s) covered by $JUDGED judged allowlist entr(ies); $NOT_JUDGED entr(ies) outside the scope were NOT judged."
else
    echo "PASS: no retired object-store residue in $N_FILES tracked file(s); $ALLOWED hit line(s) covered by $JUDGED reasoned, live allowlist entr(ies)."
fi
exit 0
