#!/usr/bin/env bash
#
# Gate: no Flyway migration may create a PostgreSQL extension.
#
# This is not a style preference — the statement CANNOT EXECUTE in any environment this project
# has. Flyway runs as the spring.datasource.username role (DB_USER, default jtoye_app), and that
# role is deliberately unprivileged: rolsuper = f, rolbypassrls = f, and no CREATE on the
# database. Measured on the live stack inside a rolled-back transaction:
#
#     cube           1.5   trusted = t   superuser = t
#     earthdistance  1.1   trusted = f   superuser = t
#     PostGIS        absent entirely (pg_available_extensions returns 0 rows)
#
#     SET ROLE jtoye_app; CREATE EXTENSION IF NOT EXISTS cube;
#     ERROR:  permission denied to create extension "cube"
#
# So even the TRUSTED extension fails. The "fix" that would make it work — granting jtoye_app
# CREATE ON DATABASE — is a privilege escalation on the exact role the entire RLS wall is built
# around, and is explicitly rejected.
#
# The failure mode this prevents is total and late: a migration containing the statement passes
# code review, passes any test that does not run migrations as the real role, and then aborts
# application startup in every environment at once. So the invariant is enforced across the WHOLE
# migration directory, not just the migration that motivated it (V61, plan 33-02).
#
# Exit codes:
#   0  no migration creates an extension
#   1  at least one does — named, with its line
#   2  VOID — the migration directory is missing, or the scan found NO FILES to check
#
# 2 is load-bearing. A zero-file scan reporting "clean" is the vacuous shape this repo has been
# bitten by repeatedly: "I found nothing" must never render as "there is nothing".
#
# TWO HAZARDS THIS SCRIPT HAS TO DODGE, both recorded failure modes here:
#
#   1. A gate that forbids a string must NAME that string, so it can fire on its own definition.
#      The scan is therefore scoped to the migration directory ONLY, by absolute path, and this
#      script does not live there. Verified by the fact that this file contains the phrase
#      several times and the gate is green.
#
#   2. `cmd | grep -q X` under `set -o pipefail` INVERTS on match: grep exits at the first hit,
#      the writer takes SIGPIPE, and pipefail promotes it to 141 — so a guard written that way
#      fails OPEN on the case it exists to catch. Here-strings only, and counts captured with
#      `|| true` because `grep -c` exits 1 on a zero count, i.e. on the DESIRED state.

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

# Overridable so the VOID direction is testable against an empty directory without inventing a
# second code path. Defaults to the real migration directory.
MIGRATION_DIR="${MIGRATION_DIR:-$REPO_ROOT/core-java/src/main/resources/db/migration}"

fail() { echo "FAIL: $*" >&2; exit 1; }
void() { echo "VOID: $*" >&2; exit 2; }

echo "No-CREATE-EXTENSION gate"
echo "  migrations : ${MIGRATION_DIR#"$REPO_ROOT"/}"

[ -d "$MIGRATION_DIR" ] || void "migration directory not found: $MIGRATION_DIR"

# Enumerate first, and refuse to report on an empty set.
mapfile -t MIGRATIONS < <(find "$MIGRATION_DIR" -maxdepth 1 -type f -name '*.sql' | sort)
COUNT="${#MIGRATIONS[@]}"
echo "  scanned    : ${COUNT} migration file(s)"
[ "$COUNT" -gt 0 ] || void "no .sql files found under $MIGRATION_DIR — refusing to report clean over an empty scan"

