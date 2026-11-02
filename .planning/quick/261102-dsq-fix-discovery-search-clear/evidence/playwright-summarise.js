const d = require(process.argv[2])
for (const r of d.runs) {
  console.log("== " + r.name, JSON.stringify(r.nativeCancel))
  for (const s of r.samples)
    console.log(
      s.step.padEnd(14), JSON.stringify(s.value).padEnd(16), (s.search || "-").padEnd(22),
      "skel=" + s.skeletonCards, "cards=" + s.cards, "none=" + s.noKitchens, "busy=" + s.busy,
      "err=" + s.loadError, "x=" + s.customClearButtons, "|", s.summary
    )
  console.log("timeline:", r.valueTimeline.map((v) => v.t + ":" + JSON.stringify(v.value) + v.search).join(" > "))
  console.log("requests:", r.requests.length)
}
for (const r of d.runs) {
  const tl = r.renderTimeline || []
  console.log(`-- ${r.name} render transitions (latency ${r.latencyMs}ms): ${tl.length}`)
  console.log("   " + tl.map((k) => `${k.kind}@${JSON.stringify(k.value)}`).join(" > "))
  const st = r.requests.map((q) => q.status)
  console.log(`   requests ${st.length}, statuses ${[...new Set(st)].join(",")}`)
}
