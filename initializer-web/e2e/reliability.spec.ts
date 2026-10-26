import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

const headers = { 'X-Admin-Token': process.env.E2E_ADMIN_TOKEN ?? 'e2e-local-token' }
let baseline: Buffer
const serverErrors = new WeakMap<Page, string[]>()
async function json(request: APIRequestContext, path: string) {
  const response = await request.get('/api/admin/' + path, { headers })
  expect(response.ok(), await response.text()).toBeTruthy()
  return response.json()
}
async function put(request: APIRequestContext, path: string, data: unknown) {
  const response = await request.put('/api/admin/' + path, { headers, data })
  expect(response.ok(), await response.text()).toBeTruthy()
}
async function publish(request: APIRequestContext) {
  const response = await request.post('/api/admin/config/publish', { headers, data: { operator: 'e2e-setup', remark: 'test fixture' } })
  expect(response.ok(), await response.text()).toBeTruthy()
}
async function login(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: '管理后台', exact: true }).click()
  await page.locator('.admin-token').fill(headers['X-Admin-Token'])
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.admin-nav')).toBeVisible()
}
async function project(page: Page, artifact = 'e2e-demo') {
  await page.goto('/')
  await page.getByRole('button', { name: /Java 17/ }).click()
  await expect(page.getByRole('button', { name: /Spring Boot 3.2.5/ })).toHaveClass(/selected/)
  await page.locator('#meta-group').fill('com.example')
  await page.locator('#meta-artifact').fill(artifact)
  await expect(page.locator('#meta-package')).not.toHaveValue('')
  await expect(page.getByRole('button', { name: '生成并下载' })).toBeEnabled()
}
function unzip(path: string): Record<string, string> {
  return JSON.parse(execFileSync('python3', ['-c', 'import json,zipfile,sys; z=zipfile.ZipFile(sys.argv[1]);print(json.dumps({n:z.read(n).decode() for n in z.namelist()}))', path], { encoding: 'utf8' }))
}
function makeZip(path: string, files: Record<string, string>) {
  execFileSync('python3', ['-c', 'import json,zipfile,sys; z=zipfile.ZipFile(sys.argv[1],"w");[z.writestr(k,v) for k,v in json.load(sys.stdin).items()];z.close()', path], { input: JSON.stringify(files) })
}
async function versions(request: APIRequestContext) {
  const result: Record<string, number> = {}
  for (const kind of ['metadata', 'starter', 'conflict-rule', 'template']) {
    result[kind] = (await json(request, 'config/' + kind)).version
  }
  return result
}

