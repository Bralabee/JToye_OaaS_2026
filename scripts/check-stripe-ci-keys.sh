#!/usr/bin/env bash
# check-stripe-ci-keys.sh — guard that STRIPE_TEST_SECRET_KEY and CI_STRIPE_PUBLISHABLE_KEY
# passed into CI / nightly jobs are genuine Stripe TEST-mode credentials (Phase 30 plan 30-22,
# PAY-01; threat mitigations T-30-09, T-30-10).
#
# USAGE
#   STRIPE_TEST_SECRET_KEY=<value> CI_STRIPE_PUBLISHABLE_KEY=<value> scripts/check-stripe-ci-keys.sh
#   STRIPE_TEST_SECRET_KEY=<value> [STRIPE_WEBHOOK_SECRET=<value>] \
#     scripts/check-stripe-ci-keys.sh --scan-artifacts <path>...
#   scripts/check-stripe-ci-keys.sh --lint [<repo-root>]
#   scripts/check-stripe-ci-keys.sh --selftest
#   STRIPE_CI_KEYS_SELFTEST_FORMAT=tap scripts/check-stripe-ci-keys.sh --selftest
#
# --scan-artifacts (T-30-87, the execution point): run immediately before the nightly uploads its
#   artifacts from a PUBLIC repository. Searches every file under the given paths — zip members
#   expanded, so a Playwright trace.zip is read, not skipped — for the secret key and the webhook
#   signing secret taken from the environment. A file that holds either is DELETED before it can be
#   uploaded and the run exits 1; only paths are printed, never a value. A zip that cannot be read
#   is deleted too (it cannot be shown clean). Absent paths are listed and skipped; zero files
#   scanned in total, or no secret to scan for, is VOID (2), never clean.
# --lint: the structural layers the scan backs up — every frontend/e2e/stripe-*.spec.ts applies
#   test.use(STRIPE_SPEC_ARTIFACTS) at the top level and neither names the Stripe API base nor
#   reads the secret itself; frontend/e2e/stripe-test-api.ts keeps trace "off" and never imports
#   Playwright; the nightly's Stripe run writes its Playwright output under $RUNNER_TEMP (not
#   uploaded) and the --scan-artifacts step covers every upload path and comes before the upload.
#
# EXIT
#   0  accepted (keys) / no secret found (scan) / lint clean
#   1  refused (unset/empty key, live key, restricted key, swapped keys, or malformed characters) /
#      a secret found and its file deleted (scan) / a lint rule broken
#   2  usage error / VOID (scan: nothing to scan or no secret to scan for; lint: a file missing)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$SCRIPT_DIR/check-stripe-ci-keys.sh"

void() { echo "VOID: $*" >&2; exit 2; }
refuse() { echo "FAIL: $*" >&2; exit 1; }

well_formed() {
  local rest
  case "$1" in "$2"*) rest="${1#"$2"}" ;; *) return 1 ;; esac
  case "$rest" in ''|*[!A-Za-z0-9]*) return 1 ;; esac
  return 0
}

classify_prefix() {
  case "$1" in
    '') echo empty ;;
    sk_test_*) echo sk_test_ ;;
    sk_live_*) echo sk_live_ ;;
    pk_test_*) echo pk_test_ ;;
    pk_live_*) echo pk_live_ ;;
    rk_test_*) echo rk_test_ ;;
    rk_live_*) echo rk_live_ ;;
    *) echo other ;;
  esac
}

