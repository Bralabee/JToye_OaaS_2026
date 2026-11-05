#!/usr/bin/env bash
# check-unknown-kitchen-404.sh — an unknown /shop/<slug> answers HTTP 404 FAST on the BUILT frontend,
# however the server is started.
#
# WHY THIS EXISTS. SW-CLR-17 (#1111) made the proxy (frontend/middleware.ts) answer 404 for a kitchen core
# does not know. It was green in `next dev` and in every unit test, and on the delivered stack an unknown
# slug hung for 30 s and answered 500: the proxy rewrote to its own URL, Next decided that rewrite was
# EXTERNAL (it compares against {protocol}://{HOSTNAME}:{port} while the proxy is handed a URL built from
# localhost, and the two differ whenever the server is bound to 0.0.0.0), and proxied it back to itself
# until the socket dropped (runtime check of main 7665cabe, PR #1126). Nothing in CI started the built
# server with a core that answers 404, so nothing could see it, and a Next upgrade that changes how that
# comparison is made would bring the hang back silently.
#
# WHAT IT DOES. Needs `npm run build` done in frontend/ (output: standalone). For each launch mode it
# starts a stub core that answers 404 to every /public/shops/<slug> (so the gate has a verdict to act on),
# starts the frontend against it, and asks for /shop/<unknown> as a document request:
#   standalone  node server.js with HOSTNAME=0.0.0.0 (the image build file, compose and k8s)
#   next-start  `next start -p <port>` with HOSTNAME exported to an unrelated value (any container)
# with NEXTAUTH_URL set to a public origin (Auth.js rewrites the proxy's request URL to it, so the proxy must not
# fetch itself there), each plain and with X-Forwarded-Proto: https (a TLS-terminating ingress). Every probe must answer 404
# within 3 s with the "Kitchen not found" screen. Control (an instrument that can fail): the same probe
# with the stub core DOWN must still answer within the budget, 200 (fail open), so a 404 above can only
# have come from the gate's verdict, and a slow server cannot read as a pass.
#
# Exit codes: 0 = every probe passed, 1 = a probe failed, 2 = VOID (no build, no node, a server that never
# came up). Never 0 on a VOID.
#
# Falsification: run it against a tree whose middleware.ts is the pre-#1126 one
# (`NextResponse.rewrite(req.nextUrl, { status: 404 ... })`): the standalone probes time out and it exits 1.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FE="${FRONTEND_DIR:-$ROOT/frontend}"
BUDGET_MS="${UNKNOWN_KITCHEN_BUDGET_MS:-3000}"
SLUG="no-such-kitchen-$$-$RANDOM"
NODE="${NODE_BIN:-node}"

void() { echo "VOID: $*" >&2; cleanup; exit 2; }
PIDS=()
TMP=""
cleanup() {
	for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done
	[ -n "$TMP" ] && rm -rf "$TMP"
	return 0
}
trap cleanup EXIT

command -v "$NODE" >/dev/null 2>&1 || void "node is not on PATH"
[ -d "$FE/.next" ] || void "$FE/.next does not exist: run 'npm run build' in frontend/ first"
SA="$FE/.next/standalone"
[ -d "$SA" ] || void "$SA does not exist: next.config.mjs must keep output: 'standalone'"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/unknown-kitchen-404.XXXXXXXX")" || void "mktemp failed"

free_port() { "$NODE" -e 'const s=require("net").createServer().listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})'; }

# A copy of the standalone output with its static assets, so the build tree stays untouched.
cp -r "$SA" "$TMP/sa" || void "cannot copy the standalone output"
SERVER_JS="$TMP/sa/server.js"; SERVER_DIR="$TMP/sa"
[ -f "$SERVER_JS" ] || { SERVER_JS="$(find "$TMP/sa" -name server.js -not -path '*/node_modules/*' | head -1)"; SERVER_DIR="$(dirname "$SERVER_JS")"; }
[ -f "$SERVER_JS" ] || void "no server.js in the standalone output"
mkdir -p "$SERVER_DIR/.next"; rm -rf "$SERVER_DIR/.next/static"; cp -r "$FE/.next/static" "$SERVER_DIR/.next/static"
[ -d "$FE/public" ] && { rm -rf "$SERVER_DIR/public"; cp -r "$FE/public" "$SERVER_DIR/public"; }

# Stub core: 404 to every /public/shops/<slug>, 200 to anything else.
cat > "$TMP/stub.js" <<'JS'
const http = require("http")
http.createServer((req, res) => {
  const missing = /^\/public\/shops\/[^/]+\/?$/.test(req.url.split("?")[0])
  res.writeHead(missing ? 404 : 200, { "content-type": "application/json" })
  res.end(missing ? '{"status":404}' : "{}")
}).listen(Number(process.argv[2]), "127.0.0.1")
JS

