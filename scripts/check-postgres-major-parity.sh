#!/usr/bin/env bash
# check-postgres-major-parity.sh — the backup tooling's PostgreSQL major must equal
# the server's, everywhere both are declared.
#
# ---------------------------------------------------------------------------
# WHY THIS EXISTS
#
#   Dependabot #525 bumped infra/backups/Dockerfile from postgres:15-bookworm to
#   postgres:18-bookworm while every server in the tree stayed on 15. Every CI
#   check was GREEN. Measured against the live 15.17 server on 2026-08-04:
#
#     postgres:18 pg_dump -Fc against the 15.17 server   -> works, 469,421 bytes
#     postgres:15 pg_restore --list <that pg18 dump>     -> rc=1
#         pg_restore: error: unsupported version (1.16) in file header
#     postgres:15 pg_restore --list <a pg15 dump>        -> rc=0   (control)
#     postgres:18 pg_restore --list <a pg15 dump>        -> rc=0   (direction check)
#
#   So the asymmetry is: tooling reads its own major and OLDER, never NEWER. A
#   backup image ahead of its server keeps taking backups that succeed and become
#   unrestorable by the server's own tooling. The failure surfaces during a
#   recovery, which is the worst possible moment to discover it.
#
#   The bump is not wrong forever — it is REQUIRED to perform a 15->18 upgrade,
#   because the logical upgrade path dumps with the NEW tooling. It is wrong
#   ALONE. This gate does not forbid postgres 18; it forbids the halves diverging.
#
# WHY A COMMENT WAS NOT ENOUGH
#
#   infra/backups/Dockerfile already said, two lines above the line dependabot
#   changed:
#
#     # The tag must match the image referenced in k8s/base/pg-backup-cronjob.yaml.
#
#   A comment cannot fail a build. This script is that sentence, made executable.
#
# WHY THE BACKUP SCRIPT'S OWN CHECK CANNOT CATCH IT
#
#   infra/backups/k8s-backup.sh verifies each dump with
#       pg_restore --list "$TMP" || fail "dump is not a readable pg_restore archive"
#   which runs INSIDE the image that produced the dump. pg18 tooling reading a
#   pg18 archive always agrees. The verification agrees with the dump BY
#   CONSTRUCTION and is structurally incapable of observing this fault — the same
#   shape as the edge<->core `processed_count` bug (#337), where both sides
#   encoded with the same struct. A gate outside that loop is the only place the
#   question can be asked.
#
# WHAT THIS DOES *NOT* PROVE
#
#   Parity of DECLARED majors. It does not run pg_dump, does not test a restore,
#   and cannot tell you the backups are recoverable — only that the two halves
#   claim the same major. A restore rehearsal is a different, and stronger, check
#   that this repo does not yet have (docs/runbooks/backups.md documents the
#   procedure; nothing executes it). Do not read a green run here as "backups
#   restore".
#
# EXIT CODES
#   0 = every declared site agrees on one major
#   1 = a contract violation (the majors disagree)
#   2 = VOID — could not evaluate. NEVER treat as a pass.
#
# USAGE
#   bash scripts/check-postgres-major-parity.sh
#   CONF=/path/to/other.conf bash scripts/check-postgres-major-parity.sh   # for tests

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONF="${CONF:-$SCRIPT_DIR/gates/postgres-major-parity.conf}"

# `grep` and `rg` are Claude Code SHELL FUNCTIONS on this machine and do not exist
# inside an exec'd script — a bare `grep` here returns 127 and reads exactly like
# "found nothing". Extraction therefore uses awk, which is a real binary.
AWK="$(command -v awk || true)"

fail_count=0
void() { printf 'VOID: %s\n' "$*" >&2; exit 2; }
fail() { printf 'FAIL: %s\n' "$*" >&2; fail_count=$((fail_count + 1)); }

[ -n "$AWK" ] || void "awk not found — cannot extract versions"
[ -f "$CONF" ] || void "config not found: $CONF"

# ---------------------------------------------------------------------------
# extract_major <file> <anchor>
#   Prints every major version declared on a line containing <anchor>, one per
#   line. A major is the digit run immediately following the anchor, and it must
#   be terminated by end-of-line, '-', '"', or whitespace.
#
#   The terminator rule is load-bearing: it rejects `postgres:5432/keycloak` (a
#   JDBC port, terminated by '/') while accepting `postgres:15-alpine`,
#   `postgres:15` and `pin: "postgres:15-alpine"`.
# ---------------------------------------------------------------------------
extract_major() {
	"$AWK" -v anchor="$2" '
	{
		s = $0
		while ((i = index(s, anchor)) > 0) {
			rest = substr(s, i + length(anchor))
			if (match(rest, /^[0-9]+/)) {
				num  = substr(rest, 1, RLENGTH)
				term = substr(rest, RLENGTH + 1, 1)
				if (term == "" || term == "-" || term == "\"" || term == " " || term == "\t")
					print num
			}
			s = substr(s, i + length(anchor))
		}
	}' "$1"
}

# ---------------------------------------------------------------------------
# P-4 SELF-TEST — run BEFORE any real site, because an extractor that matches
# nothing would otherwise report a clean contract over an unexamined tree. It
# must FIRE on a known-good line and DECLINE a constructed-absent one; both
# directions, or VOID.
# ---------------------------------------------------------------------------
probe="$(mktemp)"; trap 'rm -f "$probe"' EXIT
cat > "$probe" <<'PROBE'
    image: postgres:15-alpine
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
# postgres:99-alpine in a comment with no anchor
PROBE

