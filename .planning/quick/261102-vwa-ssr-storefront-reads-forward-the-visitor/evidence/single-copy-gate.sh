#!/usr/bin/env bash
# Single-copy gate (quick 261102-vwa): code lines naming x-forwarded-for (case-insensitive) in the
# frontend's app/lib/components/hooks + middleware/proxy, tests and specs excluded, comment lines
# dropped. Every remaining line must be in lib/forwarded-for.ts. Usage: single-copy-gate.sh <frontend-dir>
# Exit 0 pass, 1 a second copy exists, 2 VOID (tooling failed or scope empty).
set -u
root=${1:?frontend dir}
targets=()
for d in app lib components hooks middleware.ts proxy.ts; do [ -e "$root/$d" ] && targets+=("$root/$d"); done
[ ${#targets[@]} -ge 3 ] || { echo "VOID: scope missing under $root" >&2; exit 2; }
# Positive control on the scope: the token exists somewhere in it (comments count), else VOID.
all=$(rg -uu -n -i --no-heading 'x-forwarded-for' -g '!**/__tests__/**' -g '!*.test.*' -g '!*.spec.*' -g '!**/node_modules/**' -g '!**/.next/**' "${targets[@]}"); rc=$?
echo "rg rc=$rc (0 = matches, 1 = none, 2 = error)"
[ "$rc" -eq 2 ] && { echo "VOID: rg error" >&2; exit 2; }
[ "$rc" -eq 1 ] && { echo "VOID: no line names the header at all; the scope or pattern is wrong" >&2; exit 2; }
code=$(awk -F: '{ line=$0; sub(/^[^:]*:[0-9]+:/, "", line); if (line ~ /^[[:space:]]*(\*|\/\/|\/\*)/) next; print }' <<<"$all")
echo "comment lines filtered: $(( $(wc -l <<<"$all") - $( [ -n "$code" ] && wc -l <<<"$code" || echo 0) ))"
echo "code lines naming the header:"
[ -n "$code" ] && printf '  %s\n' "$code"
outside=$(/usr/bin/grep -v '/lib/forwarded-for\.ts:' <<<"$code" | /usr/bin/grep -v '^$')
if [ -n "$outside" ]; then echo "FAIL: code outside lib/forwarded-for.ts names the header:"; printf '  %s\n' "$outside"; exit 1; fi
echo "PASS: every code line naming the header is in lib/forwarded-for.ts"
