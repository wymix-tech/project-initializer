import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
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