check() {
  local sk="${STRIPE_TEST_SECRET_KEY-}" pk="${CI_STRIPE_PUBLISHABLE_KEY:-${STRIPE_TEST_PUBLISHABLE_KEY-}}"
  local sk_prefix pk_prefix
  sk_prefix="$(classify_prefix "$sk")"
  pk_prefix="$(classify_prefix "$pk")"
  echo "secret_prefix=$sk_prefix secret_length=${#sk} pk_prefix=$pk_prefix pk_length=${#pk}"

  if [ "$sk_prefix" = empty ]; then
    refuse "STRIPE_TEST_SECRET_KEY is unset or empty — provision the repository secret with a Stripe TEST secret key (sk_test_...); do not skip the Stripe E2E phase."
  fi
  if [ "$sk_prefix" != sk_test_ ]; then
    refuse "STRIPE_TEST_SECRET_KEY must be a Stripe TEST secret key (sk_test_...), got prefix=$sk_prefix."
  fi
  well_formed "$sk" sk_test_ || refuse "STRIPE_TEST_SECRET_KEY starts sk_test_ but contains non-alphanumeric characters (length=${#sk})."

  if [ "$pk_prefix" = empty ]; then
    refuse "CI_STRIPE_PUBLISHABLE_KEY is unset or empty — provision the repository variable with a Stripe TEST publishable key (pk_test_...)."
  fi
  if [ "$pk_prefix" != pk_test_ ]; then
    refuse "CI_STRIPE_PUBLISHABLE_KEY must be a Stripe TEST publishable key (pk_test_...), got prefix=$pk_prefix."
  fi
  well_formed "$pk" pk_test_ || refuse "CI_STRIPE_PUBLISHABLE_KEY starts pk_test_ but contains non-alphanumeric characters (length=${#pk})."

  echo "OK: Stripe CI test keys accepted (secret_prefix=$sk_prefix secret_length=${#sk} pk_prefix=$pk_prefix pk_length=${#pk})"
  exit 0
}

# ---------------------------------------------------------------- --scan-artifacts (T-30-87)
count_in_file() {  # <file> <value> -> prints the matching line count; never prints the value
  local n
  case "$1" in
    *.zip) n=$(unzip -p "$1" 2>/dev/null | command grep -caF -- "$2") ;;
    *) n=$(command grep -caF -- "$2" "$1" 2>/dev/null) ;;
  esac
  printf '%s\n' "${n:-0}"
}

scan_artifacts() {
  local -a values=() paths=("$@")
  local v p f files=0 leaks=0 n hit
  [ "${#paths[@]}" -gt 0 ] || { echo "USAGE ERROR: --scan-artifacts needs at least one path" >&2; exit 2; }
  [ -n "${STRIPE_TEST_SECRET_KEY-}" ] && values+=("$STRIPE_TEST_SECRET_KEY")
  [ -n "${STRIPE_WEBHOOK_SECRET-}" ] && values+=("$STRIPE_WEBHOOK_SECRET")
  [ "${#values[@]}" -gt 0 ] || void "no secret to scan for: STRIPE_TEST_SECRET_KEY and STRIPE_WEBHOOK_SECRET are both empty"
  for v in "${values[@]}"; do
    [ "${#v}" -ge 12 ] || void "a secret to scan for is shorter than 12 characters (length=${#v}); refusing a scan that would match noise"
  done
  if ! command -v unzip >/dev/null 2>&1; then
    for p in "${paths[@]}"; do
      [ -e "$p" ] && /usr/bin/find "$p" -type f -name '*.zip' -print -delete 2>/dev/null | sed 's/^/DELETED-UNSCANNED: /'
    done
    void "unzip is not installed, so no .zip could be read; every .zip under the scanned paths was deleted unscanned"
  fi
  for p in "${paths[@]}"; do
    if [ ! -e "$p" ]; then echo "absent: $p"; continue; fi
    while IFS= read -r -d '' f; do
      files=$((files + 1))
      case "$f" in
        *.zip)
          if ! unzip -tqq "$f" >/dev/null 2>&1; then
            rm -f -- "$f"; echo "UNREADABLE (deleted before upload): $f"; leaks=$((leaks + 1)); continue
          fi ;;
      esac
      hit=""
      for v in "${values[@]}"; do
        n=$(count_in_file "$f" "$v")
        if [ "$n" -gt 0 ]; then hit=yes; break; fi
      done
      if [ -n "$hit" ]; then
        rm -f -- "$f"; echo "LEAK (deleted before upload): $f"; leaks=$((leaks + 1))
      fi
    done < <(/usr/bin/find "$p" -type f -print0 2>/dev/null)
  done
  [ "$files" -gt 0 ] || void "no files under the scanned paths (${paths[*]}); a scan of nothing is not a clean scan"
  if [ "$leaks" -gt 0 ]; then
    refuse "$leaks of $files artifact file(s) held a Stripe secret or could not be read; each was deleted before upload (T-30-87). Find the writer: a Stripe call that went through Playwright."
  fi
  echo "OK: scanned $files artifact file(s) under ${#paths[@]} path(s) for ${#values[@]} Stripe secret(s); none found"
  exit 0
}

