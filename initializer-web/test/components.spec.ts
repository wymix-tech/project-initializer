import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import StepIndicator from '@/components/StepIndicator.vue'
import StepJava from '@/components/StepJava.vue'
import StepBoot from '@/components/StepBoot.vue'
import StepStarters from '@/components/StepStarters.vue'
import ToastHost from '@/components/ToastHost.vue'
import { useWizardStore } from '@/stores/wizard'
import { useToast } from '@/composables/useToast'
import type { BootVersion, JavaVersion, Starter } from '@/types/api'

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  // 组件内部未 stub api 时兜底：返回空清单，避免 jsdom fetch 静默失败
  fetchMock.mockImplementation(() =>
    Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) }),
  )
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function mountWithPinia(component: Parameters<typeof mount>[0], options = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(component, {
    global: { plugins: [pinia] },
    ...options,
  })
}

function storeOf(wrapper: { vm: unknown }): ReturnType<typeof useWizardStore> {
  return useWizardStore()
}

describe('StepIndicator', () => {
  it('渲染四步标签', () => {
    const wrapper = mountWithPinia(StepIndicator, { props: { current: 1 } })
    expect(wrapper.findAll('.step')).toHaveLength(4)
    expect(wrapper.text()).toContain('Java 版本')
    expect(wrapper.text()).toContain('元信息')
  })

  it('当前步与已完成步样式正确', () => {
    const wrapper = mountWithPinia(StepIndicator, { props: { current: 3 } })
    const steps = wrapper.findAll('.step')
    expect(steps[2].classes()).toContain('active')
    expect(steps[0].classes()).toContain('done')
    expect(steps[0].text()).toContain('✓')
  })
})

describe('StepJava', () => {
  const javaVersions: JavaVersion[] = [
    { id: '8', label: 'Java 8', deprecated: false, recommendedBootVersion: '2.7.18' },
    { id: '6', label: 'Java 6', deprecated: true, recommendedBootVersion: null },
  ]

  it('渲染版本清单，deprecated 禁用', async () => {
    const wrapper = mountWithPinia(StepJava)
    const wizard = useWizardStore()
    wizard.javaVersions = javaVersions
    await wrapper.vm.$nextTick()
    const buttons = wrapper.findAll('.option')
    expect(buttons).toHaveLength(2)
    expect(buttons[1].attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('推荐 Boot 2.7.18')
    expect(wrapper.text()).toContain('已废弃')
  })

  it('点击版本触发联动并前进', async () => {
    const wrapper = mountWithPinia(StepJava)
    const wizard = useWizardStore()
    wizard.javaVersions = javaVersions
    await wrapper.vm.$nextTick()
    await wrapper.findAll('.option')[0].trigger('click')
    await new Promise((r) => setTimeout(r, 50))
    expect(wizard.step).toBe(2)
  })

  it('空清单时显示占位文案', async () => {
    const wrapper = mountWithPinia(StepJava)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('暂无可用版本')
  })
})

describe('StepBoot', () => {
  const bootVersions: BootVersion[] = [{ id: '3.2.5', label: 'Spring Boot 3.2.5', official: true }]

  it('渲染 Boot 版本与官方徽标', async () => {
    const wrapper = mountWithPinia(StepBoot)
    const wizard = useWizardStore()
    wizard.selectedJava = '17'
    wizard.bootVersions = bootVersions
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Java 17')
    expect(wrapper.text()).toContain('官方')
  })

  it('点击版本触发联动并前进', async () => {
    const wrapper = mountWithPinia(StepBoot)
    const wizard = useWizardStore()
    wizard.selectedJava = '17'
    wizard.step = 2
    wizard.bootVersions = bootVersions
    await wrapper.vm.$nextTick()
    await wrapper.find('.option').trigger('click')
    await new Promise((r) => setTimeout(r, 50))
    expect(wizard.step).toBe(3)
  })

  it('空清单显示占位文案', async () => {
    const wrapper = mountWithPinia(StepBoot)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('该 Java 版本暂无兼容的 Boot 版本')
  })
})

describe('StepStarters', () => {
  const starters: Starter[] = [
    { id: 'web', label: 'Spring Web', maintainer: 'official' as const, category: 'web', description: 'Spring MVC' },
    { id: 'data-jpa', label: 'Spring Data JPA', maintainer: 'official' as const, category: 'data', description: 'JPA' },
  ]

  it('点击 checkbox 切换选中', async () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    wizard.starters = starters
    await wrapper.vm.$nextTick()
    await wrapper.findAll('input[type="checkbox"]')[0].setValue(true)
    expect(wizard.selectedStarters.has('web')).toBe(true)
    await wrapper.findAll('input[type="checkbox"]')[0].setValue(false)
    expect(wizard.selectedStarters.has('web')).toBe(false)
  })

  it('内部 starter 渲染维护方标签', async () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    wizard.starters = [{ id: 'xxx-redis', label: '内部 Redis', maintainer: 'internal' as const, category: 'middleware', description: 'Redis' }]
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('内部')
    expect(wrapper.findAll('.maintainer-tag')).toHaveLength(1)
  })

  it('选中项渲染 checked 样式与 id', async () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    wizard.starters = starters
    wizard.selectedStarters = new Set(['web'])
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.starter.checked')).toHaveLength(1)
    expect(wrapper.text()).toContain('web')
  })
})

describe('ToastHost', () => {
  it('渲染 toast 并点击可关闭', async () => {
    const wrapper = mountWithPinia(ToastHost)
    const toast = useToast()
    toast.push('error', '生成失败')
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.toast')).toHaveLength(1)
    await wrapper.find('.toast').trigger('click')
    expect(wrapper.findAll('.toast')).toHaveLength(0)
  })

  it('error 类型渲染对应样式', async () => {
    const wrapper = mountWithPinia(ToastHost)
    useToast().push('error', 'oops')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.toast').classes()).toContain('toast-error')
  })
})

describe('wizard store 与组件联动（冒烟）', () => {
  it('storeOf 返回同一实例', () => {
    const wrapper = mountWithPinia(StepStarters)
    const wizard = useWizardStore()
    expect(storeOf(wrapper)).toBe(wizard)
  })
})
