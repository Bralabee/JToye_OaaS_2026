#!/usr/bin/env bash
# check-dependency-horizons.sh — the dependency support-horizon gate.
#
# WHY THIS EXISTS (Phase 27, plan 27-00 Task 5 — finding F-6)
#
#   The brief for this phase named ONE end-of-life dependency. Building this gate found SIX,
#   by fetching endoflife.date for every pin in the repo instead of trusting recall:
#
#     rabbitmq/3.12 (2024-02-21) · prometheus/2.48 (2023-12-28) · grafana/10.2 (2024-07-24)
#     keycloak/24.0 (2024-06-10) · nodejs/20 (2026-04-30) · alpine-linux/3.20 (2026-04-01)
#
#   One believed, six measured. That gap is the argument for the mechanism, and it is the
#   reason this gate re-fetches rather than reading a number someone wrote down.
#
# THE SIX RULES
#
#   H-1  coverage, source -> manifest. Every `image:` in the declared compose/k8s set and
#        every `FROM` in the four Dockerfiles must have a manifest row. A new pin with no
#        row is the headline failure. EMPTY extraction -> VOID, never "clean".
#   H-2  cache freshness. For every row with a non-null eol_slug, re-fetch and compare the
#        catalogue eol against the cached eol_date. Disagreement -> FAIL naming --refresh.
#        This is what stops the manifest rotting into fiction.
#   H-2b catalogue-vs-vendor classification. Two dates, both past, days apart -> NOTE.
#        Catalogue `false` WITH a vendor_eol present -> FAIL: a pin the vendor has dated but
#        the catalogue has not is a missing horizon on something already adopted.
#   H-3  horizon. Past EOL, or inside HORIZON_WARN_DAYS (default 90) -> FAIL, unless an
#        UNEXPIRED exemption covers it. An expired exemption is itself a FAIL.
#   H-4  hygiene. Missing owner, UNASSIGNED without a dated manual_review, duplicate id,
#        empty exemption reason, or an exemption on a row that is NOT past horizon (STALE).
#   H-5  drift, manifest -> source. The direction H-1 structurally cannot see. Every row
#        with a non-empty `sites:` must have its declared `pin` string actually present in
#        each declared file. Absent -> VOID (exit 2), because a manifest describing a tree
#        that does not exist cannot be evaluated: every horizon it reports is about a pin
#        that is not deployed.
#   H-6  UNKNOWN is a state, not a silence. A row whose horizon cannot be established is
#        PRINTED on every run, counted in the summary, and passes ONLY while a dated
#        manual_review is unexpired. Missing or lapsed -> FAIL.
#
# EXIT CODES — uniform across this plan's gates, and PRECEDENCE IS 2 > 1 > 0
#   0 = clean · 1 = contract violation · 2 = VOID (could not evaluate)
#
#   The precedence is stated because H-1 and H-5 fire TOGETHER whenever a pin is bumped in
#   only one place: the new pin has no row (H-1, exit 1) and the old pin is no longer at its
#   site (H-5, exit 2). The process must exit 2. AC-5.14 asserts that rather than assuming it.
#
#   VOID on: missing curl/jq/python3 · unparseable manifest · a slug that 404s · a slug that
#   redirects (the recorded slug must resolve DIRECTLY) · an eol_cycle absent from the
#   fetched cycle list · unparseable JSON · an effective URL whose host is not exactly
#   endoflife.date · ANY discovery rule returning an EMPTY set.
#
# THE UNTRUSTED-INPUT BOUNDARY (threat T-27-06)
#
#   This gate lets a third-party HTTP body decide whether CI passes. So: the host is pinned
#   AFTER following redirects (%{url_effective} is asserted, not assumed), the body is parsed
#   strictly with jq and never eval-ed, and EVERY anomaly is exit 2. A hostile or broken
#   response can VOID this gate; it can never silently green it. An endoflife.date outage
#   therefore fails the job (threat T-27-07, accepted deliberately) — treating an unreachable
#   source as clean is the exact inversion this gate exists to prevent.
#
# WHY eol_slug IS A RECORDED FIELD AND NOT DERIVED FROM THE IMAGE NAME
#
#   Measured 2026-07-27: node -> 301 -> nodejs, alpine -> 301 -> alpine-linux,
#   postgres -> 301 -> postgresql, and minio/ollama/mailhog/alertmanager/both exporters 404.
#   Derived naively, five of the eleven resolvable rows here would either follow a
#   redirect nobody recorded or read a 404 body as "no EOL data" and pass. The slug is
#   recorded, and the gate REJECTS a slug that redirects even though curl -L would resolve
#   it — because the point is that the manifest states the true product, not that the fetch
#   happens to succeed.
#
# KIND EXEMPTIONS, AND EXACTLY HOW WIDE THEY ARE
#
#   first_party  we are upstream; no external horizon exists -> exempt from EOL lookup ONLY.
#   pseudo       `FROM scratch` is the ABSENCE of a base image -> exempt from EOL lookup ONLY.
#   out_of_repo  not deployed from this repo -> exempt from H-1 discovery AND H-5 drift ONLY.
#
#   All three remain subject to H-4 hygiene: pin, owner and (where sites exist) drift. An
#   exemption whose width is never measured is an exemption that quietly covers everything,
#   so AC-5.16 breaks each boundary and confirms the neighbouring rules still fire.
#
# WHEN THIS GOES AMBER AND RED WITH NO CODE CHANGE — SAY IT BEFORE IT HAPPENS
#
#   rabbitmq 4.3 is the only viable upgrade target and its vendor community-support horizon
#   is 2026-11-30. At the default 90-day window this job turns AMBER ~2026-09-01 and RED on
#   2026-12-01 with no commit in between. Intended. Not an outage. Not a broken gate.
#
# USAGE
#   bash scripts/check-dependency-horizons.sh            # check mode
#   bash scripts/check-dependency-horizons.sh --refresh  # rewrite cached eol_date + site
#                                                        # line numbers, print a diff
#
# NOTE ON docs/metrics.json: this script contributes 0. docs-freshness.sh counts Java @Test
# methods, Jest/vitest blocks, Go Test funcs and Playwright tests; it counts no bash and no
# YAML. The expected metrics delta for this file is ZERO.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

