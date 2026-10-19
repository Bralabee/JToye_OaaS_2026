# Capturing the kitchen display without a vendor password

`capture-operators.mjs kitchen` needs the dashboard, and the dashboard layout
refuses to render without a server-side session. The script signs nobody in (its
request firewall would abort the sign-in anyway), so mount the REAL kitchen page and
the REAL dashboard shell outside that gate, for the duration of one build:

```
cd frontend
mkdir app/capture-kitchen
cp scripts/marketing-captures/capture-kitchen-route/layout.tsx scripts/marketing-captures/capture-kitchen-route/page.tsx app/capture-kitchen/
npm run build
rm -r app/capture-kitchen            # never commit it, never deploy this build
CORE_API_INTERNAL_URL=http://127.0.0.1:1 npx next start -p 3203 &
node scripts/marketing-captures/capture-operators.mjs \
  --base-url http://localhost:3203 --kitchen-path /capture-kitchen kitchen
```

Nothing about the page differs from production except that gate: every API
read is answered by the fixtures in `capture-operators.mjs`, the session
endpoint answers a fictional owner, and no request leaves the machine. Rebuild
from a clean tree afterwards (the build above contains an ungated route).