# ---------------------------------------------------------------- --lint (the structural layers)
lint() {
  local root="${1:-$SCRIPT_DIR/..}" bad=0 spec api nightly line run_line scan_line upload_line found=0 up
  api="$root/frontend/e2e/stripe-test-api.ts"
  nightly="$root/.github/workflows/e2e-nightly.yml"
  [ -f "$api" ] || void "missing $api"
  [ -f "$nightly" ] || void "missing $nightly"
  lfail() { echo "FAIL: lint $1"; bad=$((bad + 1)); }
  for spec in "$root"/frontend/e2e/stripe-*.spec.ts; do
    [ -f "$spec" ] || continue
    found=$((found + 1))
    command grep -qE '^test\.use\(STRIPE_SPEC_ARTIFACTS\)' "$spec" \
      || lfail "$(basename "$spec") does not apply test.use(STRIPE_SPEC_ARTIFACTS) at the top level (traces would keep request headers)"
    ! command grep -qF 'https://api.stripe.com' "$spec" \
      || lfail "$(basename "$spec") names the Stripe API base itself; Stripe calls go through stripe-test-api.ts (Node fetch), never Playwright's request fixture"
    ! command grep -qF 'process.env.STRIPE_TEST_SECRET_KEY' "$spec" \
      || lfail "$(basename "$spec") reads STRIPE_TEST_SECRET_KEY itself; only stripe-test-api.ts may hold the secret"
  done
  [ "$found" -gt 0 ] || void "no frontend/e2e/stripe-*.spec.ts under $root"
  command grep -qE 'STRIPE_SPEC_ARTIFACTS = \{ trace: "off"' "$api" \
    || lfail "stripe-test-api.ts STRIPE_SPEC_ARTIFACTS does not set trace \"off\""
  ! command grep -qF '@playwright/test' "$api" \
    || lfail "stripe-test-api.ts imports Playwright; its client must stay on Node fetch"
  run_line=$(command grep -nE '^[^#]*npx playwright test e2e/stripe-' "$nightly" | head -n 1)
  if [ -z "$run_line" ]; then
    lfail "e2e-nightly.yml has no Stripe Playwright run"
  else
    case "$run_line" in
      *'--output "$RUNNER_TEMP/'*) ;;
      *) lfail "e2e-nightly.yml's Stripe run does not write its Playwright output under \$RUNNER_TEMP (frontend/test-results/ is uploaded)" ;;
    esac
  fi
  scan_line=$(command grep -nE '^[^#]*bash scripts/check-stripe-ci-keys\.sh --scan-artifacts' "$nightly" | head -n 1)
  upload_line=$(command grep -nE '^[^#]*uses: actions/upload-artifact' "$nightly" | head -n 1)
  if [ -z "$scan_line" ] || [ -z "$upload_line" ]; then
    lfail "e2e-nightly.yml lacks the --scan-artifacts step or the artifact upload"
  else
    [ "${scan_line%%:*}" -lt "${upload_line%%:*}" ] \
      || lfail "e2e-nightly.yml scans the artifacts AFTER uploading them"
    line=$(sed -n "${scan_line%%:*},$(( ${scan_line%%:*} + 3 ))p" "$nightly" | tr '\n' ' ')
    for up in frontend/e2e-artifacts frontend/playwright-report frontend/test-results; do
      case "$line" in *"$up"*) ;; *) lfail "e2e-nightly.yml --scan-artifacts does not cover $up" ;; esac
    done
  fi
  [ "$bad" -eq 0 ] || exit 1
  echo "OK: lint — $found Stripe spec(s), stripe-test-api.ts and e2e-nightly.yml keep the secret out of uploaded artifacts"
  exit 0
}

