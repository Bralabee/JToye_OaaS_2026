#!/usr/bin/env bash
# usage: prodlcp.sh <label>   — build the worktree frontend as it is now, next start on :3437, LCP probe x5, stop.
set -uo pipefail
LABEL="$1"
WT=/home/sanmi/IdeaProjects/JToye_OaaS_2026/.claude/worktrees/agent-a0c738f005c12b2b9
S=/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/d0eb1018-7337-4baa-83f8-ca6c33e2337a/scratchpad
cd "$WT/frontend" || exit 2
npx next build > "$S/laneC-build-$LABEL.txt" 2>&1
brc=$?
echo "build rc=$brc"
[ "$brc" = 0 ] || exit 2
NEXTAUTH_URL=http://localhost:3437 NEXTAUTH_SECRET=devsecretdevsecretdevsecret AUTH_SECRET=devsecretdevsecretdevsecret \
  npx next start -p 3437 > "$S/laneC-start-$LABEL.log" 2>&1 &
SP=$!
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:3437/ || true)
  [ "$code" = 200 ] && break
  sleep 1
done
echo "server http=$code"
FRONTEND="$WT/frontend" node "$S/m/lcpprobe.cjs" http://localhost:3437 "$LABEL" "$S/laneC-lcp-prod-$LABEL.json" 5 | sed 's/_next\/image?url=%2Fmarketing%2Flanding%2F//'
prc=${PIPESTATUS[0]}
for p in $(ss -ltnp 2>/dev/null | grep ":3437 " | grep -o "pid=[0-9]*" | cut -d= -f2); do kill "$p"; done
kill "$SP" 2>/dev/null
wait "$SP" 2>/dev/null
echo "probe rc=$prc"
exit "$prc"
