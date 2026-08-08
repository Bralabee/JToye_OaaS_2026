#!/usr/bin/env bash
# check-doc-citations.sh — every `file:line` citation in a LIVE doc must resolve
# to a line that actually says what the doc claims.
#
# WHY THIS EXISTS
#
#   Measured on this repo 2026-07-29, before any fix: of the 11 dependency
#   citations in .planning/codebase/STACK.md, exactly ONE (Bucket4j) was still
#   correct. The rest drifted as core-java/build.gradle.kts grew — nothing ever
#   checked them, so nothing ever complained:
#
#     Spring AMQP        cited :32  -> line 32 is spring-boot-starter-WEB
#     Stripe Java SDK    cited :60  -> line 60 is the AWS SDK bom
#     AWS SDK v2         cited :45-46 -> a blank line and a `// RabbitMQ` comment
#     PostgreSQL JDBC    cited :70  -> a WebP ImageIO comment
#
#   A citation that points at the wrong line is worse than no citation: a reader
#   follows it, sees something unrelated, and concludes the DOC is right and their
#   understanding is wrong. `scripts/check-doc-versions.sh` cannot catch this —
#   it compares version STRINGS and has no notion of where a claim points.
#
# WHAT IS SCANNED, AND WHY IT IS NOT EVERY MARKDOWN FILE
#
#   Only docs that make LIVE claims about the current tree. Historical records
#   under .planning/phases/** legitimately carry citations that were correct when
#   written — 24-RESEARCH.md cites `build.gradle.kts:54-55` for scrimage, which
#   has since moved. Validating those would force a choice between a red gate and
#   REWRITING THE RECORD, and rewriting the record is the worse outcome. The
#   default set is overridable with CITATION_DOCS (newline- or space-separated).
#
# THE ASSERTION
#
#   C-1  the cited file exists
#   C-2  every cited line number is within that file
#   C-3  at least one STRONG token from the claim appears on a cited line. Strong
#        = a backticked span, or an identifier containing - . : _ (coordinates,
#        artifact names, versions). Ordinary words are NOT used: an earlier
#        version fell back to them and its own break arm proved the fallback
#        could launder a wrong citation, because "spring" matches
#        `spring-boot-starter-web` as happily as `-amqp`. A claim carrying no
#        strong token is UNCHECKABLE — printed, never counted as a pass.
#
# EXIT CODES  0 = clean · 1 = violation · 2 = VOID (could not evaluate)
#   VOID on: missing tooling · an unreadable doc · ZERO docs discovered · ZERO
#   citations discovered · citations found but NONE verifiable. "Found nothing"
#   is never "clean" — a gate that silently scans an empty set is the failure
#   mode this repo keeps rediscovering.
#   NOTE the precedence: violations outrank the not-verifiable VOID, because
#   finding violations IS a successful evaluation. Getting that order wrong made
#   an all-wrong document exit 2 instead of 1 — measured, not theorised.
#
# USAGE
#   bash scripts/check-doc-citations.sh
#   CITATION_DOCS="a.md b.md" bash scripts/check-doc-citations.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || { echo "VOID: cannot cd to repo root" >&2; exit 2; }