MANIFEST="${MANIFEST:-infra/dependency-horizons.yaml}"
HORIZON_WARN_DAYS="${HORIZON_WARN_DAYS:-90}"
# Overridable ONLY so the host assertion itself can be broken and observed (AC-5.8).
HORIZONS_API_BASE="${HORIZONS_API_BASE:-https://endoflife.date/api}"
EXPECTED_HOST="${EXPECTED_HOST:-endoflife.date}"

REFRESH=0
[ "${1:-}" = "--refresh" ] && REFRESH=1

void() { echo "VOID: $*" >&2; exit 2; }

# ---------------------------------------------------------------- declared source surface
# Declared, never inferred, never repo-wide. A repo-wide scan for `image:` would match the
# documentation in this very file, and the planning directory.
COMPOSE_FILES=(
  "docker-compose.full-stack.yml"
  "infra/monitoring/docker-compose.monitoring.yml"
)
K8S_GLOB_DIR="k8s/base"
DOCKERFILES=(
  "core-java/Dockerfile"
  "edge-go/Dockerfile"
  "frontend/Dockerfile"
  "mcp-server/Dockerfile"
)

# ---------------------------------------------------------------- tooling (VOID, exit 2)
# AC-5.11 BREAK-a runs this with a scratch PATH that has bash and coreutils but no jq/curl.
# PATH=/nonexistent is deliberately NOT used: it exits 127 before bash starts, so this branch
# would never execute and the arm would prove nothing.
for t in curl jq python3; do
  command -v "$t" >/dev/null 2>&1 || void "required tool not on PATH: $t — cannot evaluate horizons"
