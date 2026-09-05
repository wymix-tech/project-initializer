import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api, ApiError } from '@/api/client'
import { STEP_JAVA, STEP_META, STEP_STARTERS, TOTAL_STEPS, useWizardStore } from '@/stores/wizard'
import type { BootVersion, JavaVersion, Starter, ValidationResult } from '@/types/api'

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    code: number
    detail: string
    constructor(error: { code: number; message: string }) {
      super(`[${error.code}] ${error.message}`)
      this.code = error.code
      this.detail = error.message
    }
  },
  api: {
    listJavaVersions: vi.fn(),
    listBootVersions: vi.fn(),
    listStarters: vi.fn(),
    listConflictRules: vi.fn().mockResolvedValue([]),
    validate: vi.fn(),
    derivePackage: vi.fn(),
    generateProject: vi.fn(),
  },
}))

const mockApi = vi.mocked(api, true)

const javaVersions: JavaVersion[] = [
  { id: '8', label: 'Java 8', deprecated: false, recommendedBootVersion: '2.7.18' },
  { id: '17', label: 'Java 17', deprecated: false, recommendedBootVersion: '3.2.5' },
]
const bootVersions: BootVersion[] = [
  { id: '3.2.5', label: '3.2.5', official: true },
]
const starters: Starter[] = [
  { id: 'web', label: 'Spring Web', maintainer: 'official' as const, category: 'web', description: 'Spring MVC' },
  { id: 'data-jpa', label: 'Spring Data JPA', maintainer: 'official' as const, category: 'data', description: 'JPA' },
]

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

function happyMocks(): void {
  mockApi.listJavaVersions.mockResolvedValue(javaVersions)
  mockApi.listBootVersions.mockResolvedValue(bootVersions)
  mockApi.listStarters.mockResolvedValue(starters)
  mockApi.derivePackage.mockResolvedValue({ packageName: 'com.example.demo' })
  mockApi.validate.mockResolvedValue({ valid: true, errors: null })
}

describe('版本联动', () => {
  it('loadJavaVersions 拉取清单', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.loadJavaVersions()
    expect(wizard.javaVersions).toEqual(javaVersions)
    expect(wizard.loading).toBe(false)
  })

  it('selectJava 联动拉取 Boot 版本并默认选中推荐版本', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.loadJavaVersions()
    await wizard.selectJava('17')
    expect(mockApi.listBootVersions).toHaveBeenCalledWith('17')
    expect(wizard.selectedBoot).toBe('3.2.5')
    expect(mockApi.listStarters).toHaveBeenCalledWith('3.2.5')
  })

  it('selectJava 推荐版本不在兼容清单时保持未选中', async () => {
    happyMocks()
    // Java 8 推荐版本 2.7.18，但后端返回的兼容清单不含该版本
    mockApi.listBootVersions.mockResolvedValueOnce([{ id: '3.2.5', label: '3.2.5', official: true }])
    const wizard = useWizardStore()
    await wizard.loadJavaVersions()
    await wizard.selectJava('8')
    expect(wizard.selectedBoot).toBe('')
    expect(wizard.bootVersions).toHaveLength(1)
    expect(mockApi.listStarters).not.toHaveBeenCalled()
  })

  it('selectJava 无推荐版本时不自动选中', async () => {
    happyMocks()
    mockApi.listBootVersions.mockResolvedValueOnce([{ id: '2.7.18', label: '2.7.18', official: true }])
    const wizard = useWizardStore()
    await wizard.loadJavaVersions()
    wizard.javaVersions = [{ id: '11', label: 'Java 11', deprecated: false, recommendedBootVersion: null }]
    await wizard.selectJava('11')
    expect(wizard.selectedBoot).toBe('')
  })

  it('selectJava 重置 Boot 选择与 starter', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.loadJavaVersions()
    await wizard.selectJava('17')
    wizard.toggleStarter('web')
    await wizard.selectJava('8')
    expect(wizard.selectedBoot).toBe('')
    expect(wizard.selectedStarters.size).toBe(0)
  })

  it('selectBoot 拉取 starter 清单并清空已选 starter', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.loadJavaVersions()
    await wizard.selectJava('17')
    wizard.toggleStarter('web')
    await wizard.selectBoot('3.2.5')
    expect(wizard.selectedBoot).toBe('3.2.5')
    expect(wizard.starters).toEqual(starters)
    expect(wizard.selectedStarters.size).toBe(0)
  })

  it('加载完成后 loading 复位', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.selectJava('17')
    expect(wizard.loading).toBe(false)
  })
})

describe('starter 多选', () => {
  it('toggleStarter 切换选中态', () => {
    const wizard = useWizardStore()
    wizard.toggleStarter('web')
    expect(wizard.selectedStarters.has('web')).toBe(true)
    wizard.toggleStarter('web')
    expect(wizard.selectedStarters.has('web')).toBe(false)
  })
})