got="$(extract_major "$probe" 'image: postgres:' | tr '\n' ',')"
[ "$got" = "15," ] || void "P-4 self-test: extractor should yield '15,' on the probe, got '$got' (a matcher that cannot fire cannot certify anything)"

got="$(extract_major "$probe" 'image: mysql:' | tr '\n' ',')"
[ -z "$got" ] || void "P-4 self-test: extractor should yield NOTHING for an absent anchor, got '$got'"

# The JDBC-port rejection is the specific thing that would silently produce a
# wrong answer rather than no answer, so it gets its own arm.
got="$(extract_major "$probe" 'postgres:' | tr '\n' ',')"
case ",$got" in
	*",5432,"*) void "P-4 self-test: the terminator rule failed — a JDBC port was read as a major version" ;;
esac

# ---------------------------------------------------------------------------
# Read the declared sites and collect majors per role.
# ---------------------------------------------------------------------------
declare -a SERVER_SEEN=() TOOLING_SEEN=() SITE_LINES=()
rows=0

while IFS= read -r line; do
	case "$line" in ''|\#*) continue ;; esac
	rows=$((rows + 1))

	role="${line%%|*}";           rest="${line#*|}"
	path="${rest%%|*}";           rest="${rest#*|}"
	anchor="${rest%%|*}";         why="${rest#*|}"

	case "$role" in
		SERVER|TOOLING) ;;
		*) void "unknown ROLE '$role' in $CONF — an unknown directive is a VOID, not a shrug" ;;
	esac

	file="$REPO_ROOT/$path"
	[ -f "$file" ] || void "declared site does not exist: $path (the manifest describes a tree that is not here)"

	majors="$(extract_major "$file" "$anchor")"
	if [ -z "$majors" ]; then
		void "declared site matched NOTHING: $path anchor='$anchor'. A site that cannot be read is not a site that agrees — fix the anchor or remove the row."
	fi

	# Every match inside one site must agree with itself before it can agree with
	# anything else.
	uniq_in_file="$(printf '%s\n' "$majors" | sort -u | tr '\n' ' ')"
	set -- $uniq_in_file
	if [ "$#" -ne 1 ]; then
		fail "$path declares MORE THAN ONE major for anchor '$anchor': $uniq_in_file"
		continue
	fi
	major="$1"

	if [ "$major" -gt 99 ] 2>/dev/null; then
		void "$path anchor='$anchor' yielded implausible major '$major' — the anchor is matching something that is not a version"
	fi

	SITE_LINES+=("  $(printf '%-7s %-38s %-42s -> %s' "$role" "$path" "$anchor" "$major")")
	if [ "$role" = "SERVER" ]; then SERVER_SEEN+=("$major"); else TOOLING_SEEN+=("$major"); fi
done < "$CONF"

# ---------------------------------------------------------------------------
# P-5 COVERAGE — an empty roster is not a clean roster.
# ---------------------------------------------------------------------------
[ "$rows" -gt 0 ]                || void "no site rows in $CONF"
[ "${#SERVER_SEEN[@]}" -gt 0 ]   || void "no SERVER site resolved — nothing to compare tooling against"
[ "${#TOOLING_SEEN[@]}" -gt 0 ]  || void "no TOOLING site resolved — the half this gate exists to check is unexamined"

echo "check-postgres-major-parity"
echo "  config : ${CONF#$REPO_ROOT/}"
printf '%s\n' "${SITE_LINES[@]}"

server_u="$(printf '%s\n' "${SERVER_SEEN[@]}"  | sort -u | tr '\n' ' ')"
tool_u="$(printf   '%s\n' "${TOOLING_SEEN[@]}" | sort -u | tr '\n' ' ')"

set -- $server_u
[ "$#" -eq 1 ] || fail "SERVER majors disagree with each other: $server_u — the servers are not all the same version"
server_major="$1"

set -- $tool_u
[ "$#" -eq 1 ] || fail "TOOLING majors disagree with each other: $tool_u — the backup image, its build tag and the CronJob reference are not all the same version"
tool_major="$1"

if [ "$fail_count" -eq 0 ] && [ "$server_major" != "$tool_major" ]; then
	fail "TOOLING major $tool_major != SERVER major $server_major."
	cat >&2 <<EOF

  pg_dump/pg_restore read their own major and OLDER, never NEWER. Measured
  2026-08-04 against the live 15.17 server:
      postgres:15 pg_restore --list <a postgres:18 dump>
        -> pg_restore: error: unsupported version (1.16) in file header

  With tooling AHEAD of the server, backups keep succeeding and stop being
  restorable by the server's own client. Nothing else in CI observes this, and
  k8s-backup.sh's own \`pg_restore --list\` cannot: it runs inside the image that
  produced the dump, so it agrees by construction.

  This gate does not forbid a newer PostgreSQL. To upgrade, move the halves
  TOGETHER — every SERVER row, every TOOLING row, the image tag, and the CronJob
  reference in one change — and rehearse a restore on the new major before and
  after. Sites are declared in ${CONF#$REPO_ROOT/}.
EOF
fi

if [ "$fail_count" -gt 0 ]; then
	echo "FAILED: $fail_count postgres major-parity violation(s)." >&2
	exit 1
fi

echo "  server : $server_major   tooling: $tool_major   ($rows site(s) checked)"
echo "PASS: backup tooling and server agree on PostgreSQL major $server_major."
echo "      NOTE: this proves the DECLARED majors match. It does not prove a backup"
echo "      restores — nothing in this repo executes a restore drill."
exit 0
