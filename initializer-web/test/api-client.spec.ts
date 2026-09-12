import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, adminApi, adminArchiveApi, adminStructuredApi, ApiError, fileNameOf } from '@/api/client'
import type { ErrorResponse } from '@/types/api'

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  fetchMock.mockRestore()
})

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response
}

function errorBody(code: number, message: string): ErrorResponse {
  return { code, message, timestamp: 1700000000000 }
}

describe('ApiError', () => {
  it('携带错误码与明细信息', () => {
    const err = new ApiError(errorBody(1001, '元信息校验失败'))
    expect(err.code).toBe(1001)
    expect(err.detail).toBe('元信息校验失败')
    expect(err.message).toBe('[1001] 元信息校验失败')
    expect(err.name).toBe('ApiError')
  })
})

describe('api.listJavaVersions', () => {
  it('GET 元数据接口并返回清单', async () => {
    const list = [{ id: '17', label: 'Java 17', deprecated: false, recommendedBootVersion: '3.2.5' }]
    fetchMock.mockResolvedValueOnce(jsonResponse(list))
    await expect(api.listJavaVersions()).resolves.toEqual(list)
    expect(fetchMock).toHaveBeenCalledWith('/api/metadata/java-versions')
  })

  it('非 2xx 时抛出后端明细的 ApiError', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(errorBody(9001, '系统内部错误'), false, 500))
    await expect(api.listJavaVersions()).rejects.toMatchObject({ code: 9001, detail: '系统内部错误' })
  })

  it('错误体非 JSON 时使用 HTTP 状态兜底', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 502,
      json: () => Promise.reject(new SyntaxError('bad json')),
    } as unknown as Response)
    await expect(api.listJavaVersions()).rejects.toMatchObject({ code: 502, detail: '请求失败（HTTP 502）' })
  })
})

describe('api.listBootVersions / listStarters', () => {
  it('带 java 查询参数请求 boot-versions', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([]))
    await api.listBootVersions('17')
    expect(fetchMock).toHaveBeenCalledWith('/api/metadata/boot-versions?java=17')
  })

  it('带 boot 查询参数请求 starters', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([]))
    await api.listStarters('3.2.5')
    expect(fetchMock).toHaveBeenCalledWith('/api/metadata/starters?boot=3.2.5')
  })
})

describe('api.validate', () => {
  it('POST 校验请求体并返回结果', async () => {
    const result = { valid: false, errors: { group: 'group 不能为空' } }
    fetchMock.mockResolvedValueOnce(jsonResponse(result))
    await expect(api.validate('', 'demo', 'p')).resolves.toEqual(result)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/validate')
    expect(init.method).toBe('POST')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(init.body).toBe(JSON.stringify({ group: '', artifact: 'demo', packageName: 'p' }))
  })
})

describe('api.derivePackage', () => {
  it('带 group/artifact 参数请求推导', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ packageName: 'com.example.demoapp' }))
    await api.derivePackage('com.example', 'demo-app')
    expect(fetchMock).toHaveBeenCalledWith('/api/derive-package?group=com.example&artifact=demo-app')
  })
})

describe('api.generateProject', () => {
  const request = {
    group: 'com.example',
    artifact: 'demo',
    packageName: 'com.example.demo',
    javaVersion: '17',
    bootVersion: '3.2.5',
    starters: ['web'],
  }

  function blobResponse(): Response {
    return {
      ok: true,
      status: 200,
      blob: () => Promise.resolve(new Blob(['zip-bytes'])),
    } as unknown as Response
  }

  it('成功时创建 blob 下载链接并清理', async () => {
    fetchMock.mockResolvedValueOnce(blobResponse())
    const click = vi.fn()
    const anchorInstance = { href: '', download: '', click, remove: vi.fn() }
    const createElementSpy = vi
      .spyOn(document, 'createElement')
      .mockReturnValue(anchorInstance as unknown as HTMLElement)
    const appendChildSpy = vi.spyOn(document.body, 'appendChild').mockImplementation(() => anchorInstance as never)
    const createObjectURL = vi.fn(() => 'blob:url')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })

    await api.generateProject(request)

    expect(createObjectURL).toHaveBeenCalledOnce()
    expect(anchorInstance.download).toBe('demo.zip')
    expect(click).toHaveBeenCalledOnce()
    expect(anchorInstance.remove).toHaveBeenCalledOnce()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:url')
    createElementSpy.mockRestore()
    appendChildSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('失败时抛出后端明细 ApiError', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(errorBody(1001, '元信息校验失败'), false, 422))
    await expect(api.generateProject(request)).rejects.toMatchObject({ code: 1001 })
  })

  it('失败且错误体非 JSON 时使用 HTTP 状态兜底', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: () => Promise.reject(new SyntaxError('bad')),
    } as unknown as Response)
    await expect(api.generateProject(request)).rejects.toMatchObject({
      code: 500,
      detail: '生成失败（HTTP 500）',
    })
  })
})

