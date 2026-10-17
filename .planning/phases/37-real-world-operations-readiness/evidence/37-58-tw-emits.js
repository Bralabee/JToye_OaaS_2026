// node tw-emits.js <frontendDir> <sourceFile> <needle>...
// Runs the project's Tailwind config with `content` narrowed to ONE source file and prints
// whether each needle occurs in the generated utilities CSS. Exit 0 = every needle emitted,
// 1 = at least one missing, 2 = VOID (tooling/config/empty-output failure).
// Why: the full build scans test files too (app/** and components/** include __tests__), so a
// test that spells the class literally makes the built stylesheet carry it whatever the source
// does. This asks the narrower question: does THIS file make Tailwind emit the rule?
const path = require("path")
const fs = require("fs")
const [frontendDir, sourceFile, ...needles] = process.argv.slice(2)
try {
  if (!frontendDir || !sourceFile || needles.length === 0) throw new Error("usage")
  if (!fs.existsSync(sourceFile)) throw new Error(`no such source file: ${sourceFile}`)
  const req = (m) => require(require.resolve(m, { paths: [frontendDir] }))
  const postcss = req("postcss")
  const tailwind = req("tailwindcss")
  const loadConfig = req("tailwindcss/loadConfig")
  const cfgPath = path.join(frontendDir, "tailwind.config.ts")
  const base = loadConfig(cfgPath)
  const cfg = { ...base, content: [path.resolve(sourceFile)] }
  postcss([tailwind(cfg)])
    .process("@tailwind utilities;", { from: undefined })
    .then((res) => {
      const css = res.css
      if (!css || css.length < 100) {
        console.error(`VOID: generated CSS is empty or tiny (${css ? css.length : 0} bytes)`)
        process.exit(2)
      }
      let missing = 0
      for (const n of needles) {
        const hit = css.includes(n)
        if (!hit) missing++
        console.log(`${hit ? "EMITTED" : "MISSING"} ${n}  (${css.length} bytes of CSS from ${path.basename(sourceFile)})`)
      }
      process.exit(missing ? 1 : 0)
    })
    .catch((e) => {
      console.error(`VOID: ${e.message}`)
      process.exit(2)
    })
} catch (e) {
  console.error(`VOID: ${e.message}`)
  process.exit(2)
}
