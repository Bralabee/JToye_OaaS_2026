#!/usr/bin/env python3
# Quick 261102-vwa Task 3 break arms. Run from the repo root in the jtoye-ops env:
#   conda run -n jtoye-ops python break-arms.py <scratch-dir> <out.txt>
# clean -> each arm (scripted edit, three suites, failed test names) -> restore from HEAD -> hash check -> clean.
import json, subprocess, sys, os

SP = sys.argv[1]
FILES = ["frontend/lib/forwarded-for.ts", "frontend/lib/storefront-server.ts",
         "frontend/lib/customer-orders-server.ts", "frontend/app/api/customer-orders/route.ts"]
SUITES = ["lib/__tests__/storefront-server.test.ts", "lib/__tests__/forwarded-for.test.ts",
          "app/api/customer-orders/__tests__/route.test.ts"]
FF, SS, CO, RT = FILES
ONE = 'return chain ? { "X-Forwarded-For": chain } : {}'
ARMS = [
    ("B1", "forwardedForHeader always returns no header", FF,
     ONE, 'void chain; return {}',
     ["T1", "T2", "R1", "R3", "E1"]),
    ("B2", "forwardedForHeader invents 'unknown' when the source has none", FF,
     ONE, 'return { "X-Forwarded-For": chain || "unknown" }',
     ["T3", "R2", "R4"]),
    ("B3", "forwardedClientHeaders loses its try/catch", FF,
     '  try {\n    return forwardedForHeader(await headers())\n  } catch {\n    return {}\n  }',
     '  return forwardedForHeader(await headers())',
     ["T4", "E2"]),
    ("B4", "forwardedForHeader forwards only the left-most hop", FF,
     ONE, 'return chain ? { "X-Forwarded-For": chain.split(",")[0].trim() } : {}',
     ["T1", "R1", "R3", "E1"]),
    ("B5", "forwardedForHeader copies every header of its source", FF,
     ONE,
     'const all: Record<string, string> = {}\n  ;(source as unknown as Headers)?.forEach?.((v, k) => { all[k] = v })\n  return chain ? all : {}',
     ["T2", "R1", "R3", "E1"]),
    ("B6", "getJson passes only { headers }, dropping NO_STORE", SS,
     '      ...NO_STORE,\n      headers: await forwardedClientHeaders(),', '      headers: await forwardedClientHeaders(),',
     ["T1", "fetches every storefront call with cache: no-store"]),
    ("B7", "loadCustomerOrders sends only its token again", CO,
     'headers: { "X-Customer-Token": accessToken, ...(await forwardedClientHeaders()) },',
     'headers: { "X-Customer-Token": accessToken },',
     ["E1"]),
    ("B8", "the route GET drops the helper spread", RT,
     'headers: { "X-Customer-Token": access, ...forwardedForHeader(req.headers) },',
     'headers: { "X-Customer-Token": access },',
     ["R1", "E1"]),
]


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def hash_mismatches():
    bad = []
    for f in FILES:
        a = sh(["git", "hash-object", f]).stdout.strip()
        b = sh(["git", "rev-parse", f"HEAD:{f}"]).stdout.strip()
        if not a or a != b:
            bad.append(f)
    return bad


def run_suites(tag):
    out = os.path.join(SP, f"jest-{tag}.json")
    if os.path.exists(out):
        os.remove(out)
    p = sh(["npx", "jest", *SUITES, "--ci", "--json", f"--outputFile={out}"], cwd="frontend")
    if not os.path.exists(out):
        return p.returncode, None, p.stderr[-2000:]
    j = json.load(open(out))
    failed, total = [], 0
    for tr in j["testResults"]:
        for a in tr["assertionResults"]:
            total += 1
            if a["status"] != "passed":
                failed.append(a["title"])
    if j["numTotalTestSuites"] != 3:
        return p.returncode, None, f"expected 3 suites, got {j['numTotalTestSuites']}"
    return p.returncode, (failed, total, j["numFailedTests"], j["numPassedTests"]), ""


log = []


def emit(s=""):
    print(s, flush=True)
    log.append(s)


def finish(code):
    open(sys.argv[2], "w").write("\n".join(log) + "\n")
    sys.exit(code)


def clean(label):
    rc, res, err = run_suites(label)
    bad = hash_mismatches()
    if res is None:
        emit(f"{label}: VOID jest produced no usable json rc={rc} {err}")
        finish(2)
    failed, total, nf, np_ = res
    emit(f"{label}: jest rc={rc} passed={np_} failed={nf} total={total}; hash mismatches vs HEAD={bad or 'none'}")
    if rc != 0 or failed or bad:
        emit(f"{label}: NOT CLEAN {failed}")
        finish(1)


def matched(expect, failed):
    # Test titles start with their id ("T1 ...", "R3 ...", "E1 ...") or are matched by full title.
    return any(t.startswith(expect + " ") or (len(expect) > 3 and t.startswith(expect)) for t in failed)


clean("clean-before")
defects = 0
for (aid, desc, f, old, new, expect) in ARMS:
    src = open(f).read()
    if src.count(old) != 1:
        emit(f"{aid}: VOID anchor not found exactly once in {f}")
        finish(2)
    open(f, "w").write(src.replace(old, new))
    rc, res, err = run_suites(aid)
    r = sh(["git", "restore", "--source=HEAD", "--", f])
    bad = hash_mismatches()
    if res is None:
        emit(f"{aid}: VOID jest produced no usable json rc={rc} {err}")
        finish(2)
    failed, total, nf, np_ = res
    missing = [e for e in expect if not matched(e, failed)]
    emit(f"{aid} ({desc}) in {f}")
    emit(f"   jest rc={rc} failed={nf}/{total}")
    for t in failed:
        emit(f"   RED: {t}")
    emit(f"   expected red: {expect} -> {'ALL RED' if not missing else 'STAYED GREEN: ' + str(missing)}")
    emit(f"   restore rc={r.returncode}; hash mismatches vs HEAD after restore={bad or 'none'}")
    if missing:
        defects += 1
    if bad or r.returncode != 0:
        emit("   RESTORE FAILED")
        finish(1)
clean("clean-after")
emit(f"arms with an expected test that stayed green: {defects}")
finish(1 if defects else 0)
