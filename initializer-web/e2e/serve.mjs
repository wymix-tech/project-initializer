import { spawn } from 'node:child_process'
import { createServer, request as proxyRequest } from 'node:http'
import { existsSync, createReadStream, mkdirSync, createWriteStream, writeFileSync } from 'node:fs'
import { resolve, extname, sep } from 'node:path'

// Keep the browser, HTTP proxy and backend in one process tree and network namespace.
const root = resolve(import.meta.dirname, '..')
const repo = resolve(root, '..')
const jar = process.env.E2E_BACKEND_JAR ?? resolve(repo, 'initializer-app/target/initializer-app-0.1.0-SNAPSHOT.jar')
if (!existsSync(jar) || !existsSync(resolve(root, 'dist/index.html'))) {
  throw new Error('先构建后端 jar 和前端 dist；参见 e2e/README.md')
}
const apiPort = Number(process.env.E2E_API_PORT ?? 18080)
const webPort = Number(process.env.E2E_WEB_PORT ?? 18173)
const token = process.env.E2E_ADMIN_TOKEN ?? 'e2e-local-token'
mkdirSync(resolve(root, 'test-results'), { recursive: true })
const log = createWriteStream(resolve(root, 'test-results/e2e-backend.log'))
const accessLog = createWriteStream(resolve(root, 'test-results/e2e-http.log'))
const backend = spawn(process.env.JAVA ?? 'java', ['-jar', jar, '--demo', `--server.port=${apiPort}`,
  '--spring.datasource.url=jdbc:h2:mem:browser-e2e;DB_CLOSE_DELAY=-1;MODE=LEGACY',
  `--initializer.admin.token=${token}`, '--initializer.sync.mock.enabled=false'], { cwd: repo })
backend.stdout.pipe(log)
backend.stderr.pipe(log)
let server
let closing = false
function stop() {
  if (closing) { return }
  closing = true
  server?.close()
  backend.kill('SIGTERM')
}
process.on('SIGTERM', stop)
process.on('SIGINT', stop)
backend.on('exit', (code) => {
  if (!closing) { console.error(`后端退出: ${code}；查看 test-results/e2e-backend.log`); process.exit(1) }
})
try {
  const deadline = Date.now() + 135000
  let ready = false
  while (Date.now() < deadline) {
    try {
      // Public metadata has a classpath fallback before demo seeding; wait for a committed release instead.
      const response = await fetch(`http://127.0.0.1:${apiPort}/api/admin/releases`, {
        headers: { 'X-Admin-Token': token },
      })
      ready = response.ok && (await response.json()).length > 0
      if (ready) { break }
    } catch { /* backend is still starting */ }
    await new Promise((done) => setTimeout(done, 250))
  }
  if (!ready) { throw new Error('后端启动超时；查看 test-results/e2e-backend.log') }
  const baseline = await fetch(`http://127.0.0.1:${apiPort}/api/admin/config/export`, {
    headers: { 'X-Admin-Token': token },
  })
  if (!baseline.ok) { throw new Error('无法获取 E2E 初始归档') }
  writeFileSync(resolve(root, 'test-results/e2e-baseline.zip'), Buffer.from(await baseline.arrayBuffer()))
  const dist = resolve(root, 'dist')
  server = createServer((req, res) => {
    if (req.url.startsWith('/api/')) {
      res.once('finish', () => accessLog.write(`${req.method} ${req.url} ${res.statusCode}\n`))
      const proxy = proxyRequest({ hostname: '127.0.0.1', port: apiPort, path: req.url, method: req.method,
        headers: { ...req.headers, host: `127.0.0.1:${apiPort}` } }, (upstream) => {
        res.writeHead(upstream.statusCode, upstream.headers)
        upstream.pipe(res)
      })
      proxy.on('error', (error) => { res.writeHead(502); res.end(error.message) })
      req.pipe(proxy)
      return
    }
    const path = resolve(dist, '.' + decodeURIComponent(new URL(req.url, 'http://localhost').pathname))
    if (path !== dist && !path.startsWith(dist + sep)) { res.writeHead(403); res.end(); return }
    const file = existsSync(path) && path !== dist ? path : resolve(dist, 'index.html')
    const type = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' }[extname(file)]
    res.setHeader('Content-Type', type ?? 'application/octet-stream')
    res.setHeader('Cache-Control', 'no-store')
    createReadStream(file).pipe(res)
  })
  server.listen(webPort, '127.0.0.1', () => console.log(`E2E ready: http://127.0.0.1:${webPort}`))
} catch (error) {
  console.error(error)
  stop()
  process.exitCode = 1
}
