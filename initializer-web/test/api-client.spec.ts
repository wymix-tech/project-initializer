import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, adminApi, ApiError } from '@/api/client'
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