RESULTS=()
result() { RESULTS+=("$1"); }
render_results() {
  local fmt="$1" i=0 r verdict rest
  if [ "$fmt" = tap ]; then
    echo "TAP version 13"
    echo "1..${#RESULTS[@]}"
    for r in "${RESULTS[@]}"; do
      i=$((i + 1)); verdict="${r%% *}"; rest="${r#* }"
      if [ "$verdict" = PASS ]; then
        echo "ok $i - ${rest%% *}"
      else
        echo "not ok $i - ${rest%% *}"
        echo "  ---"
        echo "  message: '$(printf '%s' "${rest#* }" | tr "'\n" '" ')'"
        echo "  ..."
      fi
    done
  else
    for r in "${RESULTS[@]}"; do printf '%s\n' "$r"; done
  fi
}

st_run() {
  local rc=0
  STRIPE_TEST_SECRET_KEY="$2" CI_STRIPE_PUBLISHABLE_KEY="$3" bash "$SELF" > "$ST/$1.out" 2>&1 < /dev/null || rc=$?
  echo "$rc"
}

arm() {
  local name="$1" want="$2" got="$3" tag="$4" needle="$5" why=""
  [ "$got" = "$want" ] || why="rc=$got (want $want): $(tail -n 2 "$ST/$tag.out" | tr '\n' ' ')"
  if [ -z "$why" ] && ! command grep -qF -- "$needle" "$ST/$tag.out"; then
    why="output lacks '$needle': $(tail -n 2 "$ST/$tag.out" | tr '\n' ' ')"
  fi
  if [ -z "$why" ]; then result "PASS $name rc=$got"; else result "FAIL $name $why"; FAILS=$((FAILS + 1)); fi
}

selftest() {
  local fmt="${STRIPE_CI_KEYS_SELFTEST_FORMAT:-plain}" rc sk_test sk_live pk_test pk_live whsec why f v
  FAILS=0
  ST="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-cikeys-st.XXXXXXXX")" || void "mktemp -d failed"
  trap 'rm -rf "$ST"' EXIT
  sk_test="s""k_""test_""CiKeysSelfTestSecret012345"
  sk_live="s""k_""live_""CiKeysLiveSecret0123456789"
  pk_test="p""k_""test_""CiKeysSelfTestPub012345678"
  pk_live="p""k_""live_""CiKeysLivePub0123456789012"

  rc=$(st_run ok "$sk_test" "$pk_test");             arm ok 0 "$rc" ok "secret_prefix=sk_test_ secret_length=${#sk_test}"
  rc=$(st_run secret-empty "" "$pk_test");           arm secret-empty 1 "$rc" secret-empty "STRIPE_TEST_SECRET_KEY"
  rc=$(st_run secret-live "$sk_live" "$pk_test");    arm secret-live 1 "$rc" secret-live "TEST secret key"
  rc=$(st_run pk-live "$sk_test" "$pk_live");        arm pk-live 1 "$rc" pk-live "TEST publishable key"
  rc=$(st_run swapped "$pk_test" "$sk_test");        arm swapped 1 "$rc" swapped "TEST secret key"

  # --scan-artifacts arms (T-30-87). Fixture trees live in $ST/fx-*, never in *.out, so the
  # no-echo scan below reads only what the script PRINTED.
  whsec="w""hsec_""CiKeysSelfTestSigning0123"
  command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1 || void "the selftest needs zip and unzip to build its trace fixtures"
  mkfx() {  # <name>: a clean artifact tree: a report, an error-context.md and a clean trace.zip
    mkdir -p "$ST/fx-$1/test-results/t1" "$ST/fx-$1/e2e-artifacts" "$ST/zsrc-$1"
    printf '{"suites":[]}\n' > "$ST/fx-$1/e2e-artifacts/report.json"
    printf 'Error: expected 200\n' > "$ST/fx-$1/test-results/t1/error-context.md"
    printf 'GET /v1/refunds\nauthorization: Bearer <none>\n' > "$ST/zsrc-$1/0-trace.network"
    (cd "$ST/zsrc-$1" && zip -q "$ST/fx-$1/test-results/t1/trace.zip" 0-trace.network)
  }
  scan_run() {  # <tag> <sk> <whsec> <path>...
    local tag="$1" sk="$2" wh="$3" rc=0; shift 3
    STRIPE_TEST_SECRET_KEY="$sk" STRIPE_WEBHOOK_SECRET="$wh" bash "$SELF" --scan-artifacts "$@" > "$ST/$tag.out" 2>&1 < /dev/null || rc=$?
    echo "$rc"
  }
  mkfx clean
  rc=$(scan_run scan-clean "$sk_test" "$whsec" "$ST/fx-clean/e2e-artifacts" "$ST/fx-clean/test-results" "$ST/fx-clean/absent")
  arm scan-clean 0 "$rc" scan-clean "OK: scanned 3 artifact file(s)"
  mkfx plain; printf 'apiRequestContext.get: ECONNREFUSED\n  - Authorization: Bearer %s\n' "$sk_test" > "$ST/fx-plain/test-results/t1/error-context.md"
  rc=$(scan_run scan-plain "$sk_test" "" "$ST/fx-plain/e2e-artifacts" "$ST/fx-plain/test-results")
  arm scan-plain 1 "$rc" scan-plain "LEAK (deleted before upload): $ST/fx-plain/test-results/t1/error-context.md"
  [ -e "$ST/fx-plain/test-results/t1/error-context.md" ] && { result "FAIL scan-plain-deleted the leaking file is still on disk"; FAILS=$((FAILS + 1)); }
  [ -e "$ST/fx-plain/e2e-artifacts/report.json" ] || { result "FAIL scan-plain-kept a clean file was deleted"; FAILS=$((FAILS + 1)); }
  mkfx zip; printf 'GET /v1/refunds\nauthorization: Bearer %s\n' "$sk_test" > "$ST/zsrc-zip/0-trace.network"
  rm -f "$ST/fx-zip/test-results/t1/trace.zip"; (cd "$ST/zsrc-zip" && zip -q "$ST/fx-zip/test-results/t1/trace.zip" 0-trace.network)
  rc=$(scan_run scan-zip "$sk_test" "" "$ST/fx-zip/test-results")
  arm scan-zip 1 "$rc" scan-zip "LEAK (deleted before upload): $ST/fx-zip/test-results/t1/trace.zip"
  mkfx whsec; printf 'whsec in a log: %s\n' "$whsec" > "$ST/fx-whsec/e2e-artifacts/listen.log"
  rc=$(scan_run scan-whsec "$sk_test" "$whsec" "$ST/fx-whsec/e2e-artifacts")
  arm scan-whsec 1 "$rc" scan-whsec "LEAK (deleted before upload): $ST/fx-whsec/e2e-artifacts/listen.log"
  mkfx badzip; printf 'not a zip' > "$ST/fx-badzip/test-results/t1/trace.zip"
  rc=$(scan_run scan-badzip "$sk_test" "" "$ST/fx-badzip/test-results")
  arm scan-badzip 1 "$rc" scan-badzip "UNREADABLE (deleted before upload)"
  rc=$(scan_run scan-void-nosecret "" "" "$ST/fx-clean/e2e-artifacts")
  arm scan-void-nosecret 2 "$rc" scan-void-nosecret "no secret to scan for"
  rc=$(scan_run scan-void-nothing "$sk_test" "" "$ST/fx-clean/absent")
  arm scan-void-nothing 2 "$rc" scan-void-nothing "a scan of nothing is not a clean scan"

  # --lint arms: the real tree, then copies with one layer removed each.
  lint_run() {  # <tag> <root>
    local rc=0
    bash "$SELF" --lint "$2" > "$ST/$1.out" 2>&1 < /dev/null || rc=$?
    echo "$rc"
  }
  rc=$(lint_run lint-real "$SCRIPT_DIR/..");          arm lint-real 0 "$rc" lint-real "OK: lint"
  lintfx() {  # <name>: a copy of the three linted files from the real tree
    mkdir -p "$ST/lx-$1/frontend/e2e" "$ST/lx-$1/.github/workflows"
    cp "$SCRIPT_DIR"/../frontend/e2e/stripe-*.spec.ts "$SCRIPT_DIR/../frontend/e2e/stripe-test-api.ts" "$ST/lx-$1/frontend/e2e/"
    cp "$SCRIPT_DIR/../.github/workflows/e2e-nightly.yml" "$ST/lx-$1/.github/workflows/"
  }
  lintfx trace; sed -i '/^test\.use(STRIPE_SPEC_ARTIFACTS)/d' "$ST"/lx-trace/frontend/e2e/stripe-*.spec.ts
  rc=$(lint_run lint-trace "$ST/lx-trace");           arm lint-trace 1 "$rc" lint-trace "does not apply test.use(STRIPE_SPEC_ARTIFACTS)"
  lintfx request; for f in "$ST"/lx-request/frontend/e2e/stripe-*.spec.ts; do printf 'const STRIPE_API = "https://api.stripe.com"\n' >> "$f"; done
  rc=$(lint_run lint-request "$ST/lx-request");       arm lint-request 1 "$rc" lint-request "names the Stripe API base itself"
  lintfx output; sed -i 's| --output "\$RUNNER_TEMP/[^"]*"||' "$ST/lx-output/.github/workflows/e2e-nightly.yml"
  rc=$(lint_run lint-output "$ST/lx-output");         arm lint-output 1 "$rc" lint-output "does not write its Playwright output under"
  lintfx noscan; sed -i 's|check-stripe-ci-keys\.sh --scan-artifacts|true|' "$ST/lx-noscan/.github/workflows/e2e-nightly.yml"
  rc=$(lint_run lint-noscan "$ST/lx-noscan");         arm lint-noscan 1 "$rc" lint-noscan "lacks the --scan-artifacts step"

  why=""
  for f in "$ST"/*.out; do
    for v in "$sk_test" "$sk_live" "$pk_test" "$pk_live" "$whsec"; do
      if command grep -qF -- "$v" "$f"; then why="$(basename "$f") contains a fixture value"; break 2; fi
    done
  done
  [ -z "$why" ] && ! command grep -qF "secret_length=${#sk_test}" "$ST/ok.out" && why="ok.out does not describe the key, so the scan above proves nothing"
  if [ -z "$why" ]; then result "PASS no-echo"; else result "FAIL no-echo $why"; FAILS=$((FAILS + 1)); fi

  render_results "$fmt"
  [ "$fmt" = tap ] && printf "# "; echo "SELFTEST: $(( ${#RESULTS[@]} - FAILS ))/${#RESULTS[@]}"
  [ "$FAILS" -eq 0 ] || exit 1
  exit 0
}

case "${1-}" in
  "")
    check ;;
  --selftest)
    [ "$#" -eq 1 ] || { echo "USAGE ERROR: --selftest takes no other argument" >&2; exit 2; }
    selftest ;;
  --scan-artifacts)
    shift
    scan_artifacts "$@" ;;
  --lint)
    [ "$#" -le 2 ] || { echo "USAGE ERROR: --lint takes at most one repo root" >&2; exit 2; }
    lint "${2-}" ;;
  -h|--help)
    sed -n '/^# USAGE/,/^# EXIT/p' "$SELF" | sed '$d' | sed 's/^# \{0,1\}//' ;;
  *)
    echo "USAGE ERROR: check-stripe-ci-keys.sh takes no positional arguments (keys are read from the environment), --scan-artifacts <path>..., --lint [<root>] or --selftest" >&2
    exit 2 ;;
esac
