import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AdminPanel from '@/components/AdminPanel.vue'
import { adminApi } from '@/api/client'
import type { AdminDraftView } from '@/types/api'

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** 挂载 AdminPanel 时注入 pinia（组件使用 starterConfigWizard store）。 */
function mountAdmin(): ReturnType<typeof mount<typeof AdminPanel>> {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(AdminPanel, { global: { plugins: [pinia] } })
}

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return { ok, status, json: () => Promise.resolve(body) } as unknown as Response
}

function draftView(kind: string): AdminDraftView {
  return { kind, contentYaml: 'id: demo\n', version: 3, updatedAt: null }
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
  await new Promise((r) => setTimeout(r, 0))
}

async function mountLoggedIn(): Promise<ReturnType<typeof mount<typeof AdminPanel>>> {
  fetchMock.mockImplementation((url: string) => {
    if (url === '/api/admin/config/metadata') {
      return Promise.resolve(jsonResponse(draftView('metadata')))
    }
    if (url === '/api/admin/releases') return Promise.resolve(jsonResponse([]))
    if (url === '/api/admin/audits') return Promise.resolve(jsonResponse([]))
    return Promise.resolve(jsonResponse({}))
  })
  const wrapper = mountAdmin()
  await wrapper.find('input.admin-token').setValue('secret-token')
  await wrapper.find('button.btn-primary').trigger('click')
  await settle()
  return wrapper
}

describe('AdminPanel 登录', () => {
  it('token 为空时登录按钮禁用', () => {
    const wrapper = mountAdmin()
    const btn = wrapper.find('button.btn-primary').element as HTMLButtonElement
    expect(btn.disabled).toBe(true)
  })

  it('登录成功后展示管理面板并拉取草稿/历史/审计', async () => {
    const wrapper = await mountLoggedIn()
    expect(wrapper.find('.admin-body').exists()).toBe(true)
    expect((wrapper.find('textarea.admin-yaml').element as HTMLTextAreaElement).value).toBe('id: demo\n')
  })

  it('登录失败展示错误明细', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(jsonResponse({ code: 4004, message: '未授权', timestamp: 1 }, false, 404)),
    )
    const wrapper = mountAdmin()
    await wrapper.find('input.admin-token').setValue('bad')
    await wrapper.find('button.btn-primary').trigger('click')
    await settle()
    expect(wrapper.find('.admin-msg-error').text()).toContain('未授权')
  })
})

describe('AdminPanel 草稿编辑与发布', () => {
  it('保存草稿携带乐观锁 If-Match', async () => {
    const wrapper = await mountLoggedIn()
    fetchMock.mockClear()
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/api/admin/config/metadata' && init?.method === 'PUT') {
        expect((init.headers as Record<string, string>)['If-Match']).toBe('3')
        return Promise.resolve({ ok: true, status: 204 } as Response)
      }
      return Promise.resolve(jsonResponse(draftView('metadata')))
    })
    await wrapper.findAll('button.btn')[0].trigger('click')
    await settle()
    expect(fetchMock).toHaveBeenCalled()
  })

  it('发布成功展示发布结果', async () => {
    const wrapper = await mountLoggedIn()
    fetchMock.mockClear()
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/admin/config/publish') {
        return Promise.resolve(
          jsonResponse({
            releaseId: 9,
            javaVersionCount: 2,
            starterCount: 5,
            templateOverrideCount: 1,
            conflictRuleCount: 3,
          }),
        )
      }
      return Promise.resolve(jsonResponse([]))
    })
    await wrapper.findAll('button.btn')[1].trigger('click')
    await settle()
    expect(wrapper.find('.admin-msg-info').text()).toContain('发布 #9')
  })

  it('发布校验失败展示 4002 明细', async () => {
    const wrapper = await mountLoggedIn()
    fetchMock.mockClear()
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/admin/config/publish') {
        return Promise.resolve(
          jsonResponse({ code: 4002, message: '校验失败明细', timestamp: 1 }, false, 400),
        )
      }
      return Promise.resolve(jsonResponse([]))
    })
    await wrapper.findAll('button.btn')[1].trigger('click')
    await settle()
    expect(wrapper.find('.admin-msg-error').text()).toContain('校验失败明细')
  })

  it('回滚成功后刷新历史', async () => {
    const wrapper = await mountLoggedIn()
    fetchMock.mockClear()
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/admin/releases/7/rollback') {
        return Promise.resolve(
          jsonResponse({
            releaseId: 10,
            javaVersionCount: 1,
            starterCount: 1,
            templateOverrideCount: 0,
            conflictRuleCount: 0,
          }),
        )
      }
      if (url === '/api/admin/releases') return Promise.resolve(jsonResponse([]))
      if (url === '/api/admin/audits') return Promise.resolve(jsonResponse([]))
      return Promise.resolve(jsonResponse({}))
    })
    // 注入一条历史记录并等待渲染后触发回滚
    ;(
      wrapper.vm as unknown as {
        releases: { id: number; releasedBy: string | null; releasedAt: string | null; remark: string | null }[]
      }
    ).releases = [{ id: 7, releasedBy: null, releasedAt: null, remark: null }]
    await settle()
    await wrapper.find('.admin-releases button').trigger('click')
    await settle()
    expect(wrapper.find('.admin-msg-info').text()).toContain('#10')
  })
})

describe('adminApi 封装', () => {
  it('getDraft 携带 token 头', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(draftView('metadata')))
    await adminApi.getDraft('metadata', 'tk')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/config/metadata', {
      headers: { 'X-Admin-Token': 'tk', 'Content-Type': 'application/json' },
    })
  })

  it('putDraft 无版本时不带 If-Match', async () => {
    fetchMock.mockResolvedValueOnce({ ok: true, status: 204 } as Response)
    await adminApi.putDraft('starter', 'tk', 'id: x\n', null)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.headers).not.toHaveProperty('If-Match')
  })
})

describe('AdminPanel starter 向导模式', () => {
  it('选中 starter 草稿且切到向导模式时渲染 StarterConfigWizard', async () => {
    const wrapper = await mountLoggedIn()
    // 切到 starter kind
    wrapper.find('select.admin-kind').setValue('starter')
    await settle()
    // 切到向导模式
    const wizardBtn = wrapper.findAll('button.mode-btn').find((b) => b.text().includes('向导式'))
    expect(wizardBtn).toBeTruthy()
    await wizardBtn!.trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'StarterConfigWizard' }).exists()).toBe(true)
  })

  it('默认 yaml 模式不渲染向导', async () => {
    const wrapper = await mountLoggedIn()
    await wrapper.find('select.admin-kind').setValue('starter')
    await settle()
    expect(wrapper.findComponent({ name: 'StarterConfigWizard' }).exists()).toBe(false)
  })

  it('非 starter kind 不显示模式切换', async () => {
    const wrapper = await mountLoggedIn()
    // 默认 metadata kind
    expect(wrapper.find('.mode-switch').exists()).toBe(false)
    // 切到 conflict-rule 也不显示
    await wrapper.find('select.admin-kind').setValue('conflict-rule')
    await settle()
    expect(wrapper.find('.mode-switch').exists()).toBe(false)
  })
})
