import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import StepStarters from '@/components/StepStarters.vue'
import { useWizardStore } from '@/stores/wizard'
import type { ConflictRule, Starter } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    listJavaVersions: vi.fn(),
    listBootVersions: vi.fn(),
    listStarters: vi.fn(),
    listConflictRules: vi.fn(),
    validate: vi.fn(),
    derivePackage: vi.fn(),
    generateProject: vi.fn(),
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

const starters: Starter[] = [
  { id: 'web', label: 'Spring Web', maintainer: 'official', category: 'web', description: 'Spring MVC' },
  { id: 'data-redis', label: 'Spring Data Redis', maintainer: 'official', category: 'data', description: 'Redis' },
  { id: 'xxx-redis', label: '内部 Redis', maintainer: 'internal', category: 'middleware', description: 'Redis 封装' },
]

const rules: ConflictRule[] = [
  { id: 'redis-vs-cache', type: 'pair', starters: ['web', 'data-redis'], message: '两者均注册缓存管理器' },
  { id: 'redis-family', type: 'group', starters: ['data-redis', 'xxx-redis', 'another'], message: 'Redis 封装组内互斥' },
]

function mountWithPinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(StepStarters)
}

describe('StepStarters 依赖冲突提示（M3.5）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('未命中规则时不渲染冲突提示', () => {
    const wrapper = mountWithPinia()
    const wizard = useWizardStore()
    wizard.starters = starters
    wizard.conflictRules = rules
    wizard.selectedStarters = new Set(['web'])
    return wrapper.vm.$nextTick().then(() => {
      expect(wrapper.find('.conflict-warnings').exists()).toBe(false)
    })
  })

  it('pair 规则两个成员同时选中时显示提示', async () => {
    const wrapper = mountWithPinia()
    const wizard = useWizardStore()
    wizard.starters = starters
    wizard.conflictRules = rules
    wizard.selectedStarters = new Set(['web', 'data-redis'])
    await wrapper.vm.$nextTick()
    const warnings = wrapper.findAll('.conflict-warning')
    expect(warnings).toHaveLength(1)
    expect(warnings[0].text()).toContain('两者均注册缓存管理器')
  })

  it('group 规则两个及以上成员命中时显示提示', async () => {
    const wrapper = mountWithPinia()
    const wizard = useWizardStore()
    wizard.starters = starters
    wizard.conflictRules = rules
    wizard.selectedStarters = new Set(['data-redis', 'xxx-redis'])
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.conflict-warning')).toHaveLength(1)
    expect(wrapper.text()).toContain('Redis 封装组内互斥')
  })

  it('取消选择后提示消失', async () => {
    const wrapper = mountWithPinia()
    const wizard = useWizardStore()
    wizard.starters = starters
    wizard.conflictRules = rules
    wizard.selectedStarters = new Set(['web', 'data-redis'])
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.conflict-warning')).toHaveLength(1)
    wizard.toggleStarter('data-redis')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.conflict-warnings').exists()).toBe(false)
  })

  it('conflictRules 区域带 alert 角色供可访问性', async () => {
    const wrapper = mountWithPinia()
    const wizard = useWizardStore()
    wizard.conflictRules = rules
    wizard.selectedStarters = new Set(['web', 'data-redis'])
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.conflict-warnings').attributes('role')).toBe('alert')
  })
})

describe('wizard store 冲突求值（M3.5）', () => {
  it('selectBoot 并行拉取冲突规则并回填', async () => {
    const { api } = await import('@/api/client')
    const mockApi = vi.mocked(api, true)
    mockApi.listStarters.mockResolvedValue(starters)
    mockApi.listConflictRules.mockResolvedValue(rules)
    const pinia = createPinia()
    setActivePinia(pinia)
    const wizard = useWizardStore()
    await wizard.selectBoot('3.2.5')
    expect(wizard.conflictRules).toEqual(rules)
    expect(wizard.triggeredConflicts).toEqual([])
  })

  it('triggeredConflicts 命中 pair 与 group 规则', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wizard = useWizardStore()
    wizard.conflictRules = rules
    wizard.selectedStarters = new Set(['web', 'data-redis', 'xxx-redis'])
    const ids = wizard.triggeredConflicts.map((c) => c.ruleId)
    expect(ids).toContain('redis-vs-cache')
    expect(ids).toContain('redis-family')
  })

  it('listConflictRules 失败时回退空规则（提示降级不影响选择）', async () => {
    const { api } = await import('@/api/client')
    const mockApi = vi.mocked(api, true)
    mockApi.listStarters.mockResolvedValue(starters)
    mockApi.listConflictRules.mockRejectedValue(new Error('network'))
    const pinia = createPinia()
    setActivePinia(pinia)
    const wizard = useWizardStore()
    await wizard.selectBoot('3.2.5')
    expect(wizard.conflictRules).toEqual([])
  })
})
