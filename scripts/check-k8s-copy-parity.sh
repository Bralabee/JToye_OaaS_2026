#!/usr/bin/env bash
# check-k8s-copy-parity.sh — every committed copy of a canonical file is byte-equal
# to it (Phase 29 plan 29-10, T-29-36). Generalises the phase-29-research branch's
# check-alert-corpus-parity.sh from one hard-coded pair to the declared list in
# k8s/copy-parity.conf.
#
# WHY THIS EXISTS
#
#   kustomize's configMapGenerator may only read files INSIDE its own kustomization
#   root, so `../../infra/...` is rejected ("security; file is not in or below the
#   current directory"), and a symlink is resolved the same way. There is no
#   supported escape. So a file that BOTH runtimes must serve — compose by bind
#   mount, k8s by ConfigMap — becomes a committed COPY, and without a gate the
#   invariant "it is the same file" is an intention. The failure is the quiet kind:
#   the two drift, both runtimes keep serving what they have, every check stays
#   green, and the only symptom is that an edit made in one place (a login-theme
#   fix, an alert rule) never reaches the other runtime. Nothing goes red.
#
# WHAT IT ASSERTS
#
#   For every `<copy> <canonical>` line of k8s/copy-parity.conf:
#       md5(copy) == md5(canonical)
#
#   BYTE-EQUALITY, deliberately (the branch gate's reasoning, kept): any semantic
#   comparison needs a normaliser, a normaliser that is slightly wrong makes a
#   required gate cry wolf, and a checksum has no such failure mode. It also catches
#   comment-only drift, and it is the only comparison that means anything for a
#   binary file (the theme's woff2 font).
#
#   ONE DELIBERATE EXCEPTION, its own pair kind (plan 29-23). For every
#   `jobs <k8s-config> <compose-config>` line: the two Prometheus scrape configs
#   cannot be byte-equal (their scrape TARGETS differ by runtime), but
#   check-alert-liveness.sh maps each rule to a job by NAME, so the job_name set and
#   what each job stamps on its series must not drift. compare_jobs (below) asserts
#   the sorted job_name sets equal and, per job, labels (static labels plus
#   source-less constant relabels), metrics_path, params and metric_relabel_configs
#   equal. It parses YAML (python3 + PyYAML), so it is a narrow structural check,
#   not a normaliser: anything it cannot read is VOID, never "equal".
#
# WHICH FILE IS CANONICAL
#
#   The RIGHT-HAND path of each line. When this gate fails, copy canonical -> copy,
#   never the other way, unless the copy is where the intended edit was made, in
#   which case copy it BACK to the canonical path first and then re-run. Either way
#   one commit ends with the two byte-equal. For a compose-mounted canonical file,
#   recreate that container afterwards (a single-file bind mount detaches on inode
#   change, so the running container keeps the old bytes).
#
#   THE COPY CARRIES NO HEADER OF ITS OWN, and that is not an oversight. A "this is
#   a copy" banner inside the copy would make the two differ by construction and this
#   gate could never pass. The statement lives in the kustomization beside each
#   generator and in k8s/copy-parity.conf.
#
#   K8S-ONLY JOBS (issue #972). A scrape job whose series exist only in the cluster (cert-manager's
#   metrics: compose has no cert-manager) is declared with a third directive,
#       k8s-only-job <k8s-config> <job_name>
#   where <k8s-config> is the k8s side of a `jobs` pair. The job is then left out of the job-set
#   comparison, and the declaration is itself checked both ways: the job MUST exist in the k8s
#   config (else the declaration is stale) and MUST NOT exist in the compose config (else it is a
#   shared job now and the declaration hides a real comparison). An undeclared k8s-only job is
#   still a job-set difference, so the exemption cannot be widened by editing the config alone.
#
# WHAT IT DOES NOT ASSERT
#
#   That the content is correct, that the generator actually lists the copy, or that
#   a running pod serves these bytes. The first is the canonical file's own gates, the
#   second is the render (the goldens), the third is the live check of the plan that
#   ships the runtime. This answers exactly one question: are the declared pairs the
#   same bytes.
#
# EXIT CODES — uniform across this repo's gates
#   0 = every declared pair is byte-identical (the count is printed)
#   1 = at least one pair has DRIFTED (pair, both md5s and the canonical side named)
#   2 = VOID: the list is missing, empty, comment-only or malformed; a jobs pair
#       cannot be read (no python3/PyYAML, a file that does not parse, zero jobs,
#       a duplicate job_name); a path is
#       absolute, has a `..` segment, is a symlink or is listed twice; a copy or a
#       canonical file is missing, unreadable, empty or not a regular file; or md5sum
#       is not on PATH. A missing input is never clean: the natural bug here is a
#       `[ -f X ] || continue`, which would turn a deleted copy (the most complete
#       drift possible) into a pass.
#
# USAGE
#   bash scripts/check-k8s-copy-parity.sh             # the real tree
#   bash scripts/check-k8s-copy-parity.sh --selftest  # planted failure arms, then clean
#   COPY_PARITY_ROOT / COPY_PARITY_CONF override the root and the list (the selftest
#   uses them); COPY_PARITY_SELFTEST_FORMAT=tap prints TAP 13 for the RED-evidence
#   classifier. COPY_PARITY_PYTHON names the interpreter for the jobs pairs
#   (default python3; on the operator host run the gate inside the jtoye-ops env).
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