# probe URL [header] -> "status ms marker csp"
cat > "$TMP/probe.js" <<'JS'
const [url, hdr] = process.argv.slice(2)
const headers = {}
if (hdr) { const i = hdr.indexOf(":"); headers[hdr.slice(0, i)] = hdr.slice(i + 1).trim() }
const t0 = Date.now()
fetch(url, { headers, redirect: "manual", signal: AbortSignal.timeout(35000) })
  .then(async (r) => { const b = await r.text(); console.log(r.status, Date.now() - t0, b.includes("Kitchen not found") ? "marker" : "no-marker", r.headers.get("content-security-policy") ? "csp" : "no-csp") })
  .catch((e) => console.log("ERR", Date.now() - t0, String(e.cause?.code || e.message)))
JS

STUB_PORT="$(free_port)" || void "no free port"
"$NODE" "$TMP/stub.js" "$STUB_PORT" & STUB_PID=$!; PIDS+=("$STUB_PID")

FE_ENV=(NEXT_PUBLIC_API_URL="http://127.0.0.1:$STUB_PORT" NEXTAUTH_URL="https://app.example.test" NEXTAUTH_SECRET=ci-placeholder-not-a-real-secret
	CORE_API_INTERNAL_URL="http://127.0.0.1:$STUB_PORT" NEXT_TELEMETRY_DISABLED=1 NODE_ENV=production
	# auth() wraps the proxy and throws without a Keycloak provider config; a throwing proxy lets every request
	# through unchecked (200), which this gate reports as a failure rather than a pass.
	KEYCLOAK_ISSUER="http://127.0.0.1:1/realms/ci" KEYCLOAK_CLIENT_ID=ci KEYCLOAK_CLIENT_SECRET=ci-placeholder-not-a-secret) # gitleaks:allow

FAILS=0
start_server() { # MODE PORT -> sets SERVER_PID
	local mode=$1 port=$2
	case "$mode" in
		standalone) env "${FE_ENV[@]}" HOSTNAME=0.0.0.0 PORT="$port" "$NODE" "$SERVER_JS" >"$TMP/$mode.log" 2>&1 & ;;
		next-start) (cd "$FE" && env "${FE_ENV[@]}" HOSTNAME=some-container-id "$NODE" node_modules/next/dist/bin/next start -p "$port" >"$TMP/$mode.log" 2>&1) & ;;
	esac
	SERVER_PID=$!; PIDS+=("$SERVER_PID")
	local i=0
	while [ $i -lt 90 ]; do
		out="$("$NODE" "$TMP/probe.js" "http://127.0.0.1:$port/robots.txt" 2>/dev/null)"
		case "$out" in 200*) return 0 ;; esac
		sleep 1; i=$((i + 1))
	done
	echo "--- $mode log ---" >&2; tail -20 "$TMP/$mode.log" >&2
	return 1
}

check() { # LABEL URL HEADER WANT_STATUS WANT_MARKER (marker=yes also requires a CSP header)
	local label=$1 url=$2 hdr=$3 want=$4 marker=$5 out status ms mark
	out="$("$NODE" "$TMP/probe.js" "$url" "$hdr" 2>&1)"
	status=${out%% *}; rest=${out#* }; ms=${rest%% *}; rest=${rest#* }; mark=${rest%% *}; csp=${rest#* }
	if [ "$status" = "$want" ] && [ "$ms" -lt "$BUDGET_MS" ] 2>/dev/null && { [ "$marker" = no ] || { [ "$mark" = marker ] && [ "$csp" = csp ]; }; }; then
		echo "PASS  $label: $status in ${ms} ms"
	else
		echo "FAIL  $label: got '$out' (want $want in < ${BUDGET_MS} ms${marker:+, marker=$marker})"; FAILS=$((FAILS + 1))
	fi
}

for MODE in standalone next-start; do
	PORT="$(free_port)" || void "no free port"
	start_server "$MODE" "$PORT" || void "$MODE server did not come up"
	U="http://127.0.0.1:$PORT/shop/$SLUG"
	check "$MODE: unknown kitchen" "$U" "" 404 yes
	check "$MODE: unknown kitchen, X-Forwarded-Proto https" "$U" "X-Forwarded-Proto: https" 404 yes
	check "$MODE: unknown kitchen, public Host + X-Forwarded-Host" "$U" "X-Forwarded-Host: app.example.test" 404 yes
	# CONTROL: with core down the gate has no verdict and must fail open, fast, with the page (200).
	kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null || true
	check "$MODE: control, core down fails open" "$U" "" 200 no
	"$NODE" "$TMP/stub.js" "$STUB_PORT" & STUB_PID=$!; PIDS+=("$STUB_PID")
	kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null
done

if [ "$FAILS" -gt 0 ]; then echo "FAIL: $FAILS probe(s) failed"; exit 1; fi
echo "PASS: unknown /shop/<slug> answers 404 inside ${BUDGET_MS} ms in every launch mode"
