// Quick 261102-vwa: asserts over a stub-core log written by ssr-xff-probe.sh.
//   node assert-probe.mjs <log> before
//   node assert-probe.mjs <log> after [--cases a,b,...]
//   node assert-probe.mjs diff <before.jsonl> <after.jsonl> [--cases a,b,...]
// Exit 0 pass, 1 violation (offending line printed), 2 VOID (the probe exercised nothing).
import fs from "node:fs"

const CHAINS = {
  landing: "192.0.2.66, 198.51.100.1",
  shop: "198.51.100.2",
  slug: "192.0.2.66,198.51.100.3",
  cart: "198.51.100.4",
  sitemap: "198.51.100.5",
  orders: "198.51.100.6",
  "api-get": "198.51.100.7",
  "api-post": "198.51.100.8",
  none: null,
}
const ALL = Object.keys(CHAINS)

// Minimum upstream calls per case; a shortfall means the probe exercised nothing.
const MIN = {
  landing: (c) => c.length >= 2 && c.some((x) => x.url.split("?")[0] === "/public/featured-shops"),
  shop: (c) => c.length >= 1,
  slug: (c) => c.length >= 4,
  cart: (c) => c.length >= 1,
  sitemap: (c) => c.length >= 1,
  orders: (c) => c.filter((x) => x.url.startsWith("/public/orders/mine")).length >= 1,
  "api-get": (c) => c.filter((x) => x.url.startsWith("/public/orders/mine")).length >= 1,
  "api-post": (c) =>
    c.filter((x) => x.method === "POST" && x.url === "/public/shops/probe-kitchen/orders").length >= 1,
  none: (c) => c.length >= 1,
}

function load(path) {
  let raw
  try {
    raw = fs.readFileSync(path, "utf8")
  } catch (e) {
    console.error(`VOID: cannot read ${path}: ${e.message}`)
    process.exit(2)
  }
  const segs = {}
  let cur = null
  for (const l of raw.split("\n")) {
    if (!l.trim()) continue
    const o = JSON.parse(l)
    if (o.mark) {
      cur = o.mark === "end" ? null : o.mark
      if (cur) segs[cur] = []
      continue
    }
    if (cur) segs[cur].push(o)
  }
  return segs
}

function requireMinimums(segs, cases, label) {
  let ok = true
  for (const c of cases) {
    const calls = segs[c]
    if (!calls || !MIN[c](calls)) {
      console.error(`VOID (${label}): case ${c} has ${calls ? calls.length : "no"} upstream calls: ${JSON.stringify(calls ?? [])}`)
      ok = false
    }
  }
  if (!ok) process.exit(2)
}

function parseCases(args) {
  const i = args.indexOf("--cases")
  if (i < 0) return ALL
  const list = (args[i + 1] ?? "").split(",").filter(Boolean)
  for (const c of list) if (!(c in CHAINS)) { console.error(`VOID: unknown case ${c}`); process.exit(2) }
  if (!list.length) { console.error("VOID: empty --cases"); process.exit(2) }
  return list
}

const args = process.argv.slice(2)
let violations = 0
const bad = (msg, line) => { violations++; console.log(`VIOLATION: ${msg}\n  ${JSON.stringify(line)}`) }

if (args[0] === "diff") {
  const cases = parseCases(args)
  const b = load(args[1]), a = load(args[2])
  requireMinimums(b, cases, "before"); requireMinimums(a, cases, "after")
  let pairs = 0
  for (const c of cases) {
    const key = (x) => `${x.method} ${x.url}`
    const bm = new Map(); for (const x of b[c]) bm.set(key(x), x)
    for (const x of a[c]) {
      const y = bm.get(key(x))
      if (!y) { bad(`case ${c}: after call has no before counterpart`, x); continue }
      pairs++
      const added = x.names.filter((n) => !y.names.includes(n))
      const removed = y.names.filter((n) => !x.names.includes(n))
      if (c === "api-post") {
        if (added.length || removed.length || x.xff !== y.xff)
          bad(`case api-post must be identical; added=${JSON.stringify(added)} removed=${JSON.stringify(removed)} xff ${JSON.stringify(y.xff)} -> ${JSON.stringify(x.xff)}`, x)
      } else if (c === "none") {
        if (removed.length || added.length)
          bad(`case none: header names changed added=${JSON.stringify(added)} removed=${JSON.stringify(removed)}`, x)
      } else if (removed.length || JSON.stringify(added) !== JSON.stringify(["x-forwarded-for"])) {
        bad(`case ${c}: after-minus-before names must be exactly ["x-forwarded-for"]; added=${JSON.stringify(added)} removed=${JSON.stringify(removed)}`, x)
      }
    }
  }
  console.log(`diff: ${pairs} call pairs compared over ${cases.join(",")}; violations=${violations}`)
  process.exit(violations ? 1 : 0)
}

const [path, mode] = args
const segs = load(path)
if (mode === "before") {
  requireMinimums(segs, ALL, "before")
  let n = 0
  for (const c of ALL) for (const x of segs[c]) {
    n++
    if (c === "api-post") { if (x.xff !== CHAINS[c]) bad(`case api-post must carry ${JSON.stringify(CHAINS[c])} on base`, x) }
    else if (c === "none") { /* Next fills its own value for a header-less request; printed, not asserted */ console.log(`none: observed xff=${JSON.stringify(x.xff)} on ${x.url}`) }
    else if (x.xff !== null) bad(`case ${c} must carry no x-forwarded-for on base`, x)
  }
  console.log(`before: ${n} upstream calls checked; violations=${violations}`)
  process.exit(violations ? 1 : 0)
}
if (mode === "after") {
  const cases = parseCases(args)
  requireMinimums(segs, cases, "after")
  const sent = Object.values(CHAINS).filter(Boolean)
  let n = 0
  for (const c of cases) for (const x of segs[c]) {
    n++
    if (c === "none") {
      console.log(`none: observed xff=${JSON.stringify(x.xff)} on ${x.url}`)
      if (sent.includes(x.xff)) bad(`case none carries a chain the probe sent`, x)
    } else if (x.xff !== CHAINS[c]) bad(`case ${c} must carry exactly ${JSON.stringify(CHAINS[c])}`, x)
  }
  console.log(`after: ${n} upstream calls checked over ${cases.join(",")}; violations=${violations}`)
  process.exit(violations ? 1 : 0)
}
console.error("VOID: usage"); process.exit(2)
