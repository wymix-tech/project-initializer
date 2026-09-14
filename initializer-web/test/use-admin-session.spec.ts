import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdminSession } from '@/composables/useAdminSession'

/**
 * 管理端会话单测：登录校验、提示条状态机、`run` 包裹器的成功/失败语义。
 */
const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response
}

describe('useAdminSession 登录', () => {
  it('初始态：未登录、无提示、非忙碌', () => {
    const s = useAdminSession()
    expect(s.token.value).toBe('')
    expect(s.loggedIn.value).toBe(false)
    expect(s.busy.value).toBe(false)
    expect(s.hasMessage.value).toBe(false)
  })

  it('可传入初始 token', () => {
    expect(useAdminSession('tk').token.value).toBe('tk')
  })

  it('登录成功置 loggedIn 并提示', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ kind: 'metadata', contentYaml: null, version: 1, updatedAt: null }))
    const s = useAdminSession('tk')
    await expect(s.login()).resolves.toBe(true)
    expect(s.loggedIn.value).toBe(true)
    expect(s.messageType.value).toBe('info')
    expect(s.message.value).toBe('登录成功')
  })

  it('登录失败（ApiError）展示后端明细且不置登录态', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ code: 4004, message: '未授权', timestamp: 1 }, false, 401))
    const s = useAdminSession('bad')
    await expect(s.login()).resolves.toBe(false)
    expect(s.loggedIn.value).toBe(false)
    expect(s.messageType.value).toBe('error')
    expect(s.message.value).toBe('未授权')
    expect(s.busy.value).toBe(false)
  })

  it('登录失败（非 ApiError）展示通用文案', async () => {
    fetchMock.mockRejectedValueOnce(new Error('network down'))
    const s = useAdminSession('tk')
    await expect(s.login()).resolves.toBe(false)
    expect(s.message.value).toBe('登录失败')
  })
})

describe('useAdminSession 提示条', () => {
  it('showText 写入类型与文本，clearText 清空', () => {
    const s = useAdminSession()
    s.showText('error', '明细')
    expect(s.hasMessage.value).toBe(true)
    expect(s.messageType.value).toBe('error')
    s.clearText()
    expect(s.hasMessage.value).toBe(false)
  })
})

describe('useAdminSession.run 包裹器', () => {
  it('成功时返回结果，可带成功提示', async () => {
    const s = useAdminSession()
    await expect(s.run(() => Promise.resolve(42), '已保存')).resolves.toBe(42)
    expect(s.message.value).toBe('已保存')
    expect(s.busy.value).toBe(false)
  })

  it('成功且未给成功提示时不改写提示条', async () => {
    const s = useAdminSession()
    s.showText('info', '旧提示')
    await expect(s.run(() => Promise.resolve('x'))).resolves.toBe('x')
    expect(s.message.value).toBe('旧提示')
  })

  it('失败时返回 null 并把后端明细写进提示条', async () => {
    const s = useAdminSession()
    const result = await s.run(async () => {
      const res = await Promise.resolve(
        jsonResponse({ code: 4002, message: '校验失败明细', timestamp: 1 }, false, 400),
      )
      if (!res.ok) {
        const { ApiError } = await import('@/api/client')
        throw new ApiError(await res.json())
      }
      return 'ok'
    }, '不该出现', '兜底文案')
    expect(result).toBeNull()
    expect(s.message.value).toBe('校验失败明细')
    expect(s.messageType.value).toBe('error')
  })

  it('失败且非 ApiError 时使用兜底文案', async () => {
    const s = useAdminSession()
    const result = await s.run(() => Promise.reject(new Error('boom')), undefined, '自定义兜底')
    expect(result).toBeNull()
    expect(s.message.value).toBe('自定义兜底')
  })

  it('失败时 busy 复位', async () => {
    const s = useAdminSession()
    await s.run(() => Promise.reject(new Error('x')))
    expect(s.busy.value).toBe(false)
  })
})