describe('元信息与包名推导', () => {
  it('updateMeta 未手改包名时自动推导', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.updateMeta('group', 'com.example')
    await wizard.updateMeta('artifact', 'demo')
    expect(mockApi.derivePackage).toHaveBeenCalledWith('com.example', 'demo')
    expect(wizard.meta.packageName).toBe('com.example.demo')
  })

  it('手改包名后停止自动推导', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.updateMeta('group', 'com.example')
    await wizard.updateMeta('artifact', 'demo')
    await wizard.updateMeta('packageTouched', 'true')
    await wizard.updateMeta('artifact', 'demo2')
    expect(wizard.meta.packageName).toBe('com.example.demo')
  })

  it('group/artifact 不全时清空推导包名', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.updateMeta('group', 'com.example')
    await wizard.updateMeta('artifact', 'demo')
    await wizard.updateMeta('group', '')
    expect(wizard.meta.packageName).toBe('')
  })

  it('推导失败时记录包名错误', async () => {
    happyMocks()
    mockApi.derivePackage.mockRejectedValueOnce(new ApiError({ code: 1001, message: 'group 非法', timestamp: 0 }))
    const wizard = useWizardStore()
    await wizard.updateMeta('group', 'BAD GROUP')
    await wizard.updateMeta('artifact', 'demo')
    expect(wizard.validationErrors.packageName).toBe('group 非法')
  })

  it('validateMeta 三项全空时清空错误', async () => {
    const wizard = useWizardStore()
    wizard.validationErrors = { group: 'x' }
    await wizard.validateMeta()
    expect(wizard.validationErrors).toEqual({})
    expect(mockApi.validate).not.toHaveBeenCalled()
  })

  it('validateMeta 校验通过清空错误', async () => {
    happyMocks()
    const wizard = useWizardStore()
    wizard.meta.group = 'com.example'
    wizard.meta.artifact = 'demo'
    wizard.meta.packageName = 'com.example.demo'
    await wizard.validateMeta()
    expect(wizard.validationErrors).toEqual({})
  })

  it('validateMeta 校验失败记录错误明细', async () => {
    const errors: Record<string, string> = { group: 'group 非法' }
    mockApi.validate.mockResolvedValueOnce({ valid: false, errors } as ValidationResult)
    const wizard = useWizardStore()
    wizard.meta.group = 'BAD'
    await wizard.validateMeta()
    expect(wizard.validationErrors).toEqual(errors)
  })

  it('validateMeta errors 为 null 时记录空对象', async () => {
    mockApi.validate.mockResolvedValueOnce({ valid: false, errors: null })
    const wizard = useWizardStore()
    wizard.meta.group = 'BAD'
    await wizard.validateMeta()
    expect(wizard.validationErrors).toEqual({})
  })
})

describe('步骤导航', () => {
  it('初始为第一步且不可下一步', () => {
    const wizard = useWizardStore()
    expect(wizard.step).toBe(STEP_JAVA)
    expect(wizard.canNext).toBe(false)
  })

  it('选中 Java 后可下一步', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.loadJavaVersions()
    await wizard.selectJava('17')
    expect(wizard.canNext).toBe(true)
    wizard.next()
    expect(wizard.step).toBe(2)
  })

  it('未选 Boot 时第二步不可下一步', async () => {
    happyMocks()
    mockApi.listBootVersions.mockResolvedValueOnce([])
    const wizard = useWizardStore()
    await wizard.loadJavaVersions()
    await wizard.selectJava('8')
    wizard.next()
    expect(wizard.canNext).toBe(false)
  })

  it('依赖步骤始终可下一步', async () => {
    happyMocks()
    const wizard = useWizardStore()
    await wizard.loadJavaVersions()
    await wizard.selectJava('17')
    wizard.next()
    wizard.next()
    expect(wizard.step).toBe(STEP_STARTERS)
    expect(wizard.canNext).toBe(true)
    wizard.next()
    expect(wizard.step).toBe(STEP_META)
  })

  it('元信息完整且无错误时第四步可下一步', async () => {
    const wizard = useWizardStore()
    wizard.step = STEP_META
    expect(wizard.canNext).toBe(false)
    wizard.meta = { group: 'g', artifact: 'a', packageName: 'p', packageTouched: true, buildTool: 'maven' }
    expect(wizard.canNext).toBe(true)
  })

  it('最后一步不再前进', async () => {
    happyMocks()
    const wizard = useWizardStore()
    wizard.step = TOTAL_STEPS
    wizard.next()
    expect(wizard.step).toBe(TOTAL_STEPS)
  })

  it('prev 回退且第一步不再回退', () => {
    const wizard = useWizardStore()
    wizard.step = 2
    wizard.prev()
    expect(wizard.step).toBe(1)
    wizard.prev()
    expect(wizard.step).toBe(1)
  })
})

describe('生成提交', () => {
  function readyWizard(): ReturnType<typeof useWizardStore> {
    const wizard = useWizardStore()
    wizard.selectedJava = '17'
    wizard.selectedBoot = '3.2.5'
    wizard.selectedStarters = new Set(['web'])
    wizard.meta = { group: 'com.example', artifact: 'demo', packageName: 'com.example.demo', packageTouched: true, buildTool: 'maven' }
    return wizard
  }

  it('submit 成功返回 true 并置 success', async () => {
    mockApi.generateProject.mockResolvedValueOnce(undefined)
    const wizard = readyWizard()
    await expect(wizard.submit()).resolves.toBe(true)
    expect(wizard.submitState).toBe('success')
    expect(mockApi.generateProject).toHaveBeenCalledWith({
      group: 'com.example',
      artifact: 'demo',
      packageName: 'com.example.demo',
      javaVersion: '17',
      bootVersion: '3.2.5',
      starters: ['web'],
      buildTool: 'maven',
    })
  })

  it('submit 失败记录 ApiError 明细', async () => {
    mockApi.generateProject.mockRejectedValueOnce(new ApiError({ code: 2001, message: '版本组合不兼容', timestamp: 0 }))
    const wizard = readyWizard()
    await expect(wizard.submit()).resolves.toBe(false)
    expect(wizard.submitState).toBe('error')
    expect(wizard.errorMessage).toBe('生成失败：[2001] 版本组合不兼容')
  })

  it('submit 非ApiError 异常记录网络异常文案', async () => {
    mockApi.generateProject.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const wizard = readyWizard()
    await expect(wizard.submit()).resolves.toBe(false)
    expect(wizard.errorMessage).toBe('生成失败：网络异常，请稍后重试')
  })
})