describe('adminApi admin 接口封装', () => {
  it('publish 发送 POST 请求携带 operator 和 token 头', async () => {
    const result = { releaseId: 1, javaVersionCount: 1, starterCount: 1, templateOverrideCount: 0, conflictRuleCount: 0 }
    fetchMock.mockResolvedValueOnce(jsonResponse(result))
    await adminApi.publish('mytk')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/config/publish', {
      method: 'POST',
      headers: { 'X-Admin-Token': 'mytk', 'Content-Type': 'application/json' },
      body: JSON.stringify({ operator: 'admin' }),
    })
  })

  it('publish 失败时抛出 ApiError 含后端明细', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(errorBody(4002, '校验失败'), false, 400))
    await expect(adminApi.publish('tk')).rejects.toMatchObject({ code: 4002, detail: '校验失败' })
  })

  it('listReleases 发送 GET 请求携带 token 头', async () => {
    const releases = [{ id: 1, releasedBy: 'admin', releasedAt: '2024-01-01', remark: 'v1' }]
    fetchMock.mockResolvedValueOnce(jsonResponse(releases))
    await adminApi.listReleases('rtk')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/releases', {
      headers: { 'X-Admin-Token': 'rtk', 'Content-Type': 'application/json' },
    })
  })

  it('listReleases 失败时抛出 ApiError', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(errorBody(403, '无权限'), false, 403))
    await expect(adminApi.listReleases('bad')).rejects.toMatchObject({ code: 403 })
  })

  it('rollback 发送 POST 到正确 URL 携带 operator 和 token', async () => {
    const result = { releaseId: 10, javaVersionCount: 1, starterCount: 1, templateOverrideCount: 0, conflictRuleCount: 0 }
    fetchMock.mockResolvedValueOnce(jsonResponse(result))
    await adminApi.rollback('rtk', 7)
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/releases/7/rollback', {
      method: 'POST',
      headers: { 'X-Admin-Token': 'rtk', 'Content-Type': 'application/json' },
      body: JSON.stringify({ operator: 'admin' }),
    })
  })

  it('rollback 失败时抛出 ApiError', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(errorBody(404, '不存在'), false, 404))
    await expect(adminApi.rollback('tk', 999)).rejects.toMatchObject({ code: 404 })
  })

  it('listAudits 发送 GET 请求携带 token 头', async () => {
    const audits = [{ id: 1, action: 'publish', targetKind: 'starter', operator: 'admin', detail: 'ok', createdAt: '2024' }]
    fetchMock.mockResolvedValueOnce(jsonResponse(audits))
    await adminApi.listAudits('atk')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/audits', {
      headers: { 'X-Admin-Token': 'atk', 'Content-Type': 'application/json' },
    })
  })

  it('listAudits 失败时抛出 ApiError', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(errorBody(500, 'server error'), false, 500))
    await expect(adminApi.listAudits('tk')).rejects.toMatchObject({ code: 500 })
  })

  it('putDraft 带版本时携带 If-Match 头', async () => {
    fetchMock.mockResolvedValueOnce({ ok: true, status: 204 } as unknown as Response)
    await adminApi.putDraft('starter', 'tk', 'id: x\n', 3)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.method).toBe('PUT')
    expect((init.headers as Record<string, string>)['If-Match']).toBe('3')
    expect(init.body).toBe(JSON.stringify({ contentYaml: 'id: x\n', operator: 'admin' }))
  })

  it('putDraft 失败时抛出 ApiError 含 HTTP 状态兜底', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 409,
      json: () => Promise.reject(new SyntaxError('bad')),
    } as unknown as Response)
    await expect(adminApi.putDraft('starter', 'tk', 'id: x\n', 1)).rejects.toMatchObject({
      code: 409,
      detail: '管理端请求失败（HTTP 409）',
    })
  })

  it('putDraft 失败时抛出 ApiError 含后端明细', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(errorBody(4002, 'starter 校验失败'), false, 400))
    await expect(adminApi.putDraft('starter', 'tk', 'bad', 1)).rejects.toMatchObject({
      code: 4002,
      detail: 'starter 校验失败',
    })
  })

  it('getDraft 失败时抛出 ApiError', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(errorBody(401, '未授权'), false, 401))
    await expect(adminApi.getDraft('starter', 'bad')).rejects.toMatchObject({ code: 401 })
  })
})