void()      { echo "VOID: $*" >&2; exit 2; }
violation() { echo "FAIL: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }

# ---------------------------------------------------------------- tooling (VOID)
for t in grep sed awk; do
  command -v "$t" >/dev/null 2>&1 || void "$t not found — a check that cannot run is not a check that passed"
done

# ---------------------------------------------------------------- the doc set
# Live-claim documents only. Overridable; see header.
DEFAULT_DOCS="CLAUDE.md
AGENTS.md
.planning/codebase/STACK.md
.planning/codebase/ARCHITECTURE.md
.planning/codebase/INTEGRATIONS.md
k8s/DEPLOYMENT.md
k8s/LOCAL.md
docs/ops/terminal-states.yaml"

DOCS_RAW="${CITATION_DOCS:-$DEFAULT_DOCS}"

DOCS=()
while IFS= read -r d; do
  [ -z "$d" ] && continue
  [ -f "$d" ] && DOCS+=("$d")
done <<< "$(printf '%s\n' $DOCS_RAW)"

[ "${#DOCS[@]}" -gt 0 ] || void "ZERO documents discovered from the configured set — refusing to report clean over an empty scan"

VIOLATIONS=0
CITES=0
STRONG_OK=0
UNCHECKABLE=0

# Extract distinctive tokens from a claim line.
#   strong: backticked spans, and words containing - . : _ (identifiers/versions)
#   weak:   remaining alphabetic words of length >= 4
tokens_for() {
  local line="$1" mode="$2"
  # drop the citations themselves so a path never counts as its own evidence
  local stripped
  stripped="$(printf '%s' "$line" | sed -E 's/`[^`]*:[0-9]+([,-][0-9]+)*`//g')"
  if [ "$mode" = strong ]; then
    # BACKTICKED IDENTIFIERS WIN OUTRIGHT when the claim has any. A backticked span
    # is the author explicitly naming the thing; prose around it is not. Deriving
    # tokens from prose as well produced a FALSE VIOLATION on a CORRECT citation:
    # "Spring WebFlux - Non-blocking WebClient for Claude/Ollama AI calls" yielded
    # `Non-blocking` and `Claude/Ollama`, neither of which appears on the
    # spring-boot-starter-webflux line it correctly cited. Prose is only consulted
    # when the claim backticks nothing at all.
    local ticked
    ticked="$(printf '%s' "$stripped" | grep -oE '`[^`]+`' 2>/dev/null | tr -d '`')"
    if [ -n "$ticked" ]; then
      {
        printf '%s\n' "$ticked"
        # …and its whitespace-separated parts. A span like `POST /api/v1/webhooks/whatsapp`
        # never appears verbatim in source (there it reads r.POST("/api/v1/…")), so
        # matching only the whole span was itself a false-violation source.
        # split on '=' too: `DOCKER_API_VERSION=1.45` is a standard doc idiom whose
        # KEY is the identifier, while source reads environment("DOCKER_API_VERSION", "1.45").
        printf '%s\n' "$ticked" | tr ' =' '\n\n'
      } | sed -E 's/[[:punct:]]+$//' | awk 'length($0) >= 4' | sort -u
    else
      printf '%s' "$stripped" | grep -oE '[A-Za-z0-9_]+[-.:/][A-Za-z0-9_.:/-]+' 2>/dev/null \
        | sed -E 's/[[:punct:]]+$//' | awk 'length($0) >= 4' | sort -u
    fi
  else
    printf '%s' "$stripped" | grep -oE '[A-Za-z][A-Za-z0-9]{3,}' 2>/dev/null \
      | awk 'length($0) >= 4' | sort -u
  fi
}

# Expand a citation spec (N | N-M | N,M) into individual line numbers.
#
# NO PIPELINES HERE, DELIBERATELY. The first version of this function used
#   printf '%s' "$part" | grep -qE '^[0-9]+$'
# and it was WRONG in a way that made the whole gate report 44 false violations
# on its first run: `grep -q` exits the instant it matches, `printf` then takes
# SIGPIPE -> 141, and under `set -o pipefail` the pipeline's status is 141, so a
# MATCH is read as a FAILURE. Every well-formed spec was rejected as
# "unparseable". Bash's own =~ has no pipeline and cannot invert.
expand_spec() {
  local spec="$1" part a b
  local IFS=,
  for part in $spec; do
    if [[ "$part" =~ ^([0-9]+)-([0-9]+)$ ]]; then
      a="${BASH_REMATCH[1]}"; b="${BASH_REMATCH[2]}"
      if [ "$a" -le "$b" ]; then seq "$a" "$b"; fi
    elif [[ "$part" =~ ^[0-9]+$ ]]; then
      printf '%s\n' "$part"
    fi
  done
}

# ---------------------------------------------------------------------------
# CITATION EXTRACTION — two dialects, one record stream.
#
# Emits TSV: <doc-line-no>\t<path:spec>\t<declared subject or empty>\t<claim text>
#
# MARKDOWN dialect: a backticked `path.ext:N` span. The claim is the doc line
# the span sits on, which is where the prose describing it lives.
#
# YAML dialect (issue #346): `locator: "path:N"` and `sites: ["path:N", ...]`.
# These carried NO coverage at all until 2026-07-30, and the reason the obvious
# fix does not work is worth stating: simply adding the register to DEFAULT_DOCS
# leaves the markdown pattern matching nothing, so the gate reports
# `citations=0` for it and PASSES — an already-0 grep, coverage in appearance
# only. Measured before this parser existed:
#     CITATION_DOCS="docs/ops/terminal-states.yaml" bash scripts/check-doc-citations.sh
#     docs/ops/terminal-states.yaml   citations=0
#
# THE CLAIM IS THE WHOLE ROW, NOT THE `locator:` LINE. This is the part that
# makes the check worth having. `locator: "…/alerts.yml:127"` contains no
# assertion to test — the row's OTHER fields are what name the thing the line is
# supposed to be. TS-14's row says `HighMemoryUsage`, so a locator pointing at
# `- alert: TooManyDatabaseConnections` matches none of its tokens and fails.
# C-1/C-2 alone would have caught neither of the two wrong locators found in
# #345: both cited a line that EXISTS and is IN RANGE, just the wrong one.
#
# The citation-bearing keys are excluded from the claim text, or a locator would
# count as its own evidence.
CITE_KEYS_EXCLUDED_FROM_CLAIM="locator sites runbook related covers"

yaml_citations() {
  local doc="$1"
  command -v python3 >/dev/null 2>&1 \
    || void "python3 not on PATH — cannot parse the YAML citations in $doc"
  python3 -c '
import sys, re, yaml
path = sys.argv[1]
skip = set(sys.argv[2].split())
try:
    text = open(path).read()
    doc  = yaml.safe_load(text)
except ImportError:
    sys.stderr.write("PyYAML not importable\n"); sys.exit(3)
except Exception as e:
    sys.stderr.write("unparseable: %s\n" % str(e).splitlines()[0]); sys.exit(3)
lines = text.splitlines()

# the first top-level key whose value is a non-empty list of mappings
rows = None
if isinstance(doc, dict):
    for v in doc.values():
        if isinstance(v, list) and v and isinstance(v[0], dict):
            rows = v; break
if rows is None:
    sys.exit(0)

CITE = re.compile(r"^([A-Za-z0-9_./-]+\.[A-Za-z0-9]+):([0-9]+(?:[,-][0-9]+)*)$")

def scalars(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if k in skip: continue
            for s in scalars(v): yield s
    elif isinstance(node, list):
        for v in node:
            for s in scalars(v): yield s
    elif node is not None:
        yield str(node)

for r in rows:
    rid = str(r.get("id", ""))
    ln = 1
    if rid:
        pat = re.compile(r"^\s*-\s+id:\s*[\"\x27]?" + re.escape(rid) + r"[\"\x27]?\s*$")
        for i, L in enumerate(lines, 1):
            if pat.match(L): ln = i; break
    claim = " ".join(scalars(r))
    claim = re.sub(r"\s+", " ", claim)[:4000]
    # The DECLARED subject. Tabs would corrupt the record; there is no legitimate
    # tab in an identifier, so collapse rather than silently split the field.
    subject = str(r.get("subject", "") or "").replace("\t", " ").strip()
    cites = []
    for key in skip:
        v = r.get(key)
        if isinstance(v, str): cites.append(v)
        elif isinstance(v, list): cites += [x for x in v if isinstance(x, str)]
    for c in cites:
        if CITE.match(c.strip()):
            print("%d\x1f%s\x1f%s\x1f%s" % (ln, c.strip(), subject, claim))
' "$doc" "$CITE_KEYS_EXCLUDED_FROM_CLAIM" || void "cannot extract YAML citations from $doc (see message above)"
}

md_citations() {
  local doc="$1" hit n line c
  while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    n="${hit%%:*}"; line="${hit#*:}"
    while IFS= read -r c; do
      [ -z "$c" ] && continue
      printf '%s\x1f%s\x1f\x1f%s\n' "$n" "$c" "$line"
    done <<< "$(printf '%s' "$line" | grep -oE '`[A-Za-z0-9_./-]+\.[A-Za-z0-9]+:[0-9]+([,-][0-9]+)*`' 2>/dev/null | tr -d '`')"
  done <<< "$(grep -nE '`[A-Za-z0-9_./-]+\.[A-Za-z0-9]+:[0-9]+([,-][0-9]+)*`' "$doc" 2>/dev/null || true)"
}

citations_in() {
  case "$1" in
    *.yaml|*.yml) yaml_citations "$1" ;;
    *)            md_citations  "$1" ;;
  esac
}

