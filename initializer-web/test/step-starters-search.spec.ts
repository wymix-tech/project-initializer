import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import StepStarters from '@/components/StepStarters.vue'
import { useWizardStore } from '@/stores/wizard'
import type { Starter } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    listJavaVersions: vi.fn(),
    listBootVersions: vi.fn(),
    listStarters: vi.fn(),
    listConflictRules: vi.fn().mockResolvedValue([]),
    validate: vi.fn(),
    derivePackage: vi.fn(),
    generateProject: vi.fn(),
    validateMeta: vi.fn(),
  },
  ApiError: class ApiError extends Error {
    code: number
    detail: string
    constructor(error: { code: number; message: string }) {
      super(`[${error.code}] ${error.message}`)
      this.code = error.code
      this.detail = error.message
    }
  },
}))

import { api } from '@/api/client'
const mockApi = vi.mocked(api, true)

const fetchMock = vi.fn()

const starters: Starter[] = [
  { id: 'web', label: 'Spring Web', maintainer: 'official', category: 'web', description: 'Spring MVC', version: null, required: false, requires: [], hidden: false },
  { id: 'xxx-redis', label: '内部 Redis', maintainer: 'internal', category: 'middleware', description: 'Redis 封装', version: null, required: false, requires: [], hidden: false },
]

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockImplementation(() =>
    Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) }),
  )
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.listStarters.mockResolvedValue([])
})

function mountWithPinia(component: Parameters<typeof mount>[0]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(component, { global: { plugins: [pinia] } })
}

describe('StepStarters 搜索与分类（M3.4）', () => {
  it('渲染搜索框与分类下拉', () => {
    const wrapper = mountWithPinia(StepStarters)
    expect(wrapper.find('.starter-search').exists()).toBe(true)
    expect(wrapper.find('.starter-category').exists()).toBe(true)
    expect(wrapper.findAll('.starter-category option')).toHaveLength(5)
  })

  it('渲染分类标签与描述字段', async () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    wizard.starters = starters
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.category-tag')).toHaveLength(2)
    expect(wrapper.text()).toContain('web')
    expect(wrapper.text()).toContain('middleware')
    expect(wrapper.findAll('.starter-label').map(node => node.text())).toEqual(['Spring Web', '内部 Redis'])
    expect(wrapper.findAll('.starter-id').map(node => node.text())).toEqual(['web', 'xxx-redis'])
    expect(wrapper.findAll('.starter-desc').map(node => node.text())).toEqual(['Spring MVC', 'Redis 封装'])
  })

  it('仅看已选保留选择状态，取消最后一项后提示可恢复全部列表', async () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    wizard.starters = starters
    wizard.registerStarters(starters)
    wizard.toggleStarter('web')
    await wrapper.vm.$nextTick()
    await wrapper.find('.selected-filter').trigger('click')
    expect(wrapper.find('.selected-filter').attributes('aria-pressed')).toBe('true')
    expect(wrapper.findAll('.starter-label').map(node => node.text())).toEqual(['Spring Web'])
    await wrapper.find('.starter input').setValue(false)
    expect(wrapper.find('.starter-empty').text()).toContain('取消「仅看已选」')
    await wrapper.find('.selected-filter').trigger('click')
    expect(wrapper.findAll('.starter')).toHaveLength(2)
    expect(wizard.selectedStarters.size).toBe(0)
  })

  it('卸载后不再触发待执行的搜索请求', async () => {
    vi.useFakeTimers()
    const wrapper = mountWithPinia(StepStarters)
    const spy = vi.spyOn(useWizardStore(), 'setSearch').mockResolvedValue(undefined)
    try {
      await wrapper.find('.starter-search').setValue('redis')
      wrapper.unmount()
      await vi.advanceTimersByTimeAsync(350)
      expect(spy).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
      spy.mockRestore()
    }
  })

  it('输入关键字防抖后触发条件检索', async () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    wizard.selectedBoot = '3.2.5'
    const spy = vi.spyOn(wizard, 'setSearch').mockResolvedValue(undefined)
    await wrapper.find('.starter-search').setValue('redis')
    expect(spy).not.toHaveBeenCalled()
    await new Promise((r) => setTimeout(r, 350))
    expect(spy).toHaveBeenCalledWith('redis', '')
    spy.mockRestore()
  })

  it('切换分类立即触发条件检索', async () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    wizard.selectedBoot = '3.2.5'
    const spy = vi.spyOn(wizard, 'setSearch').mockResolvedValue(undefined)
    await wrapper.find('.starter-category').setValue('data')
    expect(spy).toHaveBeenCalledWith('', 'data')
    spy.mockRestore()
  })

  it('空结果渲染占位文案', async () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    wizard.starters = []
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('没有匹配的依赖')
    expect(wrapper.find('.starter-list').exists()).toBe(false)
  })

  it('隐藏条目默认不渲染，被已选组件依赖时展示（M3.8）', async () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    const list: Starter[] = [
      { id: 'app', label: '应用', maintainer: 'internal', category: 'data', description: null, version: null, required: false, requires: [['ghost']], hidden: false },
      { id: 'ghost', label: '幽灵依赖', maintainer: 'internal', category: 'tool', description: null, version: null, required: false, requires: [], hidden: true },
    ]
    wizard.starters = list
    wizard.registerStarters(list)
    await wrapper.vm.$nextTick()
    const labels = () => wrapper.findAll('.starter-label').map((node) => node.text())
    expect(labels()).toEqual(['应用'])
    // 选中依赖方后隐藏条目被强制展示
    wizard.toggleStarter('app')
    await wrapper.vm.$nextTick()
    expect(labels().sort()).toEqual(['应用', '幽灵依赖'].sort())
  })
})

describe('wizard store 搜索条件（M3.4）', () => {
  it('setSearch 携带条件重新拉取 starter 清单', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    mockApi.listStarters.mockResolvedValue([])
    const wizard = useWizardStore()
    wizard.selectedBoot = '3.2.5'
    await wizard.setSearch('redis', 'middleware')
    expect(mockApi.listStarters).toHaveBeenCalledWith('3.2.5', 'redis', 'middleware')
    expect(wizard.starterKeyword).toBe('redis')
    expect(wizard.starterCategory).toBe('middleware')
  })

  it('setSearch 未选 Boot 版本时不发起请求', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    mockApi.listStarters.mockResolvedValue([])
    const wizard = useWizardStore()
    await wizard.setSearch('redis', '')
    expect(mockApi.listStarters).not.toHaveBeenCalled()
  })

  it('selectBoot 重置搜索条件', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    mockApi.listStarters.mockResolvedValue([])
    const wizard = useWizardStore()
    wizard.starterKeyword = 'old'
    wizard.starterCategory = 'web'
    await wizard.selectBoot('3.2.5')
    expect(wizard.starterKeyword).toBe('')
    expect(wizard.starterCategory).toBe('')
    expect(mockApi.listStarters).toHaveBeenCalledWith('3.2.5')
  })

  it('listStarters 请求 URL 携带 q/category 参数', async () => {
    // 走真实 client 模块验证 URL 拼接（vi.mock 只影响默认导出引用）
    const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
    await actual.api.listStarters('3.2.5', 'redis', 'middleware')
    expect(fetchMock).toHaveBeenCalledWith('/api/metadata/starters?boot=3.2.5&q=redis&category=middleware')
  })
})