/** 同时提供 json / text 的成功响应（结构化端点走 text + JSON.parse）。 */
function textResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(body === null ? '' : JSON.stringify(body)),
  } as unknown as Response
}

describe('adminStructuredApi 结构化接口', () => {
  it('getMetadata 携带 token 头', async () => {
    const view = { javaVersions: [], bootVersions: [] }
    fetchMock.mockResolvedValueOnce(jsonResponse(view))
    await expect(adminStructuredApi.getMetadata('tk')).resolves.toEqual(view)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/admin/metadata')
    expect((init.headers as Record<string, string>)['X-Admin-Token']).toBe('tk')
  })

  it('putMetadata 带 If-Match 与 operator', async () => {
    fetchMock.mockResolvedValueOnce(textResponse({ kind: 'metadata', rows: 3, version: 4, summary: 's' }))
    await adminStructuredApi.putMetadata('tk', { javaVersions: [], bootVersions: [] }, 3)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/admin/metadata')
    expect(init.method).toBe('PUT')
    expect((init.headers as Record<string, string>)['If-Match']).toBe('3')
    expect(JSON.parse(init.body as string)).toEqual({ javaVersions: [], bootVersions: [], operator: 'admin' })
  })

  it('putMetadata 无版本时不携带 If-Match', async () => {
    fetchMock.mockResolvedValueOnce(textResponse({ kind: 'metadata', rows: 0, version: 1, summary: 's' }))
    await adminStructuredApi.putMetadata('tk', { javaVersions: [], bootVersions: [] }, null)
    const [, init] = fetchMock.mock.calls[0]
    expect((init.headers as Record<string, string>)['If-Match']).toBeUndefined()
  })

  it('getStarters / putStarters 走 /starters', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ starters: [] }))
    await expect(adminStructuredApi.getStarters('tk')).resolves.toEqual({ starters: [] })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/starters')

    fetchMock.mockResolvedValueOnce(textResponse({ kind: 'starter', rows: 1, version: 2, summary: 's' }))
    await adminStructuredApi.putStarters('tk', { starters: [] }, null)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/starters')
  })

  it('getConflictRules / putConflictRules 走 /conflict-rules', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ rules: [] }))
    await expect(adminStructuredApi.getConflictRules('tk')).resolves.toEqual({ rules: [] })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/conflict-rules')

    fetchMock.mockResolvedValueOnce(textResponse({ kind: 'conflict-rule', rows: 0, version: 2, summary: 's' }))
    await adminStructuredApi.putConflictRules('tk', { rules: [] }, 2)
    expect((fetchMock.mock.calls[1][1].headers as Record<string, string>)['If-Match']).toBe('2')
  })

  it('listTemplates / getTemplate 名称做 URL 编码', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ templates: [] }))
    await adminStructuredApi.listTemplates('tk')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/templates')

    fetchMock.mockResolvedValueOnce(jsonResponse({ name: 'a/b.ftl', content: 'x', updatedAt: null, sort: 0 }))
    await adminStructuredApi.getTemplate('tk', 'a/b.ftl')
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/templates/a%2Fb.ftl')
  })

  it('putTemplate 提交 name/content/sort', async () => {
    fetchMock.mockResolvedValueOnce(textResponse({ kind: 'template', rows: 1, version: 5, summary: 's' }))
    await adminStructuredApi.putTemplate('tk', 'pom.xml.ftl', '<x/>', 4, 7)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/admin/templates/pom.xml.ftl')
    expect(JSON.parse(init.body as string)).toEqual({
      name: 'pom.xml.ftl', content: '<x/>', operator: 'admin', sort: 7,
    })
  })

  it('deleteTemplate 走 DELETE 并带 operator 查询参数', async () => {
    fetchMock.mockResolvedValueOnce(textResponse({ kind: 'template', rows: 0, version: 6, summary: 's' }))
    await adminStructuredApi.deleteTemplate('tk', 'a.ftl', 5, 'bob')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/admin/templates/a.ftl?operator=bob')
    expect(init.method).toBe('DELETE')
    expect((init.headers as Record<string, string>)['If-Match']).toBe('5')
  })

  it('putTemplates 整体替换走 /templates', async () => {
    fetchMock.mockResolvedValueOnce(textResponse({ kind: 'template', rows: 2, version: 8, summary: 's' }))
    await adminStructuredApi.putTemplates('tk', { templates: [] }, null)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/templates')
  })

  it('readDraftVersion 取自 YAML 契约草稿 version', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ kind: 'metadata', contentYaml: null, version: 9, updatedAt: null }))
    await expect(adminStructuredApi.readDraftVersion('tk')).resolves.toBe(9)
  })

  it('readDraftVersion 草稿不存在时返回 null', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ kind: 'metadata', contentYaml: null, version: null, updatedAt: null }))
    await expect(adminStructuredApi.readDraftVersion('tk')).resolves.toBeNull()
  })

  it('结构化失败时抛 ApiError 含 4002 明细', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: () => Promise.resolve(errorBody(4002, '校验失败明细')),
    } as unknown as Response)
    await expect(adminStructuredApi.putStarters('tk', { starters: [] }, null)).rejects.toMatchObject({
      code: 4002, detail: '校验失败明细',
    })
  })

  it('结构化失败且错误体非 JSON 时用 HTTP 状态兜底', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: () => Promise.reject(new SyntaxError('bad')),
    } as unknown as Response)
    await expect(adminStructuredApi.getStarters('tk')).rejects.toMatchObject({
      code: 500, detail: '请求失败（HTTP 500）',
    })
  })
})

