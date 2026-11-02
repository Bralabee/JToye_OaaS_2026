// Stub core-java for quick 261102-vwa. Records what each upstream read carried.
// Usage: node stub-core.mjs <port> <log.jsonl>
// One JSON line per request: {method,url,xff,names}. /__mark/<case> logs a mark line.
import http from "node:http"
import fs from "node:fs"

const port = Number(process.argv[2])
const logPath = process.argv[3]
if (!port || !logPath) {
  console.error("usage: node stub-core.mjs <port> <log.jsonl>")
  process.exit(2)
}
fs.writeFileSync(logPath, "")
const PAGE = JSON.stringify({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 0 })

const server = http.createServer((req, res) => {
  const url = req.url ?? ""
  const mark = url.match(/^\/__mark\/([A-Za-z0-9_-]+)$/)
  // Drain the body so keep-alive POSTs complete.
  req.resume()
  req.on("end", () => {
    if (mark) {
      fs.appendFileSync(logPath, JSON.stringify({ mark: mark[1] }) + "\n")
      res.writeHead(204).end()
      return
    }
    const xffRaw = req.headers["x-forwarded-for"]
    const line = {
      method: req.method,
      url,
      xff: xffRaw === undefined ? null : Array.isArray(xffRaw) ? xffRaw.join(", ") : xffRaw,
      names: Object.keys(req.headers).sort(),
    }
    fs.appendFileSync(logPath, JSON.stringify(line) + "\n")
    const json = (status, body) => {
      res.writeHead(status, { "content-type": "application/json" })
      res.end(body)
    }
    const path = url.split("?")[0]
    if (path === "/public/featured-shops") return json(200, "[]")
    if (path === "/public/shops" || path === "/public/orders/mine") return json(200, PAGE)
    return json(404, JSON.stringify({ title: "Not Found" }))
  })
})
server.listen(port, "127.0.0.1", () => console.log(`stub-core listening on 127.0.0.1:${port}`))