echo "check-doc-citations  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  docs: ${#DOCS[@]}"

for doc in "${DOCS[@]}"; do
  [ -r "$doc" ] || void "$doc exists but is not readable"
  doc_cites=0
  case "$doc" in *.yaml|*.yml) dialect=yaml ;; *) dialect=md ;; esac

  # every `path:spec` citation, with the doc line it sits on and its claim text
  while IFS=$'\x1f' read -r docline_no cite subject docline; do
      [ -z "$cite" ] && continue
      path="${cite%:*}"
      spec="${cite##*:}"

      # resolve relative to repo root, then to the doc's own directory
      target=""
      if [ -f "$path" ]; then target="$path"
      elif [ -f "$(dirname "$doc")/$path" ]; then target="$(dirname "$doc")/$path"
      fi

      CITES=$((CITES + 1)); doc_cites=$((doc_cites + 1))

      # C-1 file exists
      if [ -z "$target" ]; then
        violation "C-1 $doc:$docline_no cites '$path' which does not exist"
        continue
      fi

      total=$(grep -c '' "$target" 2>/dev/null || echo 0)
      lines="$(expand_spec "$spec")"
      [ -n "$lines" ] || { violation "C-2 $doc:$docline_no unparseable citation spec ':$spec'"; continue; }

      # C-2 every cited line is inside the file
      oob=0
      while IFS= read -r n; do
        [ -z "$n" ] && continue
        [ "$n" -ge 1 ] 2>/dev/null && [ "$n" -le "$total" ] 2>/dev/null || oob=1
      done <<< "$lines"
      if [ "$oob" = 1 ]; then
        violation "C-2 $doc:$docline_no cites $path:$spec but that file has only $total line(s)"
        continue
      fi

      # gather the cited content
      cited=""
      while IFS= read -r n; do
        [ -z "$n" ] && continue
        cited="$cited $(sed -n "${n}p" "$target")"
      done <<< "$lines"
      cited_lc="$(printf '%s' "$cited" | tr '[:upper:]' '[:lower:]')"

      # C-3 does the cited content support the claim?
      #
      # STRONG TOKENS ONLY. An earlier version fell back to ordinary words when a
      # claim had no strong token, and that fallback was a FALSE-PASS path proven
      # by its own break arm: the claim "Spring AMQP - the queue client" cited at
      # the WRONG line still passed, because the weak token "spring" matches
      # `spring-boot-starter-web` exactly as well as `-amqp`. Measured on the real
      # doc set, strong-verified=10 / weak-verified=0 — every real citation
      # already carries a backticked identifier or a hyphenated coordinate, so the
      # fallback bought nothing and could only ever launder a wrong citation.
      #
      # A claim with no strong token is UNCHECKABLE: reported, never counted as a
      # pass. "Could not check" is not "clean".
      matched=0
      strong="$(tokens_for "$docline" strong)"
      if [ -z "$strong" ]; then
        UNCHECKABLE=$((UNCHECKABLE + 1))
        printf '  UNCHECKABLE %s:%s cites %s:%s — the claim carries no distinctive token to verify against\n' \
          "$doc" "$docline_no" "$path" "$spec"
        continue
      fi
      while IFS= read -r tok; do
        [ -z "$tok" ] && continue
        tok_lc="$(printf '%s' "$tok" | tr '[:upper:]' '[:lower:]')"
        case "$cited_lc" in *"$tok_lc"*) matched=1; break ;; esac
      done <<< "$strong"

      if [ "$matched" = 1 ]; then
        STRONG_OK=$((STRONG_OK + 1))
      elif [ "$dialect" = yaml ]; then
        # C-3 DOES NOT APPLY TO A REGISTER LOCATOR UNLESS THE ROW DECLARES ITS
        # SUBJECT. This is the whole design decision of #346, so it is written down.
        #
        # A markdown citation QUOTES: "Next.js 16.2.12 — `package.json:36`" asserts
        # that line 36 says exactly that, and token-matching verifies it. A register
        # `locator:` POINTS: it means "the thing this row is about lives here". Many
        # rows describe a CLASS of failure whose file never names them — TS-12 is
        # about scrape targets generally and cites `prometheus.yml.tmpl:72`, one
        # example job.
        #
        # Two heuristics were built and MEASURED against the real register before
        # this was settled, because both looked reasonable on paper:
        #   claim = all row prose, token match      -> 16 violations / 17 locators
        #   + CamelCase and UPPER_SNAKE tokens      -> 12 violations / 17
        #   + "token exists elsewhere in the file"  -> 11 violations / 17, and the
        #     tokens doing the matching were NEVER, FROM, EVERY, FAILED, restart —
        #     prose emphasis, not identifiers.
        # Every one of those violations was on a locator that is CORRECT. A gate
        # that is red on a correct tree gets switched off, and "fixing" it would
        # have meant rewriting a dozen accurate locators to satisfy a heuristic.
        #
        # So the subject is DECLARED, never inferred — the same rule this repo
        # already applies to `eol_slug` in infra/dependency-horizons.yaml, and for
        # the same reason: the derived value looked obvious and was wrong.
        #
        #   subject: HighMemoryUsage      <- opt in, and the locator is checked hard
        #   (absent)                      <- UNCHECKABLE, reported, never a pass
        #
        # A row that declares `subject:` gets the real drift check, which is the
        # defect #345 actually found: TS-14 named HighMemoryUsage (alerts.yml:127)
        # while its locator still pointed at :96, `- alert:
        # TooManyDatabaseConnections` — same file, wrong line.
        if [ -n "$subject" ]; then
          if command grep -qiF -- "$subject" <<< "$cited"; then
            STRONG_OK=$((STRONG_OK + 1))
          else
            found_at=$(command grep -niF -- "$subject" "$target" 2>/dev/null | head -1 | cut -d: -f1)
            violation "C-3 $doc:$docline_no cites $path:$spec, but its declared subject '$subject' is not on that line${found_at:+ — it is at $path:$found_at}
       cited: $(printf '%s' "$cited" | sed -E 's/^[[:space:]]+//' | cut -c1-92)"
          fi
        else
          UNCHECKABLE=$((UNCHECKABLE + 1))
          printf '  UNCHECKABLE %s:%s cites %s:%s — row declares no `subject:`, so the line cannot be checked against it\n' \
            "$doc" "$docline_no" "$path" "$spec"
        fi
      else
        violation "C-3 $doc:$docline_no cites $path:$spec, but that line says nothing the claim names
       claim: $(printf '%s' "$docline" | sed -E 's/^[[:space:]]*//' | cut -c1-92)
       cited: $(printf '%s' "$cited" | sed -E 's/^[[:space:]]+//' | cut -c1-92)"
      fi
  done <<< "$(citations_in "$doc")"

  printf '  %-42s citations=%s\n' "$doc" "$doc_cites"