SELF="${BASH_SOURCE[0]}"
# No `dirname`: the no-md5sum selftest arm runs this script with a PATH holding only
# the tools the gate needs, so the script resolves itself with parameter expansion.
if [[ "$SELF" == */* ]]; then SCRIPT_DIR="${SELF%/*}"; else SCRIPT_DIR="."; fi
SCRIPT_DIR="$(cd "$SCRIPT_DIR" && pwd)" || { echo "VOID: cannot resolve the script directory" >&2; exit 2; }
DEFAULT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)" || { echo "VOID: cannot resolve the repo root" >&2; exit 2; }

# ---------------------------------------------------------------------------
# The comparison. Every problem that stops a pair being evaluated is collected and
# reported (VOID, rc 2, wins over drift); every evaluated pair is compared by md5.
# ---------------------------------------------------------------------------

# check_side <label> <path>: a path the gate can compare, or a VOID line on stderr.
check_side() {
    local label="$1" p="$2" f="$ROOT/$2"
    if [[ -L "$f" ]]; then
        echo "VOID: $label $p is a symlink — a symlinked copy compares the canonical file with itself" >&2; return 1
    fi
    [[ -e "$f" ]] || { echo "VOID: $label not found: $p — a missing input is never clean (exit 2, not 0)" >&2; return 1; }
    [[ -f "$f" ]] || { echo "VOID: $label $p is not a regular file" >&2; return 1; }
    [[ -r "$f" ]] || { echo "VOID: $label $p is not readable" >&2; return 1; }
    # -s as well as -r: an EMPTY file is readable and would md5-match another empty file.
    [[ -s "$f" ]] || { echo "VOID: $label $p is EMPTY — it would md5-match any other empty file" >&2; return 1; }
    return 0
}

# check_path <path> <conf line no>: the path rules of k8s/copy-parity.conf.
check_path() {
    local p="$1" ln="$2"
    case "$p" in
        /*) echo "VOID: $CONF:$ln: '$p' may not be absolute (paths are repo-relative)" >&2; return 1 ;;
    esac
    if [[ "/$p/" == */../* ]]; then
        echo "VOID: $CONF:$ln: '$p' may not contain '..' (a pair must stay inside the repo)" >&2; return 1
    fi
    return 0
}

# compare_jobs <k8s-config> <compose-config>: the `jobs` pair kind (plan 29-23).
#
# The two Prometheus scrape configs may NOT be byte-equal (their targets differ by runtime), but
# scripts/check-alert-liveness.sh maps every rule to a job by NAME and VOIDs on a job it does not
# know, so the names and what each job stamps on its series must not drift. Per pair, equal on
# both sides:
#   - the sorted set of job_name values;
#   - per job, its LABELS: every static_configs[].labels entry, plus every relabel_configs rule
#     that sets a constant (action replace, the default, with NO source_labels). The second form
#     is how a dns_sd_configs job (which takes no `labels:` block) stamps the compose labels, so it
#     must count as the same thing. A relabel WITH source_labels derives its value from the target
#     and is not a constant label (compose's `instance` rule is one: per-pod `instance` in k8s is
#     the DEF-29-8 fix, so it is deliberately outside the comparison);
#   - per job, metrics_path (default /metrics), params and metric_relabel_configs, so a series
#     filter changed on one side only (the SSE queue drop) cannot drift silently either.
# rc 0 equal (one summary line on stdout), 1 differ (each difference on stderr), 2 VOID: python3
# or PyYAML missing, a file that does not parse, ZERO jobs on either side, a duplicate job_name.
compare_jobs() {
    local py="${COPY_PARITY_PYTHON:-python3}"
    command -v "$py" >/dev/null 2>&1 \
        || { echo "VOID: $py not on PATH — the jobs pair cannot be evaluated (activate an env with PyYAML)" >&2; return 2; }
    local k8s_cfg="$1" compose_cfg="$2"; shift 2
    "$py" - "$k8s_cfg" "$compose_cfg" "$@" <<'PY'
import sys
try:
    import yaml
except ImportError:
    sys.stderr.write("VOID: PyYAML is not importable; the jobs pair cannot be evaluated\n")
    sys.exit(2)

def void(msg):
    sys.stderr.write("VOID: " + msg + "\n")
    sys.exit(2)

def load(path):
    try:
        with open(path) as fh:
            doc = yaml.safe_load(fh)
    except Exception as exc:  # any parse error is VOID, never a difference
        void("%s does not parse as YAML: %s" % (path, str(exc).splitlines()[0]))
    if not isinstance(doc, dict):
        void("%s is not a YAML mapping" % path)
    sc = doc.get("scrape_configs")
    if not isinstance(sc, list) or not sc:
        void("%s declares zero jobs (no scrape_configs list) — refusing to compare job sets over nothing" % path)
    jobs = {}
    for j in sc:
        if not isinstance(j, dict) or "job_name" not in j:
            void("%s has a scrape_configs entry with no job_name" % path)
        name = str(j["job_name"])
        if name in jobs:
            void("%s declares job_name %r twice" % (path, name))
        labels = {}
        def put(k, v):
            labels.setdefault(str(k), set()).add(str(v))
        for st in j.get("static_configs") or []:
            for k, v in ((st or {}).get("labels") or {}).items():
                put(k, v)
        for r in j.get("relabel_configs") or []:
            r = r or {}
            if r.get("action", "replace") == "replace" and not r.get("source_labels") \
                    and "target_label" in r and "replacement" in r:
                put(r["target_label"], r["replacement"])
        jobs[name] = {
            "labels": {k: sorted(v) for k, v in labels.items()},
            "metrics_path": str(j.get("metrics_path", "/metrics")),
            "params": j.get("params") or {},
            "metric_relabel_configs": j.get("metric_relabel_configs") or [],
        }
    return jobs

k8s_path, compose_path = sys.argv[1], sys.argv[2]
k8s_only = sys.argv[3:]
a, b = load(k8s_path), load(compose_path)
diffs = []
for name in k8s_only:
    if name not in a:
        diffs.append("k8s-only job %r is declared but absent from the k8s config (stale declaration: remove it)" % name)
    elif name in b:
        diffs.append("k8s-only job %r also exists in the compose config (it is a shared job now; remove the declaration so it is compared)" % name)
    else:
        del a[name]
only_a, only_b = sorted(set(a) - set(b)), sorted(set(b) - set(a))
if only_a or only_b:
    diffs.append("job sets differ: only in the k8s config %s; only in the compose config %s"
                 % (only_a or "[]", only_b or "[]"))
for name in sorted(set(a) & set(b)):
    for field in ("labels", "metrics_path", "params", "metric_relabel_configs"):
        if a[name][field] != b[name][field]:
            diffs.append("job %r %s differ: k8s %r vs compose %r" % (name, field, a[name][field], b[name][field]))
if diffs:
    sys.stderr.write("FAIL: jobs pair has DRIFTED (k8s %s vs compose %s)\n" % (k8s_path, compose_path))
    for d in diffs:
        sys.stderr.write("  " + d + "\n")
    sys.exit(1)
print("jobs=%d (%s), labels/metrics_path/params/metric_relabel_configs equal per job%s"
      % (len(a), ",".join(sorted(a)), ("; %d k8s-only job(s) declared (%s)" % (len(k8s_only), ",".join(k8s_only))) if k8s_only else ""))
PY
}

run_check() {
    # Tooling first: without md5sum nothing below can be evaluated.
    command -v md5sum >/dev/null 2>&1 \
        || { echo "VOID: md5sum not on PATH — the comparison cannot be evaluated" >&2; return 2; }
    [[ -d "$ROOT" ]] || { echo "VOID: root not found: $ROOT" >&2; return 2; }
    [[ -e "$CONF" ]] || { echo "VOID: pair list not found: $CONF" >&2; return 2; }
    [[ -f "$CONF" && -r "$CONF" ]] || { echo "VOID: pair list not readable: $CONF" >&2; return 2; }

    local -a copies=() canons=() f=() jk8s=() jcompose=() ko_cfg=() ko_name=()
    local -A seen=()
    local raw line ln=0 voids=0
    while IFS= read -r raw || [[ -n "$raw" ]]; do
        ln=$((ln + 1))
        line="${raw%%#*}"          # `#` starts a comment; paths may not contain one
        read -r -a f <<< "$line"
        (( ${#f[@]} == 0 )) && continue
        # The `jobs` pair kind (plan 29-23): three fields, the first the literal `jobs`.
        if (( ${#f[@]} == 3 )) && [[ "${f[0]}" == jobs ]]; then
            check_path "${f[1]}" "$ln" || { voids=$((voids + 1)); continue; }
            check_path "${f[2]}" "$ln" || { voids=$((voids + 1)); continue; }
            if [[ "${f[1]}" == "${f[2]}" ]]; then
                echo "VOID: $CONF:$ln jobs pair names the same path on both sides ('${f[1]}') — a file compared with itself proves nothing" >&2
                voids=$((voids + 1)); continue
            fi
            if [[ -n "${seen[jobs:${f[1]}]+set}" ]]; then
                echo "VOID: $CONF:$ln: jobs pair for '${f[1]}' is listed twice (first at line ${seen[jobs:${f[1]}]})" >&2
                voids=$((voids + 1)); continue
            fi
            seen["jobs:${f[1]}"]="$ln"
            jk8s+=("${f[1]}"); jcompose+=("${f[2]}")
            continue
        fi
        # The `k8s-only-job` directive (issue #972): <k8s-config> <job_name>.
        if (( ${#f[@]} == 3 )) && [[ "${f[0]}" == k8s-only-job ]]; then
            check_path "${f[1]}" "$ln" || { voids=$((voids + 1)); continue; }
            ko_cfg+=("${f[1]}"); ko_name+=("${f[2]}")
            continue
        fi
        if (( ${#f[@]} != 2 )); then
            echo "VOID: $CONF:$ln is malformed — want '<copy-path> <canonical-path>', 'jobs <k8s-config> <compose-config>' or 'k8s-only-job <k8s-config> <job_name>', got: $raw" >&2
            voids=$((voids + 1)); continue
        fi
        check_path "${f[0]}" "$ln" || { voids=$((voids + 1)); continue; }
        check_path "${f[1]}" "$ln" || { voids=$((voids + 1)); continue; }
        if [[ "${f[0]}" == "${f[1]}" ]]; then
            echo "VOID: $CONF:$ln names the same path on both sides ('${f[0]}') — a file compared with itself proves nothing" >&2
            voids=$((voids + 1)); continue
        fi
        if [[ -n "${seen[${f[0]}]+set}" ]]; then
            echo "VOID: $CONF:$ln: copy '${f[0]}' is listed twice (first at line ${seen[${f[0]}]}) — one copy has one canonical file" >&2
            voids=$((voids + 1)); continue
        fi
        seen["${f[0]}"]="$ln"
        copies+=("${f[0]}"); canons+=("${f[1]}")
    done < "$CONF"

    local ko_i ko_hit ko_j
    for ko_i in "${!ko_cfg[@]}"; do
        ko_hit=0
        for ko_j in "${jk8s[@]}"; do [[ "$ko_j" == "${ko_cfg[$ko_i]}" ]] && ko_hit=1; done
        if (( ko_hit == 0 )); then
            echo "VOID: $CONF declares k8s-only-job '${ko_name[$ko_i]}' for '${ko_cfg[$ko_i]}', which is not the k8s side of any jobs pair" >&2
            voids=$((voids + 1))
        fi
    done

    if (( ${#copies[@]} + ${#jk8s[@]} == 0 )); then
        echo "VOID: $CONF declares zero pairs — refusing to report parity over nothing" >&2
        return 2
    fi

    local i c k mc mk rc_c rc_k bc bk drift=0 ok_lines=()
    for i in "${!copies[@]}"; do
        c="${copies[$i]}"; k="${canons[$i]}"
        check_side copy "$c" || { voids=$((voids + 1)); continue; }
        check_side canonical "$k" || { voids=$((voids + 1)); continue; }
        # Captured on the SAME statement as the invocation: $? read after an echo or
        # a pipe reports THAT command's status.
        mc=$(md5sum -- "$ROOT/$c"); rc_c=$?
        mk=$(md5sum -- "$ROOT/$k"); rc_k=$?
        mc="${mc%% *}"; mk="${mk%% *}"
        if (( rc_c != 0 || rc_k != 0 )) || [[ ! "$mc" =~ ^[0-9a-f]{32}$ || ! "$mk" =~ ^[0-9a-f]{32}$ ]]; then
            echo "VOID: md5sum failed or printed no digest for pair $((i + 1)) (copy rc=$rc_c, canonical rc=$rc_k)" >&2
            voids=$((voids + 1)); continue
        fi
        bc=$(wc -c < "$ROOT/$c"); bk=$(wc -c < "$ROOT/$k")
        if [[ "$mc" == "$mk" ]]; then
            ok_lines+=("  ok   $c == $k  md5=$mc  bytes=${bc// /}")
        else
            drift=$((drift + 1))
            {
                echo "FAIL: pair $((i + 1)) has DRIFTED"
                echo "  copy     : $c  md5=$mc  bytes=${bc// /}"
                echo "  CANONICAL: $k  md5=$mk  bytes=${bk// /}"
                echo "  The right-hand path is canonical: copy it over the copy (cp $k $c),"
                echo "  unless the copy holds the intended edit; then copy it back first."
            } >&2
        fi
    done

    local jrc jout
    for i in "${!jk8s[@]}"; do
        c="${jk8s[$i]}"; k="${jcompose[$i]}"
        check_side "jobs k8s config" "$c" || { voids=$((voids + 1)); continue; }
        check_side "jobs compose config" "$k" || { voids=$((voids + 1)); continue; }
        # Captured on the SAME statement as the invocation (see the md5 note above).
        local -a ko_args=()
        ko_args=()
        for ko_i in "${!ko_cfg[@]}"; do
            [[ "${ko_cfg[$ko_i]}" == "$c" ]] && ko_args+=("${ko_name[$ko_i]}")
        done
        jout="$(compare_jobs "$ROOT/$c" "$ROOT/$k" ${ko_args[@]+"${ko_args[@]}"})"; jrc=$?
        case "$jrc" in
            0) ok_lines+=("  ok   jobs $c ~ $k  $jout") ;;
            1) drift=$((drift + 1))
               echo "  The right-hand path is canonical: change the job set or labels THERE first, then mirror the k8s side." >&2 ;;
            *) voids=$((voids + 1)) ;;
        esac
    done

    echo "check-k8s-copy-parity  ($(date -u +%Y-%m-%dT%H:%M:%SZ))  list: $CONF"
    (( ${#ok_lines[@]} > 0 )) && printf '%s\n' "${ok_lines[@]}"
    if (( voids > 0 )); then
        echo "VOID: $voids problem(s) above stop the parity check being evaluated (exit 2, never 0)" >&2
        return 2
    fi
    if (( drift > 0 )); then
        echo "FAIL: $drift of $(( ${#copies[@]} + ${#jk8s[@]} )) pairs drifted. A copy that differs from its canonical file means one runtime serves an edit the other never received." >&2
        return 1
    fi
    echo "PASS: ${#copies[@]} pairs, all byte-equal; ${#jk8s[@]} jobs pair(s), job sets and labels equal."
    return 0
}

# ---------------------------------------------------------------------------
# --selftest: build a throwaway tree with two declared pairs (one text, one binary),
# plant one hole per arm, and require the gate to exit with the arm's rc AND name
# the hole. A plant that changes nothing is reported, never counted as caught.
# ---------------------------------------------------------------------------
selftest() {
    local fmt="${COPY_PARITY_SELFTEST_FORMAT:-plain}" st n=0 bad=0 tap=() line
    st="$(mktemp -d)" || { echo "VOID: --selftest could not create a temp dir" >&2; return 2; }
    # EXIT, not RETURN: the script exits straight after the selftest, and a RETURN
    # trap's scope across the nested helpers below is easy to get wrong.
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT

    # A PATH holding every external tool the gate uses EXCEPT md5sum (the
    # no-md5sum arm). Built from the real locations, so nothing else changes.
    local fb="$st/fakebin" tool p
    mkdir -p "$fb"
    for tool in wc date mktemp rm; do
        p="$(command -v "$tool")" || { echo "VOID: --selftest needs '$tool' on PATH" >&2; return 2; }
        ln -s "$p" "$fb/$tool"
    done

    # A second fake PATH WITH md5sum but without python3 (the no-python arm).
    local fbp="$st/fakebin-nopy"
    mkdir -p "$fbp"
    for tool in wc date mktemp rm md5sum; do
        p="$(command -v "$tool")" || { echo "VOID: --selftest needs '$tool' on PATH" >&2; return 2; }
        ln -s "$p" "$fbp/$tool"
    done

    build_tree() {   # $1 = root
        mkdir -p "$1/canon" "$1/copies"
        # A jobs pair: the same three jobs, different targets, and one job (core-java) whose
        # labels are static on the compose side and source-less relabels on the k8s side.
        cat > "$1/canon/prom.tmpl" <<'YML'
scrape_configs:
  - job_name: 'prometheus'
    static_configs:
      - targets: ['self:9090']
        labels:
          service: 'prometheus'
  - job_name: 'keycloak'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['kc:8080']
        labels:
          service: 'keycloak'
          component: 'auth'
  - job_name: 'core-java'
    static_configs:
      - targets: ['core-java:__PORT__']
        labels:
          service: 'core-api'
    relabel_configs:
      - source_labels: [__address__]
        target_label: instance
        replacement: 'core-java'
YML
        cat > "$1/copies/prom.yml" <<'YML'
scrape_configs:
  - job_name: 'prometheus'
    static_configs:
      - targets: ['prometheus:9090']
        labels:
          service: 'prometheus'
  - job_name: 'keycloak'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['keycloak:9000']
        labels:
          service: 'keycloak'
          component: 'auth'
  - job_name: 'core-java'
    dns_sd_configs:
      - names: ['core-java-metrics.ns.svc.cluster.local']
        type: 'A'
        port: 9091
    relabel_configs:
      - target_label: service
        replacement: 'core-api'
  - job_name: 'certm'
    static_configs:
      - targets: ['certm.ns.svc:9402']
        labels:
          component: 'platform'
YML
        printf 'parent=keycloak\nstyles=css/login.css css/jtoye.css\n' > "$1/canon/a.properties"
        printf '\x00\x01wOF2\xff\xfe\x10binary-font-bytes\x00' > "$1/canon/b.woff2"
        cp "$1/canon/a.properties" "$1/copies/a.properties"
        cp "$1/canon/b.woff2" "$1/copies/b.woff2"
        printf '# selftest list\n\ncopies/a.properties   canon/a.properties   # text pair\ncopies/b.woff2 canon/b.woff2\njobs copies/prom.yml canon/prom.tmpl\nk8s-only-job copies/prom.yml certm\n' > "$1/list.conf"
    }

    # arm <name> <want-rc> <must-contain> <plant-shell> [env-path]
    arm() {
        local name="$1" want="$2" needle="$3" plant="$4" path="${5:-}" root out rc caught before after
        n=$((n + 1))
        root="$st/$name"; build_tree "$root"
        before="$(cd "$root" && md5sum canon/* copies/* list.conf 2>/dev/null; ls -la canon copies 2>/dev/null)"
        ( cd "$root" && eval "$plant" ) || { echo "VOID: arm $name: plant failed" >&2; bad=$((bad + 1)); return; }
        after="$(cd "$root" && md5sum canon/* copies/* list.conf 2>/dev/null; ls -la canon copies 2>/dev/null)"
        if [[ -n "$plant" && "$before" == "$after" && -z "$path" ]]; then
            line="ARM $name caught=no (plant did not apply)"; caught=no
        else
            if [[ -n "$path" ]]; then
                out="$(env PATH="$path" COPY_PARITY_ROOT="$root" COPY_PARITY_CONF="$root/list.conf" "$BASH" "$SELF" 2>&1)"; rc=$?
            else
                out="$(COPY_PARITY_ROOT="$root" COPY_PARITY_CONF="$root/list.conf" "$BASH" "$SELF" 2>&1)"; rc=$?
            fi
            if [[ "$rc" -eq "$want" ]] && [[ "$out" == *"$needle"* ]]; then
                caught=yes; line="ARM $name caught=yes"
            else
                caught=no; line="ARM $name caught=no (want rc $want and '$needle'; got rc=$rc: ${out//$'\n'/ })"
            fi
        fi
        [[ "$caught" == yes ]] || bad=$((bad + 1))
        if [[ "$fmt" == tap ]]; then
            if [[ "$caught" == yes ]]; then tap+=("ok $n - $name"); else
                tap+=("not ok $n - $name" "  ---" "  message: '${line//\'/}'" "  ..."); fi
        else
            echo "$line"
        fi
    }

    # One byte of the binary copy flipped: drift, rc 1, the pair named with both
    # md5s and the canonical side. printf + dd-free rewrite keeps the size equal.
    arm one-byte-edit 1 "CANONICAL: canon/b.woff2" \
        "printf '\x00\x01wOF2\xff\xfe\x10binary-font-bytez\x00' > copies/b.woff2"
    arm missing-copy 2 "copy not found: copies/a.properties" "rm copies/a.properties"
    arm missing-canonical 2 "canonical not found: canon/a.properties" "rm canon/a.properties"
    arm no-md5sum 2 "md5sum not on PATH" ":" "$fb"
    arm empty-conf 2 "zero pairs" ": > list.conf"
    arm comment-only-conf 2 "zero pairs" "printf '# nothing\n\n   # still nothing\n' > list.conf"
    arm missing-conf 2 "pair list not found" "rm list.conf"
    arm malformed-line 2 "malformed" "printf 'copies/a.properties\n' >> list.conf"
    arm self-pair 2 "same path on both sides" "printf 'copies/b.woff2 copies/b.woff2\n' > list.conf"
    arm symlink-copy 2 "is a symlink" "rm copies/a.properties && ln -s ../canon/a.properties copies/a.properties"
    arm escaping-path 2 "may not contain '..'" "printf 'copies/a.properties ../outside.txt\n' > list.conf"
    arm duplicate-copy 2 "listed twice" "printf 'copies/a.properties canon/a.properties\n' >> list.conf"
    # The jobs pair kind (plan 29-23).
    arm renamed-job 1 "only in the k8s config ['kc']" "sed -i \"s/job_name: 'keycloak'/job_name: 'kc'/\" copies/prom.yml"
    arm dropped-label 1 "job 'keycloak' labels differ" "sed -i \"/component: 'auth'/d\" copies/prom.yml"
    arm changed-relabel-label 1 "job 'core-java' labels differ" "sed -i \"s/replacement: 'core-api'/replacement: 'core-java'/\" copies/prom.yml"
    arm zero-jobs 2 "declares zero jobs" "printf 'global: {}\n' > copies/prom.yml"
    arm duplicate-job 2 "declares job_name 'keycloak' twice" "sed -i \"s/job_name: 'prometheus'/job_name: 'keycloak'/\" copies/prom.yml"
    # The k8s-only-job directive (issue #972): the exemption is checked both ways and cannot be
    # widened by editing the k8s config alone.
    arm undeclared-k8s-job 1 "only in the k8s config ['certm']" "sed -i '/^k8s-only-job /d' list.conf"
    arm k8s-only-now-in-compose 1 "k8s-only job 'certm' also exists in the compose config" "printf \"  - job_name: 'certm'\n    static_configs:\n      - targets: ['x:1']\n        labels:\n          component: 'platform'\n\" >> canon/prom.tmpl"
    arm k8s-only-stale 1 "k8s-only job 'certm' is declared but absent from the k8s config" "sed -i \"/job_name: 'certm'/,\\\$d\" copies/prom.yml"
    arm k8s-only-unlisted-config 2 "not the k8s side of any jobs pair" "printf 'k8s-only-job canon/prom.tmpl certm\n' >> list.conf"
    arm no-python 2 "python3 not on PATH" ":" "$fbp"

    # Clean: the unmodified tree passes and states its count.
    local root out rc
    n=$((n + 1)); root="$st/clean"; build_tree "$root"
    out="$(COPY_PARITY_ROOT="$root" COPY_PARITY_CONF="$root/list.conf" "$BASH" "$SELF" 2>&1)"; rc=$?
    if [[ "$rc" -eq 0 && "$out" == *"pairs, all byte-equal"* ]]; then
        line="CLEAN rc=0"; [[ "$fmt" == tap ]] && tap+=("ok $n - clean")
    else
        bad=$((bad + 1)); line="CLEAN rc=$rc (want 0 and 'pairs, all byte-equal'): ${out//$'\n'/ }"
        [[ "$fmt" == tap ]] && tap+=("not ok $n - clean" "  ---" "  message: '${line//\'/}'" "  ...")
    fi
    if [[ "$fmt" == tap ]]; then
        printf '%s\n' "TAP version 13" "1..$n" "${tap[@]}"
    else
        echo "$line"
        echo "selftest: $((n - bad))/$n arms as expected"
    fi
    (( bad == 0 ))
}

if [[ "${1:-}" == "--selftest" ]]; then
    selftest; exit $?
fi
[[ $# -eq 0 ]] || { echo "usage: $0 [--selftest]" >&2; exit 2; }

ROOT="${COPY_PARITY_ROOT:-$DEFAULT_ROOT}"
CONF="${COPY_PARITY_CONF:-$ROOT/k8s/copy-parity.conf}"
run_check
exit $?