test.beforeAll(() => {
  baseline = readFileSync(new URL('../test-results/e2e-baseline.zip', import.meta.url))
  expect(baseline.length).toBeGreaterThan(0)
  expect(baseline.includes(Buffer.from('metadata.yml')), '启动基线必须包含已播种的元数据').toBe(true)
})
test.beforeEach(async ({ request, page }) => {
  const errors: string[] = []
  serverErrors.set(page, errors)
  page.on('response', (res) => {
    if (res.url().includes('/api/') && res.status() >= 500) {
      errors.push(`${res.request().method()} ${res.url()} ${res.status()}`)
    }
  })
  const response = await request.post('/api/admin/config/import?operator=e2e-reset', {
    headers, multipart: { file: { name: 'baseline.zip', mimeType: 'application/zip', buffer: baseline } },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  await publish(request)
})

test.afterEach(async ({ page }, info) => {
  expect(serverErrors.get(page) ?? [], '浏览器不应出现未处理的 API 500 响应').toEqual([])
  if (info.status !== info.expectedStatus) { await info.attach('page', { body: await page.content(), contentType: 'text/html' }) }
})

for (const tool of ['maven', 'gradle']) {
  test(`${tool} 真实生成下载并校验 ZIP 文件及启动说明`, async ({ page }, info) => {
    const errors: string[] = []
    page.on('pageerror', (e) => errors.push(e.message))
    await project(page)
    await page.locator('#build-tool-' + tool).check()
    const downloadEvent = page.waitForEvent('download')
    await page.getByRole('button', { name: '生成并下载' }).click()
    const download = await downloadEvent
    expect(download.suggestedFilename()).toBe('e2e-demo.zip')
    const path = info.outputPath('e2e-demo.zip')
    await download.saveAs(path)
    const files = unzip(path)
    expect(files['e2e-demo/README.md']).toContain(tool === 'maven' ? 'mvn spring-boot:run' : 'gradle bootRun')
    expect(Object.keys(files)).toContain('e2e-demo/' + (tool === 'maven' ? 'pom.xml' : 'build.gradle'))
    expect(files['e2e-demo/src/main/resources/application.yml']).toContain('e2e-demo')
    expect(Object.values(files).join('\n')).not.toContain('${artifact}')
    expect(errors).toEqual([])
    await page.screenshot({ path: info.outputPath('generation.png'), fullPage: true })
  })
}

test('发布备注与操作人通过 UI 写入真实发布历史', async ({ page, request }) => {
  await login(page)
  await page.locator('.nav-operator input').fill('e2e-reviewer')
  await page.locator('.remark input').fill('E2E 发布备注：中文与符号 #42')
  await page.getByRole('button', { name: '发布', exact: true }).click()
  await expect(page.locator('.admin-msg')).toContainText('发布 #')
  const releases = await json(request, 'releases')
  expect(releases[0]).toMatchObject({ releasedBy: 'e2e-reviewer', remark: 'E2E 发布备注：中文与符号 #42' })
})

test('缺核心模板时 UI 发布失败且已发布版本仍可生成', async ({ page, request }) => {
  const before = await json(request, 'releases')
  const response = await request.delete('/api/admin/templates/pom.xml.ftl?operator=e2e', { headers })
  expect(response.ok()).toBeTruthy()
  await login(page)
  await page.getByRole('button', { name: '发布', exact: true }).click()
  await expect(page.locator('.admin-msg')).toContainText('pom.xml.ftl')
  expect((await json(request, 'releases')).length).toBe(before.length)
  await project(page, 'still-active')
  const event = page.waitForEvent('download')
  await page.getByRole('button', { name: '生成并下载' }).click()
  expect((await event).suggestedFilename()).toBe('still-active.zip')
})

test('导入显示删除差异，拒绝过期基线，重新预览后才能应用', async ({ page, request }, info) => {
  await login(page)
  await page.locator('.nav-item').filter({ hasText: '导入导出' }).click()
  const event = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 ZIP 归档' }).click()
  const archive = info.outputPath('baseline.zip')
  await (await event).saveAs(archive)
  await put(request, 'templates/extra.txt', { name: 'extra.txt', content: 'remove-me', operator: 'e2e' })
  await page.locator('input[type=file]').setInputFiles(archive)
  await expect(page.getByRole('button', { name: '确认导入到草稿' })).toBeDisabled()
  const before = await versions(request)
  await page.getByRole('button', { name: '预览差异' }).click()
  await expect(page.locator('summary')).toContainText(['DELETE · templates/extra.txt'])
  expect(await versions(request)).toEqual(before)
  await put(request, 'conflict-rules', { rules: [], operator: 'concurrent-editor' })
  const changed = await versions(request)
  await page.getByRole('button', { name: '确认导入到草稿' }).click()
  await expect(page.locator('.admin-msg')).toContainText('草稿已变化')
  expect(await versions(request)).toEqual(changed)
  await page.getByRole('button', { name: '预览差异' }).click()
  await expect(page.getByRole('button', { name: '确认导入到草稿' })).toBeEnabled()
  await page.getByRole('button', { name: '确认导入到草稿' }).click()
  await expect(page.locator('.result')).toContainText('写入结构行')
  expect((await json(request, 'templates')).templates.some((t: { name: string }) => t.name === 'extra.txt')).toBeFalsy()
})

test('恶意 ZIP 路径在浏览器导入预览中被拒绝且不改草稿', async ({ page, request }, info) => {
  const archive = info.outputPath('unsafe.zip')
  makeZip(archive, { 'templates/../../escape.txt': 'unsafe' })
  await login(page)
  await page.locator('.nav-item').filter({ hasText: '导入导出' }).click()
  const before = await versions(request)
  await page.locator('input[type=file]').setInputFiles(archive)
  await page.getByRole('button', { name: '预览差异' }).click()
  await expect(page.locator('.admin-msg')).toContainText('路径穿越')
  expect(await versions(request)).toEqual(before)
  await expect(page.getByRole('button', { name: '确认导入到草稿' })).toBeDisabled()
})

test('多 Boot 同 ID 条目经真实向导保存后保留其他版本并使用当前操作人', async ({ page, request }) => {
  const view = await json(request, 'starters')
  const entry = (boot: string) => ({ id: 'shared', category: 'tool', maintainer: 'internal', description: 'shared ' + boot,
    required: false, hidden: false, configPackage: null, dependencies: [{ groupId: 'com.example', artifactId: 'shared', version: '1' }],
    requires: [], bootVersions: [boot], linkages: null, sort: 50 })
  await put(request, 'starters', { starters: [...view.starters, entry('2.7.18'), entry('3.2.5')], operator: 'e2e' })
  await login(page)
  await page.locator('.nav-operator input').fill('wizard-reviewer')
  await page.locator('.nav-item').filter({ hasText: 'starter 清单' }).click()
  await page.getByRole('button', { name: /新增向导/ }).click()
  await page.locator('#scw-id').fill('shared')
  await page.locator('#scw-desc').fill('edited boot3')
  await page.locator('#scw-boots').fill('3.2.5')
  await page.getByRole('button', { name: '下一步', exact: true }).click()
  await page.getByPlaceholder('groupId，如 org.springframework.boot').fill('com.example')
  await page.getByPlaceholder('artifactId，如 spring-boot-starter-web').fill('shared')
  await page.getByPlaceholder('version（可空）').fill('2')
  await page.getByRole('button', { name: '下一步', exact: true }).click()
  await page.getByRole('button', { name: '下一步', exact: true }).click()
  await page.getByRole('button', { name: '写入草稿', exact: true }).click()
  await expect(page.locator('.scw-msg')).toContainText('已写入')
  const result = (await json(request, 'starters')).starters.filter((s: { id: string }) => s.id === 'shared')
  expect(result).toHaveLength(2)
  expect(result.find((s: { bootVersions: string[] }) => s.bootVersions.includes('2.7.18')).description).toBe('shared 2.7.18')
  expect(result.find((s: { bootVersions: string[] }) => s.bootVersions.includes('3.2.5')).description).toBe('edited boot3')
  const audits = await json(request, 'audits')
  expect(audits[0].operator).toBe('wizard-reviewer')
  await page.getByRole('button', { name: '清单表', exact: true }).click()
  await expect.poll(() => page.getByPlaceholder('如 Spring MVC + 内嵌 Tomcat').evaluateAll(
    (inputs) => inputs.map((input) => (input as HTMLInputElement).value),
  )).toContain('edited boot3')
})

test('延迟的旧包名响应不能覆盖新输入或手工包名', async ({ page }) => {
  await project(page)
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  let requested!: () => void
  const seen = new Promise<void>((resolve) => { requested = resolve })
  await page.route('**/api/derive-package?**', async (route) => {
    const response = await route.fetch()
    if (new URL(route.request().url()).searchParams.get('artifact') === 'old') {
      requested()
      await gate
    }
    await route.fulfill({ response })
  })
  await page.locator('#meta-artifact').fill('old')
  await seen
  await expect(page.getByRole('button', { name: '生成并下载' })).toBeDisabled()
  await page.locator('#meta-artifact').fill('latest')
  await expect(page.locator('#meta-package')).toHaveValue('com.example.latest')
  await page.locator('#meta-package').fill('com.custom.manual')
  const response = page.waitForResponse((res) => res.url().includes('/api/derive-package?') && new URL(res.url()).searchParams.get('artifact') === 'old')
  release()
  await response
  await expect(page.locator('#meta-package')).toHaveValue('com.custom.manual')
  await expect(page.getByRole('button', { name: '生成并下载' })).toBeEnabled()
})

test('生成页和管理页在目标视口无横向溢出', async ({ page }, info) => {
  await project(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBeTruthy()
  await login(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBeTruthy()
  await page.screenshot({ path: info.outputPath('admin.png'), fullPage: true })
})

test('OR 依赖阻止无效提交，满足后 YAML 替换项目参数并保留运行参数', async ({ page, request }, info) => {
  const view = await json(request, 'starters')
  const entry = (id: string) => ({ id, category: 'tool', maintainer: 'internal', description: id,
    required: false, hidden: false, configPackage: null, dependencies: [{ groupId: 'com.example', artifactId: id, version: '1' }],
    requires: [] as string[][], bootVersions: [], linkages: null as unknown, sort: 40 })
  const alpha = entry('e2e-alpha')
  alpha.requires = [['e2e-beta', 'e2e-gamma']]
  alpha.linkages = { templates: [], ymlFragments: [{ source: 'fragments/e2e.yml', mergeStrategy: 'APPEND' }], abstractClasses: [] }
  await put(request, 'template?name=' + encodeURIComponent('fragments/e2e.yml'), {
    name: 'fragments/e2e.yml', content: 'demo:\n  artifact: ${artifact}\n  host: ${SERVICE_HOST:localhost}\n', operator: 'e2e',
  })
  await put(request, 'starters', { starters: [...view.starters, alpha, entry('e2e-beta'), entry('e2e-gamma')], operator: 'e2e' })
  await publish(request)
  await project(page)
  await page.locator('label.starter').filter({ has: page.locator('.starter-label', { hasText: /^e2e-alpha$/ }) }).locator('input').check()
  await expect(page.getByRole('button', { name: '生成并下载' })).toBeDisabled()
  await expect(page.locator('.requires-warnings')).toContainText('e2e-beta')
  await page.locator('label.starter').filter({ has: page.locator('.starter-label', { hasText: /^e2e-beta$/ }) }).locator('input').check()
  await expect(page.getByRole('button', { name: '生成并下载' })).toBeEnabled()
  const event = page.waitForEvent('download')
  await page.getByRole('button', { name: '生成并下载' }).click()
  const path = info.outputPath('yaml.zip')
  await (await event).saveAs(path)
  const files = unzip(path)
  expect(files['e2e-demo/src/main/resources/application.yml']).toContain('artifact: e2e-demo')
  expect(files['e2e-demo/src/main/resources/application.yml']).toContain('${SERVICE_HOST:localhost}')
})

test('自定义框架禁用 Gradle，直调 API 同样拒绝', async ({ page, request }) => {
  const metadata = await json(request, 'metadata')
  metadata.bootVersions.find((b: { id: string }) => b.id === '3.2.5').frameworkParent = {
    groupId: 'com.example', artifactId: 'framework-parent', version: '1.0',
  }
  await put(request, 'metadata', { ...metadata, operator: 'e2e' })
  await publish(request)
  await project(page)
  await expect(page.locator('#build-tool-gradle')).toBeDisabled()
  await expect(page.locator('#build-tool-maven')).toBeChecked()
  const response = await request.post('/api/generate', { data: {
    group: 'com.example', artifact: 'demo', packageName: 'com.example.demo', javaVersion: '17',
    bootVersion: '3.2.5', starters: [], buildTool: 'gradle',
  } })
  expect(response.status()).toBe(422)
  expect((await response.json()).code).toBe(1004)
  const wrongMethod = await request.get('/api/generate')
  expect(wrongMethod.status()).toBe(405)
  expect(wrongMethod.headers().allow).toContain('POST')
})

test('目录内 YAML 模板可在浏览器编辑和删除', async ({ page, request }) => {
  await login(page)
  await page.locator('.nav-item').filter({ hasText: '模板' }).click()
  const name = 'starters/xxx-redis/application-redis.yml'
  await page.locator('.tpl-list li').filter({ hasText: name }).click()
  await expect(page.locator('textarea.tpl-content')).toHaveValue(/\$\{REDIS_HOST:localhost\}/)
  const old = await page.locator('textarea.tpl-content').inputValue()
  await page.locator('textarea.tpl-content').fill(old + '\ne2eMarker: preserved\n')
  await page.getByRole('button', { name: '保存模板', exact: true }).click()
  await expect(page.locator('.admin-msg')).toContainText('模板已保存')
  const detail = await json(request, 'template?name=' + encodeURIComponent(name))
  expect(detail.content).toContain('e2eMarker: preserved')
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '删除', exact: true }).click()
  await expect(page.locator('.admin-msg')).toContainText('模板已删除')
  expect((await json(request, 'templates')).templates.some((t: { name: string }) => t.name === name)).toBeFalsy()
})

test('依赖已选筛选与键盘恢复不丢失选择', async ({ page }) => {
  await project(page)
  const item = page.locator('.starter').filter({ has: page.locator('.starter-id', { hasText: /^web$/ }) })
  await item.locator('input').check()
  const filter = page.getByRole('button', { name: '仅看已选', exact: true })
  await filter.focus()
  await page.keyboard.press('Space')
  await expect(filter).toHaveAttribute('aria-pressed', 'true')
  await expect(page.locator('.starter')).toHaveCount(1)
  await expect(item.locator('input')).toBeChecked()
  // The row disappears immediately in selected-only mode; assert the resulting empty state.
  await item.locator('input').click()
  await expect(page.locator('.starter-empty')).toContainText('取消「仅看已选」')
  await filter.click()
  await expect(page.locator('.starter')).toHaveCount(5)
  await expect(item.locator('input')).not.toBeChecked()
})

test('小屏生成与后台各分区不产生页面横向溢出，Enter 可登录', async ({ page }, info) => {
  for (const width of [768, 390, 320]) {
    await page.setViewportSize({ width, height: 844 })
    await project(page)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBeTruthy()
    await page.screenshot({ path: info.outputPath(`wizard-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: '管理后台', exact: true }).click()
    await page.locator('.admin-token').fill(headers['X-Admin-Token'])
    await page.locator('.admin-token').press('Enter')
    await expect(page.locator('.admin-nav')).toBeVisible()
    for (const label of ['发布台', '元数据', 'starter 清单', '冲突规则', '模板', '导入导出']) {
      await page.locator('.nav-item').filter({ hasText: label }).click()
      await expect(page.locator('.admin-main .card').first()).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `${width}px ${label}`).toBeTruthy()
    }
  }
})