done

echo "  citations   total=$CITES  verified=$STRONG_OK  uncheckable=$UNCHECKABLE"
echo "  violations  $VIOLATIONS"

# ORDER IS LOAD-BEARING, and an earlier version had it wrong.
#
#   1. ZERO citations discovered -> VOID. The pattern may have broken, the docs
#      may have moved, or the scan set may be wrong. All of those are "could not
#      check", never "clean".
#   2. Violations found -> exit 1. Finding violations IS a successful evaluation,
#      so it must NOT be reported as VOID. The first version tested
#      "nothing verified" before "violations found", so a document whose
#      citations were ALL wrong exited 2 instead of 1 — the gate's loudest
#      possible signal was downgraded to "could not check". Proven by its own
#      break arm, which expected 1 and measured 2.
#   3. Citations discovered but NONE verifiable -> VOID. A pass over zero
#      verified claims is a vacuous pass.
[ "$CITES" -gt 0 ] || void "ZERO citations discovered across ${#DOCS[@]} doc(s) — the scanner found nothing, which is not the same as nothing being wrong"

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "FAILED: $VIOLATIONS citation(s) do not resolve to what the doc claims." >&2
  echo "Fix the citation (or the doc), never the gate. A citation nobody can follow is worse than none." >&2
  exit 1
fi

[ "$STRONG_OK" -gt 0 ] || void "$CITES citation(s) found but NONE could be verified (all uncheckable) — reporting clean over zero verified claims would be a vacuous pass"

echo "PASS: all $STRONG_OK verified citation(s) across ${#DOCS[@]} doc(s) resolve to lines that support their claim ($UNCHECKABLE uncheckable)."
exit 0