describe('adminArchiveApi 归档接口', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('importArchive 以 multipart 提交并返回导入摘要', async () => {
    const view = { rows: 12, versions: { metadata: 3 }, summary: '元数据 2 项' }
    fetchMock.mockResolvedValueOnce(textResponse(view))
    const file = new File(['zip'], 'a.zip', { type: 'application/zip' })
    await expect(adminArchiveApi.importArchive('tk', file, 4, 'bob')).resolves.toEqual(view)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/admin/config/import?operator=bob')
    expect(init.method).toBe('POST')
    expect(init.body).toBeInstanceOf(FormData)
    expect((init.headers as Record<string, string>)['If-Match']).toBe('4')
    // 文件字段名与后端 IMPORT_FILE_FIELD 一致
    expect((init.body as FormData).get('file')).toBeInstanceOf(File)
  })

  it('importArchive 无版本时不携带 If-Match', async () => {
    fetchMock.mockResolvedValueOnce(textResponse({ rows: 0, versions: {}, summary: '' }))
    await adminArchiveApi.importArchive('tk', new File(['x'], 'a.zip'))
    expect((fetchMock.mock.calls[0][1].headers as Record<string, string>)['If-Match']).toBeUndefined()
  })

  it('importArchive 失败抛 ApiError（4008 路径穿越明细）', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: () => Promise.resolve(errorBody(4008, '归档非法: 路径穿越')),
    } as unknown as Response)
    await expect(adminArchiveApi.importArchive('tk', new File(['x'], 'a.zip'))).rejects.toMatchObject({
      code: 4008, detail: '归档非法: 路径穿越',
    })
  })

  it('exportArchive 下载并返回附件名', async () => {
    const blob = new Blob(['zip'])
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      blob: () => Promise.resolve(blob),
      headers: { get: () => 'attachment; filename="initializer-config.zip"' },
    } as unknown as Response)
    URL.createObjectURL = vi.fn(() => 'blob:mock')
    URL.revokeObjectURL = vi.fn()
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    await expect(adminArchiveApi.exportArchive('tk')).resolves.toBe('initializer-config.zip')
    expect(anchorClick).toHaveBeenCalled()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock')
  })

  it('exportArchive 失败抛 ApiError', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: () => Promise.resolve(errorBody(4010, '导出失败')),
    } as unknown as Response)
    await expect(adminArchiveApi.exportArchive('tk')).rejects.toMatchObject({ code: 4010, detail: '导出失败' })
  })

  it('fileNameOf 无 Content-Disposition 时回退默认名', () => {
    expect(fileNameOf({ headers: { get: () => null } } as unknown as Response, 'fallback.zip')).toBe('fallback.zip')
    expect(
      fileNameOf({ headers: { get: () => 'attachment; filename=raw.zip' } } as unknown as Response, 'f.zip'),
    ).toBe('raw.zip')
  })
})

describe('adminApi.sync 制品库同步', () => {
  it('POST /sync 携带 operator 与 token', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ accepted: 3, summary: '同步完成' }))
    await expect(adminApi.sync('tk', 'bob')).resolves.toEqual({ accepted: 3, summary: '同步完成' })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/admin/sync')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ operator: 'bob' })
    expect((init.headers as Record<string, string>)['X-Admin-Token']).toBe('tk')
  })
})
