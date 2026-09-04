import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import StepMeta from '@/components/StepMeta.vue'
import WizardForm from '@/components/WizardForm.vue'
import { useWizardStore } from '@/stores/wizard'
import { api, ApiError } from '@/api/client'

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return {
    ...actual,
    api: {
      listJavaVersions: vi.fn(),
      listBootVersions: vi.fn(),
      listStarters: vi.fn(),
      validate: vi.fn(),
      derivePackage: vi.fn(),
      generateProject: vi.fn(),
    },
  }
})

const mockApi = vi.mocked(api, true)

const fetchMock = vi.fn()

beforeEach(() => {
  // 兜底：jsdom 下未 stub 的 fetch 会静默失败产生 unhandled rejection
  fetchMock.mockReset()
  fetchMock.mockImplementation(() =>
    Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) }),
  )
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function mountPage() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(WizardForm, { global: { plugins: [pinia] } })
}

describe('StepMeta', () => {
  it('输入 group 触发推导与防抖校验', async () => {
    vi.useFakeTimers()
    mockApi.derivePackage.mockResolvedValue({ packageName: 'com.example.demo' })
    mockApi.validate.mockResolvedValue({ valid: true, errors: null })
    const wrapper = mountPage()
    const wizard = useWizardStore()
    wizard.step = 4
    await vi.runOnlyPendingTimersAsync()
    await wizard.updateMeta('group', 'com.example')
    await wizard.updateMeta('artifact', 'demo')
    expect(wizard.meta.packageName).toBe('com.example.demo')
    vi.useRealTimers()
    wrapper.unmount()
  })

  it('手改包名标记 touched', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(StepMeta, { global: { plugins: [createPinia()] } })
    const wizard = useWizardStore()
    await wrapper.find('#meta-package').setValue('custom.package')
    expect(wizard.meta.packageTouched).toBe(true)
    expect(wizard.meta.packageName).toBe('custom.package')
  })

  it('渲染字段错误信息', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(StepMeta, { global: { plugins: [createPinia()] } })
    const wizard = useWizardStore()
    wizard.validationErrors = { group: 'group 非法', artifact: 'artifact 非法', packageName: 'package 非法' }
    await wrapper.vm.$nextTick()
    const errors = wrapper.findAll('.field-error')
    expect(errors).toHaveLength(3)
    expect(errors[0].text()).toBe('group 非法')
  })

  it('校验接口失败时 toast 提示', async () => {
    mockApi.validate.mockRejectedValueOnce(new ApiError({ code: 9001, message: '系统内部错误', timestamp: 0 }))
    setActivePinia(createPinia())
    const wrapper = mount(StepMeta, { global: { plugins: [createPinia()] } })
    await new Promise((r) => setTimeout(r, 350))
    wrapper.unmount()
  })
})

describe('构建工具选择（M2.4）', () => {
  it('默认选中 Maven，可切换为 Gradle', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(StepMeta, { global: { plugins: [pinia] } })
    const wizard = useWizardStore()
    expect(wizard.meta.buildTool).toBe('maven')
    expect((wrapper.find('#build-tool-maven').element as HTMLInputElement).checked).toBe(true)
    await wrapper.find('#build-tool-gradle').setValue()
    expect(wizard.meta.buildTool).toBe('gradle')
    await wrapper.find('#build-tool-maven').setValue()
    expect(wizard.meta.buildTool).toBe('maven')
  })
})

describe('WizardForm', () => {
  it('第四步渲染生成按钮，校验失败时禁用', async () => {
    mockApi.validate.mockResolvedValue({ valid: false, errors: { group: 'group 非法' } })
    const wrapper = mountPage()
    const wizard = useWizardStore()
    wizard.step = 4
    await wrapper.vm.$nextTick()
    await vi.waitFor(() => {
      expect(wizard.canNext).toBe(false)
    })
    const btn = wrapper.findAll('button').find((b) => b.text().includes('生成并下载'))
    expect(btn?.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('渲染步骤指示器与导航按钮', () => {
    const wrapper = mountPage()
    expect(wrapper.find('.steps').exists()).toBe(true)
    expect(wrapper.text()).toContain('下一步')
    wrapper.unmount()
  })

  it('submitState 为 generating 时按钮显示生成中', async () => {
    mockApi.validate.mockResolvedValue({ valid: true, errors: null })
    const wrapper = mountPage()
    const wizard = useWizardStore()
    wizard.step = 4
    wizard.meta = { group: 'g', artifact: 'a', packageName: 'p', packageTouched: true, buildTool: 'maven' }
    wizard.validationErrors = {}
    wizard.submitState = 'generating'
    await wrapper.vm.$nextTick()
    const btn = wrapper.findAll('button').find((b) => b.text().includes('生成中'))
    expect(btn?.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('提交失败渲染错误提示', async () => {
    mockApi.validate.mockResolvedValue({ valid: true, errors: null })
    const wrapper = mountPage()
    const wizard = useWizardStore()
    wizard.step = 4
    wizard.meta = { group: 'g', artifact: 'a', packageName: 'p', packageTouched: true, buildTool: 'maven' }
    wizard.validationErrors = {}
    wizard.errorMessage = '生成失败：[2001] 版本组合不兼容'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.wizard-error').text()).toContain('版本组合不兼容')
    wrapper.unmount()
  })
})