done
[ -r "$MANIFEST" ] || void "manifest not readable: $MANIFEST"
for f in "${COMPOSE_FILES[@]}" "${DOCKERFILES[@]}"; do
  [ -r "$f" ] || void "declared source file not readable: $f"
done
[ -d "$K8S_GLOB_DIR" ] || void "declared k8s directory not readable: $K8S_GLOB_DIR"

# ---------------------------------------------------------------- manifest -> JSON
MAN_JSON=$(python3 -c '
import sys, json
try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML not importable\n"); sys.exit(3)
try:
    d = yaml.safe_load(open(sys.argv[1]))
except Exception as e:
    sys.stderr.write("unparseable: %s\n" % str(e).splitlines()[0]); sys.exit(3)
if not isinstance(d, dict) or not isinstance(d.get("dependencies"), list) or not d["dependencies"]:
    sys.stderr.write("manifest has no non-empty dependencies list\n"); sys.exit(3)
# default=str: an unquoted ISO date parses to datetime.date, which json cannot serialise.
print(json.dumps(d["dependencies"], default=str))
' "$MANIFEST") || void "cannot parse $MANIFEST (see message above)"

# ---------------------------------------------------------------- H-1 discovery
# Expected-0 discipline: `grep -c` prints 0 and EXITS 1, which under `set -e` kills the
# script before it reaches its own VOID handler. Every capture is `|| true`, and emptiness is
# then asserted explicitly, so "the pattern broke" can never read as "nothing to find".
DISCOVERED=""

for f in "${COMPOSE_FILES[@]}"; do
  found=$(command grep -hoE '^[[:space:]]*image:[[:space:]]*[^[:space:]#]+' "$f" 2>/dev/null \
          | command sed -E 's/^[[:space:]]*image:[[:space:]]*//' || true)
  [ -n "$found" ] || void "H-1 discovered ZERO images in $f — the pattern broke; this is not 'no images'"
  DISCOVERED="$DISCOVERED$found"$'\n'
done

k8s_found=$(command grep -rhoE '^[[:space:]]*image:[[:space:]]*[^[:space:]#]+' "$K8S_GLOB_DIR" 2>/dev/null \
            | command sed -E 's/^[[:space:]]*image:[[:space:]]*//' || true)
[ -n "$k8s_found" ] || void "H-1 discovered ZERO images in $K8S_GLOB_DIR — the pattern broke"
DISCOVERED="$DISCOVERED$k8s_found"$'\n'

for f in "${DOCKERFILES[@]}"; do
  found=$(command grep -hoE '^FROM[[:space:]]+[^[:space:]]+' "$f" 2>/dev/null \
          | command sed -E 's/^FROM[[:space:]]+//' || true)
  [ -n "$found" ] || void "H-1 discovered ZERO FROM lines in $f — the pattern broke"
  DISCOVERED="$DISCOVERED$found"$'\n'
done

DISCOVERED=$(printf '%s' "$DISCOVERED" | command grep -vE '^$' | sort -u || true)
[ -n "$DISCOVERED" ] || void "H-1 discovery produced an EMPTY set overall — refusing to report clean"

# ---------------------------------------------------------------- H-5 drift (manifest -> source)
# Runs BEFORE any network call: if the manifest describes a tree that does not exist, every
# horizon it would report is about a pin that is not deployed, so fetching them is pointless.
#
# TWO DEFECTS FOUND BY RUNNING THIS, both of which made it weaker than it looked:
#
#   (a) A file-wide `grep -F` matched the pin inside COMMENTS. `mcp-server/Dockerfile:2` is
#       prose naming `node:20-alpine`, and `edge-go/Dockerfile:29` is the comment
#       "(scratch = minimal image)". So H-5 would have reported the pin present after the
#       real FROM line was deleted — a fail-open, and precisely the class this gate exists to
#       kill. Comment lines are therefore excluded from the search.
#   (b) Rows with several sites in ONE file (node x4, ollama x2) compared every site against
#       the FIRST match, inventing line drift that was not there. The declared line is now
#       checked exactly, and a file-wide search runs only to build a useful message.
DRIFT=""
LINE_DRIFT=""
while IFS='|' read -r rid rpin rsite; do
  [ -n "$rid" ] || continue
  sfile="${rsite%:*}"
  sline="${rsite##*:}"
  if [ ! -r "$sfile" ]; then
    DRIFT="$DRIFT  H-5 $rid: declared site file does not exist: $sfile"$'\n'
    continue
  fi
  # Non-comment lines only, numbered. Fixed-string matching throughout: pins contain regex
  # metacharacters (${MINIO_IMAGE_TAG:-latest}).
  hits=$(command grep -nF -- "$rpin" "$sfile" 2>/dev/null \
         | command awk -F: '{ rest=$0; sub(/^[0-9]+:/,"",rest); if (rest !~ /^[[:space:]]*#/) print $1 }' || true)
  if [ -z "$hits" ]; then
    DRIFT="$DRIFT  H-5 $rid: declared pin '$rpin' NOT FOUND on any non-comment line of $sfile (declared site $rsite)"$'\n'
    continue
  fi
  # Exact-line satisfaction. Only if the declared line does NOT carry the pin is this drift.
  if ! command grep -qxF -- "$sline" <<<"$hits"; then
    LINE_DRIFT="$LINE_DRIFT  NOTE $rid: pin not at $sfile:$sline; found at line(s) $(printf '%s' "$hits" | command tr '\n' ',' | command sed 's/,$//') (run --refresh)"$'\n'
  fi
done <<<"$(python3 -c '
import sys, json
rows = json.loads(sys.argv[1])
for r in rows:
    if r.get("kind") == "out_of_repo":
        continue
    for s in (r.get("sites") or []):
        print("%s|%s|%s" % (r.get("id",""), r.get("pin",""), s))
' "$MAN_JSON")"

# ---------------------------------------------------------------- fetch (untrusted boundary)
CACHE_DIR=$(mktemp -d)
trap 'rm -rf "$CACHE_DIR"' EXIT

SLUGS=$(python3 -c '
import sys, json
rows = json.loads(sys.argv[1])
out = []
for r in rows:
    s = r.get("eol_slug")
    if s and r.get("kind") not in ("first_party", "pseudo"):
        out.append(s)
print("\n".join(sorted(set(out))))
' "$MAN_JSON")
[ -n "$SLUGS" ] || void "no resolvable eol_slug in the manifest — every row cannot be exempt; refusing to report clean"

for slug in $SLUGS; do
  url="$HORIZONS_API_BASE/$slug.json"
  body_file="$CACHE_DIR/$slug.json"
  meta=$(curl -sL --max-redirs 3 --max-time 25 -o "$body_file" \
         -w '%{http_code} %{url_effective}' "$url" 2>/dev/null) \
    || void "H-2 fetch failed for slug '$slug' ($url) — an unreachable source is never 'clean'"
  code="${meta%% *}"
  effective="${meta#* }"
  # Pin the host AFTER redirects. The body decides whether CI passes; it must come from the
  # host we say it comes from.
  ehost=$(command sed -E 's#^[a-zA-Z]+://([^/:]+).*#\1#' <<<"$effective")
  [ "$ehost" = "$EXPECTED_HOST" ] \
    || void "H-2 effective host for slug '$slug' is '$ehost', expected '$EXPECTED_HOST' (url=$effective) — refusing to let an unexpected host decide CI"
  [ "$code" = "200" ] \
    || void "H-2 slug '$slug' returned HTTP $code at $effective — the recorded slug must resolve; do not guess it from the image name"
  [ "$effective" = "$url" ] \
    || void "H-2 slug '$slug' REDIRECTED to $effective — record the post-redirect slug in the manifest instead of relying on the redirect"
  jq -e 'type == "array" and length > 0' "$body_file" >/dev/null 2>&1 \
    || void "H-2 slug '$slug' returned unparseable or empty JSON — untrusted body, refusing to evaluate"
done

# ---------------------------------------------------------------- evaluate H-2..H-6
set +e
EVAL_OUT=$(python3 -c '
import sys, json, os, datetime

rows = json.loads(sys.argv[1])
cache_dir = sys.argv[2]
warn_days = int(sys.argv[3])
today = datetime.date.today()

def parse_date(v):
    if v is None: return None
    s = str(v).strip()
    if s.lower() in ("false", "none", "null", ""): return None
    try:
        return datetime.date.fromisoformat(s)
    except Exception:
        return None

def catalogue(slug, cycle):
    # Returns (found, raw_eol). found is False when the cycle is absent from the catalogue.
    path = os.path.join(cache_dir, slug + ".json")
    data = json.load(open(path))
    for e in data:
        if str(e.get("cycle")) == str(cycle):
            return True, e.get("eol")
    return False, None

fails, notes, unknowns, voids = [], [], [], []
seen_ids = set()
n_exempt_active = 0

for r in rows:
    rid   = r.get("id") or "<no-id>"
    kind  = r.get("kind")
    owner = r.get("owner")
    slug  = r.get("eol_slug")
    mrev  = r.get("manual_review")
    exm   = r.get("exemption")

    # ---------------- H-4 hygiene (applies to EVERY row, no kind is exempt)
    if rid in seen_ids:
        fails.append("H-4 duplicate row id: %s" % rid)
    seen_ids.add(rid)
    if not r.get("pin"):
        fails.append("H-4 %s: missing mandatory field `pin`" % rid)
    if not owner:
        fails.append("H-4 %s: missing mandatory field `owner`" % rid)
    elif owner == "UNASSIGNED" and not mrev:
        fails.append("H-4 %s: owner is UNASSIGNED with no manual_review — an unowned row must at least be a dated one" % rid)

    mrev_ok = False
    if mrev:
        mexp = parse_date(mrev.get("expires"))
        if mexp is None:
            fails.append("H-4 %s: manual_review.expires is missing or not a date" % rid)
        elif mexp < today:
            fails.append("H-6 %s: manual_review LAPSED on %s (%d days ago) — an expired review is not a review" % (rid, mexp.isoformat(), (today - mexp).days))
        else:
            mrev_ok = True

    exm_ok = False
    if exm:
        if not (exm.get("reason") or "").strip():
            fails.append("H-4 %s: exemption has an empty reason" % rid)
        if not (exm.get("tracked_by") or "").strip():
            fails.append("H-4 %s: exemption has no tracked_by" % rid)
        eexp = parse_date(exm.get("expires"))
        if eexp is None:
            fails.append("H-4 %s: exemption.expires is missing or not a date" % rid)
        elif eexp < today:
            fails.append("H-3 %s: exemption EXPIRED on %s (%d days ago) — a deferral cannot quietly become permanent" % (rid, eexp.isoformat(), (today - eexp).days))
        else:
            exm_ok = True
            n_exempt_active += 1

    # ---------------- kind-scoped EOL-lookup exemption (and nothing wider)
    if kind in ("first_party", "pseudo"):
        if exm:
            fails.append("H-4 %s: exemption on a kind=%s row, which has no external horizon to exempt (STALE)" % (rid, kind))
        continue

    # ---------------- H-6 UNKNOWN
    if not slug or str(r.get("pin")) == "unknown":
        reason = (r.get("note") or "no horizon source recorded").strip().split("\n")[0]
        line = "UNKNOWN %s: %s owner=%s review-expires=%s" % (
            rid, reason[:90], owner,
            (mrev.get("expires") if mrev else "NONE"))
        unknowns.append(line)
        if not mrev:
            fails.append("H-6 %s: horizon is UNKNOWN and no manual_review claims it — an unknown nobody has agreed to re-check is a silence, not a state" % rid)
        # A lapsed review already produced its own H-6 fail above.
        continue

    # ---------------- H-2 cache freshness
    cycle = r.get("eol_cycle")
    if not cycle:
        voids.append("H-2 %s: eol_slug is set but eol_cycle is missing" % rid)
        continue
    found, raw = catalogue(slug, cycle)
    if not found:
        voids.append("H-2 %s: cycle %s not present in the %s catalogue — resolve the floating tag and record it, do not guess" % (rid, cycle, slug))
        continue
    cached = str(r.get("eol_date"))
    fetched = "false" if raw in (False, "false") else str(raw)
    if cached != fetched:
        fails.append("H-2 %s: cached eol_date '%s' disagrees with fetched '%s' for %s/%s — run `bash scripts/check-dependency-horizons.sh --refresh`" % (rid, cached, fetched, slug, cycle))
        continue

    # ---------------- H-2b catalogue vs vendor
    cat_d = parse_date(fetched)
    ven_d = parse_date(r.get("vendor_eol"))
    src   = r.get("eol_source")
    effective_d, effective_label = cat_d, "catalogue"

    if src == "catalogue":
        effective_d, effective_label = cat_d, "catalogue (eol_source override)"
    elif src == "vendor":
        if ven_d is None:
            fails.append("H-2b %s: eol_source is `vendor` but no vendor_eol date is recorded" % rid)
            continue
        effective_d, effective_label = ven_d, "vendor (eol_source override)"
    else:
        if cat_d is None and ven_d is not None:
            fails.append("H-2b %s: catalogue reports no horizon (eol: false) for %s/%s but the VENDOR dates it %s — a missing horizon on an adopted pin. Source: %s" % (rid, slug, cycle, ven_d.isoformat(), r.get("vendor_source") or "unrecorded"))
            effective_d, effective_label = ven_d, "vendor"
        elif cat_d is not None and ven_d is not None and cat_d != ven_d:
            delta = abs((ven_d - cat_d).days)
            notes.append("H-2b %s: catalogue %s vs vendor %s (%d days apart) — evaluating against the EARLIER of the two" % (rid, cat_d.isoformat(), ven_d.isoformat(), delta))
            effective_d = min(cat_d, ven_d)
            effective_label = "earlier of catalogue/vendor"

    # ---------------- H-3 horizon
    if effective_d is None:
        notes.append("H-3 %s: %s/%s has no declared horizon (eol: false) — nothing to breach today" % (rid, slug, cycle))
        if exm:
            fails.append("H-4 %s: STALE exemption — the row is not past horizon and not inside the %d-day window, so there is nothing to exempt" % (rid, warn_days))
        continue

    days = (effective_d - today).days
    breached = days < 0
    approaching = (0 <= days <= warn_days)

    if not breached and not approaching:
        if exm:
            fails.append("H-4 %s: STALE exemption — %s horizon %s is %d days away, outside the %d-day window, so there is nothing to exempt" % (rid, effective_label, effective_d.isoformat(), days, warn_days))
        continue

    state = "PAST EOL" if breached else "approaching"
    detail = "%s: %s/%s %s %s (%s, %d days)" % (
        rid, slug, cycle, state, effective_d.isoformat(), effective_label, abs(days))
    if exm_ok:
        notes.append("H-3 EXEMPT %s until %s [%s]" % (detail, exm.get("expires"), exm.get("tracked_by")))
    elif mrev_ok:
        notes.append("H-3 REVIEWED %s until %s" % (detail, mrev.get("expires")))
    else:
        fails.append("H-3 %s" % detail)

print(json.dumps({
    "fails": fails, "notes": notes, "unknowns": unknowns, "voids": voids,
    "n_rows": len(rows), "n_exempt": n_exempt_active,
}))
' "$MAN_JSON" "$CACHE_DIR" "$HORIZON_WARN_DAYS")
EVAL_RC=$?
set -e
[ "$EVAL_RC" -eq 0 ] || void "rule evaluation failed (python3 exit $EVAL_RC)"

# ---------------------------------------------------------------- H-1 coverage report
COVERAGE_FAILS=$(python3 -c '
import sys, json
rows = json.loads(sys.argv[1])
discovered = [d for d in sys.argv[2].split("\n") if d.strip()]
pins = set()
for r in rows:
    if r.get("kind") == "out_of_repo":
        continue
    p = r.get("pin")
    if p: pins.add(str(p))
for d in sorted(set(discovered)):
    if d not in pins:
        print("H-1 %s is pinned in the declared source surface but has NO horizon row" % d)
' "$MAN_JSON" "$DISCOVERED")

# ---------------------------------------------------------------- --refresh
if [ "$REFRESH" -eq 1 ]; then
  python3 -c '
import sys, json, os, re

manifest, man_json, cache_dir = sys.argv[1], sys.argv[2], sys.argv[3]
rows = json.loads(man_json)

def catalogue(slug, cycle):
    path = os.path.join(cache_dir, slug + ".json")
    if not os.path.exists(path): return None
    for e in json.load(open(path)):
        if str(e.get("cycle")) == str(cycle):
            raw = e.get("eol")
            return "false" if raw in (False, "false") else str(raw)
    return None

want_eol, want_sites = {}, {}
for r in rows:
    rid = r.get("id")
    slug, cycle = r.get("eol_slug"), r.get("eol_cycle")
    if slug and cycle and r.get("kind") not in ("first_party", "pseudo"):
        v = catalogue(slug, cycle)
        if v is not None: want_eol[rid] = v
    # Same two defects as H-5, fixed the same way: skip COMMENT lines (a pin surviving only
    # in prose is not a pin), and when a row has several sites in one file, hand out the
    # matches in order instead of giving every site the first one.
    fixed, used = [], {}
    for s in (r.get("sites") or []):
        f, _, ln = s.rpartition(":")
        pin = str(r.get("pin"))
        if f and os.path.exists(f):
            hits = [i for i, line in enumerate(open(f, errors="replace"), 1)
                    if pin in line and not line.lstrip().startswith("#")]
            k = used.get(f, 0)
            if k < len(hits):
                fixed.append("%s:%d" % (f, hits[k])); used[f] = k + 1
            else:
                fixed.append(s)
        else:
            fixed.append(s)
    if fixed: want_sites[rid] = fixed

lines = open(manifest).readlines()
cur, out, changed = None, [], 0
i = 0
while i < len(lines):
    line = lines[i]
    m = re.match(r"^  - id:\s*(\S+)\s*$", line)
    if m: cur = m.group(1)
    m2 = re.match(r"^(\s*eol_date:\s*)(.*)$", line)
    if m2 and cur in want_eol:
        newv = want_eol[cur]
        quoted = chr(34) + newv + chr(34)
        if m2.group(2).strip().strip(chr(34)) != newv:
            line = m2.group(1) + quoted + "\n"; changed += 1
    m3 = re.match(r"^(\s*)sites:\s*\[(.*)\]\s*$", line)
    if m3 and cur in want_sites and len(want_sites[cur]) == 1:
        newv = chr(34) + want_sites[cur][0] + chr(34)
        if m3.group(2).strip() != newv:
            line = m3.group(1) + "sites: [" + newv + "]\n"; changed += 1
    out.append(line)
    i += 1

if changed:
    open(manifest, "w").writelines(out)
print("refresh: %d field(s) rewritten in %s" % (changed, manifest))
' "$MANIFEST" "$MAN_JSON" "$CACHE_DIR"
  echo "--- git diff $MANIFEST ---"
  git --no-pager diff -- "$MANIFEST" || true
  exit 0
fi

# ---------------------------------------------------------------- report
FAILS=$(jq -r '.fails[]' <<<"$EVAL_OUT")
NOTES=$(jq -r '.notes[]' <<<"$EVAL_OUT")
UNKNOWNS=$(jq -r '.unknowns[]' <<<"$EVAL_OUT")
VOIDS=$(jq -r '.voids[]' <<<"$EVAL_OUT")
N_ROWS=$(jq -r '.n_rows' <<<"$EVAL_OUT")
N_EXEMPT=$(jq -r '.n_exempt' <<<"$EVAL_OUT")

[ -n "$NOTES" ]    && printf '%s\n' "$NOTES"
[ -n "$LINE_DRIFT" ] && printf '%s' "$LINE_DRIFT"
# H-6: UNKNOWN is printed on EVERY run, pass or fail. An unknown that is only visible when
# it fails is an unknown that disappears the moment someone dates it.
[ -n "$UNKNOWNS" ] && printf '%s\n' "$UNKNOWNS"
# Prefix EVERY line, not just the first. `printf 'FAIL: %s\n' "$multiline"` labels only the
# first line and leaves the rest looking like notes.
[ -n "$COVERAGE_FAILS" ] && printf '%s\n' "$COVERAGE_FAILS" | command sed 's/^/FAIL: /'
[ -n "$FAILS" ]    && printf '%s\n' "$FAILS" | command sed 's/^/FAIL: /'

n_void=0;  [ -n "$VOIDS" ]    && n_void=$(printf '%s\n' "$VOIDS" | command grep -c . || true)
n_drift=0; [ -n "$DRIFT" ]    && n_drift=$(printf '%s' "$DRIFT" | command grep -c . || true)
n_cov=0;   [ -n "$COVERAGE_FAILS" ] && n_cov=$(printf '%s\n' "$COVERAGE_FAILS" | command grep -c . || true)
n_fail=0;  [ -n "$FAILS" ]    && n_fail=$(printf '%s\n' "$FAILS" | command grep -c . || true)
n_unk=0;   [ -n "$UNKNOWNS" ] && n_unk=$(printf '%s\n' "$UNKNOWNS" | command grep -c . || true)

cat <<SUMMARY
dependency-horizons summary
  manifest    rows=$N_ROWS  discovered-pins=$(printf '%s\n' "$DISCOVERED" | command grep -c . || true)
  H-1 coverage   missing-row=$n_cov
  H-5 drift      pin-not-at-site=$n_drift          (VOID class)
  H-2/H-3        violations=$n_fail  active-exemptions=$N_EXEMPT
  H-6 UNKNOWN    rows=$n_unk  (printed above; each passes only while its review is unexpired)
  warn window    HORIZON_WARN_DAYS=$HORIZON_WARN_DAYS
SUMMARY

# ---------------------------------------------------------------- exit, precedence 2 > 1 > 0
if [ "$n_drift" -gt 0 ] || [ "$n_void" -gt 0 ]; then
  [ -n "$DRIFT" ] && printf 'VOID (H-5 drift):\n%s' "$DRIFT" >&2
  [ -n "$VOIDS" ] && printf 'VOID:\n%s\n' "$VOIDS" >&2
  echo "VOIDED: the manifest describes a tree that does not exist, or a horizon could not be resolved. Exit 2 takes precedence over the $((n_cov + n_fail)) contract violation(s) also reported." >&2
  exit 2
fi

if [ "$n_cov" -gt 0 ] || [ "$n_fail" -gt 0 ]; then
  echo "FAILED: $((n_cov + n_fail)) horizon contract violation(s)."
  exit 1
fi

echo "OK: every pinned artifact carries a resolved, in-window or explicitly-deferred horizon."
exit 0