# ---- The one exemption, by addition and with a justification -----------------------------------
#
# THE PLAN FOR THIS GATE SAID "exit non-zero on ANY hit" AND ASSERTED "measured 0 hits today".
# Both are wrong on the real tree, and finding out which way they were wrong changed the gate:
#
#     core-java/src/main/resources/db/migration/V1__base_schema.sql:6
#     CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
#
# That line has been in the base schema since the beginning and every environment applies it
# successfully. A gate that failed on it would be permanently red — and this repo already records
# that a permanently-red required job is worse than no job, because it teaches people to add
# `|| true`. Deleting the line to make the gate green would be worse still: a fresh database would
# lose uuid-ossp and V1 itself would fail.
#
# So WHY does it work, given the Flyway role cannot create extensions? Measured on the live stack,
# 2026-08-08, in a rolled-back transaction:
#
#     BEGIN; SET ROLE jtoye_app;
#     SELECT rolsuper FROM pg_roles WHERE rolname = current_user;   -->  f
#     CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
#     NOTICE:  extension "uuid-ossp" already exists, skipping
#     CREATE EXTENSION                                              -->  SUCCEEDED as jtoye_app
#
# PostgreSQL checks the privilege only when it actually has to CREATE. When the extension is
# already present, IF NOT EXISTS emits a notice and skips, and an unprivileged role sails through.
# uuid-ossp is pre-provisioned in this stack; `cube` and `earthdistance` are NOT, which is why the
# identical statement for them fails with "permission denied to create extension".
#
# The real invariant is therefore NOT "no migration mentions this statement". It is:
#
#     no migration may create an extension that is not ALREADY PRESENT in every environment
#
# which is unknowable from a text scan. So the gate enforces the conservative, checkable form —
# no new extension-creating statement at all — with a single exemption carried by NAME and by
# FILE, in the same shape as RlsContractTest.EXEMPT_TABLES: exempt by ADDITION, never by
# weakening the sweep. Adding an entry here should require exactly the evidence above.
EXEMPT=(
    # <migration-basename>|<extension>|<justification>
    "V1__base_schema.sql|uuid-ossp|Present since the base schema and applied successfully in every environment. uuid-ossp ships with postgres-contrib and is pre-provisioned in this stack, so IF NOT EXISTS is a no-op that an unprivileged role may run — verified by measurement 2026-08-08 as jtoye_app with rolsuper=f. Removing the line would break a FRESH database, which is why this is exempted rather than fixed."
)

is_exempt() {
    local file_base="$1" line_text="$2" entry ex_file ex_name
    for entry in "${EXEMPT[@]}"; do
        IFS='|' read -r ex_file ex_name _ <<< "$entry"
        if [ "$file_base" = "$ex_file" ] && grep -qF "$ex_name" <<< "$line_text"; then
            return 0
        fi
    done
    return 1
}

# Case-insensitive, tolerant of any internal whitespace (CREATE  EXTENSION, CREATE\tEXTENSION).
# Deliberately does NOT skip comment lines. A commented-out extension statement is still worth a
# human look, and a comment-stripping parser is exactly the kind of clever that goes quietly
# wrong. The consequence is that a migration must discuss this constraint WITHOUT writing the
# statement — V61__postcode_centroid.sql does, and is the pattern to follow.
PATTERN='CREATE[[:space:]]+EXTENSION'

VIOLATIONS=0
EXEMPTED=0
for migration in "${MIGRATIONS[@]}"; do
    hits="$(grep -inE "$PATTERN" "$migration" || true)"
    [ -n "$hits" ] || continue
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        if is_exempt "$(basename "$migration")" "$line"; then
            EXEMPTED=$((EXEMPTED + 1))
            continue
        fi
        echo "  ${migration#"$REPO_ROOT"/}:${line}" >&2
        VIOLATIONS=$((VIOLATIONS + 1))
    done <<< "$hits"
done

# An exemption that stops matching is a silent hole: the line it covers may have been edited into
# a NEW violation, or deleted. Either way the table is now lying, so say so rather than pass.
if [ "$EXEMPTED" -ne "${#EXEMPT[@]}" ]; then
    void "expected ${#EXEMPT[@]} exempted occurrence(s) but matched ${EXEMPTED} — the exemption table no longer describes the tree, so this scan cannot be trusted. Re-read it against the migrations."
fi

if [ "$VIOLATIONS" -gt 0 ]; then
    fail "${VIOLATIONS} migration line(s) create a PostgreSQL extension. The Flyway role (jtoye_app) cannot execute this — it will abort startup in EVERY environment, not fail the migration quietly. Granting that role CREATE ON DATABASE is not the remedy: it is a privilege escalation on the role the RLS wall depends on. Use plain SQL (see V61__postcode_centroid.sql and uk.jtoye.core.geo.GeoBounds for the worked example)."
fi

echo "PASS: none of the ${COUNT} migration(s) create a PostgreSQL extension (${EXEMPTED} exempted occurrence(s) matched)."
