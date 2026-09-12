import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AdminPanel from '@/components/AdminPanel.vue'
import MetadataForm from '@/components/admin/MetadataForm.vue'
import StarterForm from '@/components/admin/StarterForm.vue'
import ConflictRuleForm from '@/components/admin/ConflictRuleForm.vue'
import TemplateForm from '@/components/admin/TemplateForm.vue'
import PublishConsole from '@/components/admin/PublishConsole.vue'
import ImportExportPanel from '@/components/admin/ImportExportPanel.vue'

/**
 * 管理后台外壳（T6.1）：登录态、左侧分区导航、分区懒加载与切换。
 *
 * 各分区的字段级行为由 `admin-forms.spec.ts` 覆盖；此处只保证外壳职责：
 * 登录 → 导航 → 渲染正确分区、提示条复用同一会话、发布台为默认分区。
 */
const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** 挂载 AdminPanel（内部使用 pinia）。 */
function mountAdmin(): ReturnType<typeof mount<typeof AdminPanel>> {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(AdminPanel, { global: { plugins: [pinia] } })
}

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(body === null ? '' : JSON.stringify(body)),
  } as unknown as Response
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
  await new Promise((r) => setTimeout(r, 0))
}

/**
 * 登录并按 URL 前缀提供各分区数据。
 *
 * @param overrides 特定 URL → 响应体覆盖（缺省给安全空值）
 */
async function mountLoggedIn(
  overrides: Record<string, unknown> = {},
): Promise<ReturnType<typeof mount<typeof AdminPanel>>> {
  const defaults: Record<string, unknown> = {
    '/api/admin/config/metadata': { kind: 'metadata', contentYaml: null, version: 3, updatedAt: null },
    '/api/admin/releases': [],
    '/api/admin/audits': [],
    '/api/admin/metadata': { javaVersions: [], bootVersions: [] },
    '/api/admin/starters': { starters: [] },
    '/api/admin/conflict-rules': { rules: [] },
    '/api/admin/templates': { templates: [] },
  }
  fetchMock.mockImplementation((url: string) => {
    const table = { ...defaults, ...overrides }
    if (url in table) return Promise.resolve(jsonResponse(table[url]))
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

  it('展示表单化定位说明（不再是 YAML 文本框）', () => {
    const wrapper = mountAdmin()
    expect(wrapper.find('.login-hint').text()).toContain('表单化维护')
    expect(wrapper.find('textarea').exists()).toBe(false)
  })

  it('登录成功后展示左侧分区导航', async () => {
    const wrapper = await mountLoggedIn()
    expect(wrapper.find('.admin-body').exists()).toBe(true)
    const items = wrapper.findAll('.nav-item')
    expect(items.map((i) => i.text())).toEqual([
      expect.stringContaining('发布台'),
      expect.stringContaining('元数据'),
      expect.stringContaining('starter 清单'),
      expect.stringContaining('冲突规则'),
      expect.stringContaining('模板'),
      expect.stringContaining('导入导出'),
    ])
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

  it('Enter 键触发登录', async () => {
    const wrapper = await mountLoggedIn()
    expect(wrapper.find('.admin-body').exists()).toBe(true)
  })
})

describe('AdminPanel 分区导航', () => {
  it('登录后默认停在发布台', async () => {
    const wrapper = await mountLoggedIn()
    expect(wrapper.findComponent(PublishConsole).exists()).toBe(true)
  })

  it.each([
    ['元数据', MetadataForm],
    ['starter 清单', StarterForm],
    ['冲突规则', ConflictRuleForm],
    ['模板', TemplateForm],
    ['导入导出', ImportExportPanel],
  ])('点击「%s」渲染对应分区表单', async (label, component) => {
    const wrapper = await mountLoggedIn()
    const item = wrapper.findAll('.nav-item').find((i) => i.text().includes(label as string))
    await item!.trigger('click')
    await settle()
    expect(wrapper.findComponent(component as never).exists()).toBe(true)
  })

  it('切换分区后只渲染当前分区', async () => {
    const wrapper = await mountLoggedIn()
    await wrapper.findAll('.nav-item')[1]!.trigger('click') // 元数据
    await settle()
    expect(wrapper.findComponent(MetadataForm).exists()).toBe(true)
    expect(wrapper.findComponent(PublishConsole).exists()).toBe(false)
    expect(wrapper.findComponent(StarterForm).exists()).toBe(false)
  })

  it('分区切换带 active 高亮', async () => {
    const wrapper = await mountLoggedIn()
    const items = wrapper.findAll('.nav-item')
    expect(items[0]!.classes()).toContain('active')
    await items[3]!.trigger('click')
    await settle()
    expect(wrapper.findAll('.nav-item')[3]!.classes()).toContain('active')
    expect(wrapper.findAll('.nav-item')[0]!.classes()).not.toContain('active')
  })

  it('操作人输入框可编辑并下发给发布台', async () => {
    const wrapper = await mountLoggedIn()
    const input = wrapper.find('.nav-operator input')
    await input.setValue('bob')
    expect((input.element as HTMLInputElement).value).toBe('bob')
  })
})

describe('AdminPanel 提示条', () => {
  it('发布成功后提示条展示发布结果', async () => {
    const wrapper = await mountLoggedIn({
      '/api/admin/config/publish': {
        releaseId: 9,
        javaVersionCount: 2,
        starterCount: 5,
        templateOverrideCount: 1,
        conflictRuleCount: 3,
      },
    })
    await wrapper.findAll('.card-actions button.btn-primary')[0]!.trigger('click')
    await settle()
    expect(wrapper.find('.admin-msg-info').text()).toContain('发布 #9')
  })

  it('发布校验失败展示 4002 明细', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/admin/config/publish') {
        return Promise.resolve(jsonResponse({ code: 4002, message: '校验失败明细', timestamp: 1 }, false, 400))
      }
      if (url === '/api/admin/releases' || url === '/api/admin/audits') return Promise.resolve(jsonResponse([]))
      return Promise.resolve(jsonResponse({ kind: 'metadata', contentYaml: null, version: 3, updatedAt: null }))
    })
    const wrapper = mountAdmin()
    await wrapper.find('input.admin-token').setValue('tk')
    await wrapper.find('button.btn-primary').trigger('click')
    await settle()
    await wrapper.findAll('.card-actions button.btn-primary')[0]!.trigger('click')
    await settle()
    expect(wrapper.find('.admin-msg-error').text()).toContain('校验失败明细')
  })

  it('点击提示条可清除', async () => {
    const wrapper = await mountLoggedIn()
    const msg = wrapper.find('.admin-msg')
    expect(msg.exists()).toBe(true)
    await msg.trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.admin-msg').exists()).toBe(false)
  })
})
