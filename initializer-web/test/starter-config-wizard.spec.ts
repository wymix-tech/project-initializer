import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import StarterConfigWizard from '@/components/StarterConfigWizard.vue'
import { useStarterConfigWizardStore } from '@/stores/starterConfigWizard'

/**
 * starter 配置向导组件冒烟测：步骤渲染、导航、字段校验阻断、预览渲染。
 */
const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockResolvedValue({
    ok: true,
    status: 200,
    json: () => Promise.resolve({ kind: 'starter', contentYaml: null, version: null, updatedAt: null }),
  } as unknown as Response)
  vi.stubGlobal('fetch', fetchMock)
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
  await new Promise((r) => setTimeout(r, 0))
}

function mountWizard() {
  // 复用 beforeEach 设置的 active pinia，确保测试内 store 与组件内 store 同一实例
  return mount(StarterConfigWizard, {
    props: { token: 'tk' },
  })
}

describe('StarterConfigWizard 步骤导航', () => {
  it('默认展示第一步基本信息', async () => {
    const wrapper = mountWizard()
    await settle()
    expect(wrapper.find('h3').text()).toContain('① 基本信息')
    // 下一步按钮在 id 为空时禁用
    const nextBtn = wrapper.findAll('button').find((b) => b.text().includes('下一步'))
    expect((nextBtn!.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('填写合法信息后可前进到第二步', async () => {
    const wrapper = mountWizard()
    await settle()
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'Spring Web'
    await wrapper.vm.$nextTick()
    const nextBtn = wrapper.findAll('button').find((b) => b.text().includes('下一步'))
    expect((nextBtn!.element as HTMLButtonElement).disabled).toBe(false)
    await nextBtn!.trigger('click')
    expect(wrapper.find('h3').text()).toContain('② 依赖坐标')
  })

  it('上一步可回退', async () => {
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 2
    const wrapper = mountWizard()
    await settle()
    const prevBtn = wrapper.findAll('button').find((b) => b.text().includes('上一步'))
    await prevBtn!.trigger('click')
    expect(useStarterConfigWizardStore().step).toBe(1)
  })
})

describe('StarterConfigWizard 预览', () => {
  it('第四步实时渲染 yml 预览', async () => {
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.category = 'web'
    wizard.form.maintainer = 'official'
    wizard.form.description = 'Spring Web'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }]
    wizard.step = 4
    const wrapper = mountWizard()
    await settle()
    const pre = wrapper.find('.preview-yaml')
    expect(pre.exists()).toBe(true)
    expect(pre.text()).toContain('id: web')
    expect(pre.text()).toContain('linkages: {}')
  })
})

describe('StarterConfigStepPreview 复制功能', () => {
  it('复制成功提示并失败降级', async () => {
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'Spring Web'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }]
    wizard.step = 4
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(globalThis, 'navigator', {
      value: { clipboard: { writeText } },
      writable: true,
      configurable: true,
    })
    const wrapper = mountWizard()
    await settle()
    const copyBtn = wrapper.findAll('button').find((b) => b.text().includes('复制'))
    await copyBtn!.trigger('click')
    await settle()
    expect(writeText).toHaveBeenCalled()
    expect(useStarterConfigWizardStore().messageType).toBe('info')
  })

  it('复制失败降级为错误提示', async () => {
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'Spring Web'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }]
    wizard.step = 4
    Object.defineProperty(globalThis, 'navigator', {
      value: { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } },
      writable: true,
      configurable: true,
    })
    const wrapper = mountWizard()
    await settle()
    const copyBtn = wrapper.findAll('button').find((b) => b.text().includes('复制'))
    await copyBtn!.trigger('click')
    await settle()
    expect(useStarterConfigWizardStore().messageType).toBe('error')
  })
})
