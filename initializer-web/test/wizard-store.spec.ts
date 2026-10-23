import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api, ApiError } from '@/api/client'
import { STEP_JAVA, STEP_META, STEP_STARTERS, TOTAL_STEPS, useWizardStore } from '@/stores/wizard'
import type { BootVersion, ConflictRule, JavaVersion, Starter, ValidationResult } from '@/types/api'

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
  { id: 'web', label: 'Spring Web', maintainer: 'official' as const, category: 'web', description: 'Spring MVC', version: null, required: false, requires: [], hidden: false },
  { id: 'data-jpa', label: 'Spring Data JPA', maintainer: 'official' as const, category: 'data', description: 'JPA', version: null, required: false, requires: [], hidden: false },
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

describe('依赖联动与必选（M3.8）', () => {
  function st(id: string, requires: string[][] = [], extra: Partial<Starter> = {}): Starter {
    return {
      id, label: id, maintainer: 'internal', category: 'data', description: null,
      version: null, required: false, requires, hidden: false, ...extra,
    }
  }

  const linkStarters: Starter[] = [
    st('web'),
    st('core'),
    // 单元素分组（无 OR）→ 强制依赖：dal 需要 core 和 log
    st('dal', [['core'], ['log']]),
    // OR 分组：repo 需要 jpa 或 mybatis
    st('repo', [['jpa', 'mybatis']]),
    st('log'),
    st('jpa'),
    st('mybatis'),
    st('base', [], { required: true }),
  ]

  function linkWizard(rules: ConflictRule[] = []) {
    const wizard = useWizardStore()
    wizard.starters = linkStarters
    wizard.registerStarters(linkStarters)
    wizard.conflictRules = rules
    return wizard
  }

  it('选中带强制依赖的 starter 自动勾选依赖', () => {
    const wizard = linkWizard()
    wizard.toggleStarter('dal')
    expect([...wizard.selectedStarters].sort()).toEqual(['base', 'core', 'dal', 'log'])
  })

  it('强制依赖被锁定，取消父组件后自动释放（随父组件联动）', () => {
    const wizard = linkWizard()
    wizard.toggleStarter('dal')
    expect(wizard.lockedStarters.has('core')).toBe(true)
    expect(wizard.lockedStarters.has('log')).toBe(true)
    // 锁定项不可取消
    wizard.toggleStarter('core')
    expect(wizard.selectedStarters.has('core')).toBe(true)
    // 取消父组件后依赖随之释放，必选项保留
    wizard.toggleStarter('dal')
    expect(wizard.selectedStarters.has('dal')).toBe(false)
    expect(wizard.selectedStarters.has('core')).toBe(false)
    expect(wizard.selectedStarters.has('log')).toBe(false)
    expect(wizard.selectedStarters.has('base')).toBe(true)
  })

  it('OR 分组：不自动勾选，选中任一选项即满足', () => {
    const wizard = linkWizard()
    wizard.toggleStarter('repo')
    expect(wizard.selectedStarters.has('jpa')).toBe(false)
    expect(wizard.selectedStarters.has('mybatis')).toBe(false)
    expect(wizard.unsatisfiedRequires.length).toBe(1)
    wizard.toggleStarter('jpa')
    expect(wizard.unsatisfiedRequires.length).toBe(0)
  })

  it('OR 分组：互斥的两个选项可只选一个', () => {
    const wizard = linkWizard()
    wizard.toggleStarter('repo')
    wizard.toggleStarter('jpa')
    expect(wizard.selectedStarters.has('mybatis')).toBe(false)
    expect(wizard.unsatisfiedRequires.length).toBe(0)
  })

  it('OR 分组未满足时把候选项标记为待二选一，选中其一后撤销（M3.9）', () => {
    const wizard = linkWizard()
    expect(wizard.pendingOrOptions.size).toBe(0)
    wizard.toggleStarter('repo')
    expect([...wizard.pendingOrOptions].sort()).toEqual(['jpa', 'mybatis'])
    wizard.toggleStarter('jpa')
    expect(wizard.pendingOrOptions.size).toBe(0)
  })

  it('单元素分组（强制依赖）不进入待选候选项标记（M3.9）', () => {
    const wizard = linkWizard()
    wizard.toggleStarter('dal')
    expect(wizard.pendingOrOptions.size).toBe(0)
  })

  it('必选项默认勾选且不可取消', () => {
    const wizard = linkWizard()
    wizard.selectRequiredStarters()
    expect(wizard.selectedStarters.has('base')).toBe(true)
    wizard.toggleStarter('base')
    expect(wizard.selectedStarters.has('base')).toBe(true)
  })

  it('强制依赖与已选组件互斥时双方不锁定，可二选一切换', () => {
    const rules: ConflictRule[] = [
      { id: 'core-vs-jpa', type: 'group', starters: ['core', 'jpa'], message: 'core 与 jpa 互斥' },
    ]
    const wizard = linkWizard(rules)
    wizard.toggleStarter('jpa')
    wizard.toggleStarter('dal')
    // 强制依赖 core 与已选 jpa 互斥 → 跳过自动勾选 core
    expect(wizard.selectedStarters.has('jpa')).toBe(true)
    expect(wizard.selectedStarters.has('core')).toBe(false)
    // 互斥双方都不锁定并标记互斥
    expect(wizard.lockedStarters.has('core')).toBe(false)
    expect(wizard.mutexIds.has('core')).toBe(true)
    expect(wizard.mutexIds.has('jpa')).toBe(true)
    expect(wizard.mutexInfo.core[0].partners).toEqual(['jpa'])
    // 二选一切换：选中 core 自动取消 jpa
    wizard.toggleStarter('core')
    expect(wizard.selectedStarters.has('core')).toBe(true)
    expect(wizard.selectedStarters.has('jpa')).toBe(false)
  })

  it('一对互斥的强制依赖默认只自动选中其一，可二选一切换', () => {
    const mutexStarters: Starter[] = [st('app', [['b'], ['c']]), st('b'), st('c')]
    const rules: ConflictRule[] = [
      { id: 'b-vs-c', type: 'pair', starters: ['b', 'c'], message: 'b 与 c 互斥' },
    ]
    const wizard = useWizardStore()
    wizard.starters = mutexStarters
    wizard.registerStarters(mutexStarters)
    wizard.conflictRules = rules
    wizard.toggleStarter('app')
    // 默认只选中其一（列表顺序中的 b）
    expect(wizard.selectedStarters.has('b')).toBe(true)
    expect(wizard.selectedStarters.has('c')).toBe(false)
    // 互斥双方都不锁定
    expect(wizard.lockedStarters.has('b')).toBe(false)
    expect(wizard.lockedStarters.has('c')).toBe(false)
    // 切换到 c
    wizard.toggleStarter('c')
    expect(wizard.selectedStarters.has('c')).toBe(true)
    expect(wizard.selectedStarters.has('b')).toBe(false)
  })

  it('隐藏条目默认不展示，被已选组件依赖时强制展示', () => {
    const hiddenStarters: Starter[] = [st('app', [['ghost']]), st('ghost', [], { hidden: true })]
    const wizard = useWizardStore()
    wizard.starters = hiddenStarters
    wizard.registerStarters(hiddenStarters)
    expect(wizard.displayStarters.map((s) => s.id)).toEqual(['app'])
    wizard.toggleStarter('app')
    expect(wizard.selectedStarters.has('ghost')).toBe(true)
    expect(wizard.displayStarters.map((s) => s.id).sort()).toEqual(['app', 'ghost'])
  })

  it('被揭示的隐藏条目不在当前检索结果时从注册表补入', () => {
    const wizard = useWizardStore()
    wizard.starters = [st('app', [['ghost']])]
    wizard.registerStarters([st('app', [['ghost']]), st('ghost', [], { hidden: true })])
    wizard.toggleStarter('app')
    expect(wizard.displayStarters.map((s) => s.id).sort()).toEqual(['app', 'ghost'])
  })
})

describe('可靠性评审回归', () => {
  function deferred<T>() {
    let resolve!: (value: T) => void
    const promise = new Promise<T>((done) => { resolve = done })
    return { promise, resolve }
  }
  it('推导乱序只采纳新输入，手动编辑使在途推导失效', async () => {
    const old = deferred<{ packageName: string }>()
    mockApi.derivePackage.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ packageName: 'com.example.new' })
    const w = useWizardStore()
    w.meta.group = 'com.example'
    const pending = w.updateMeta('artifact', 'old')
    await w.updateMeta('artifact', 'new')
    old.resolve({ packageName: 'com.example.old' })
    await pending
    expect(w.meta.packageName).toBe('com.example.new')
    const auto = deferred<{ packageName: string }>()
    mockApi.derivePackage.mockReturnValueOnce(auto.promise)
    const next = w.updateMeta('artifact', 'third')
    await w.updateMeta('packageTouched', 'true')
    await w.updateMeta('packageName', 'com.custom')
    auto.resolve({ packageName: 'com.example.third' })
    await next
    expect(w.meta.packageName).toBe('com.custom')
  })
  it('旧搜索不能污染新 Boot 的注册表', async () => {
    happyMocks()
    const old = deferred<Starter[]>()
    const w = useWizardStore()
    w.selectedBoot = '2.7.18'
    mockApi.listStarters.mockReturnValueOnce(old.promise)
    const pending = w.setSearch('old', '')
    await w.selectBoot('3.2.5')
    old.resolve([{ ...starters[0], id: 'obsolete' }])
    await pending
    expect(w.starters).toEqual(starters)
    expect(w.starterRegistry.obsolete).toBeUndefined()
  })
  it('过期元信息校验不能覆盖新结果', async () => {
    const old = deferred<ValidationResult>()
    mockApi.validate.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ valid: true, errors: null })
    const w = useWizardStore()
    w.meta.group = 'old'
    const pending = w.validateMeta()
    await w.updateMeta('group', 'com.new')
    await w.validateMeta()
    old.resolve({ valid: false, errors: { group: '过期错误' } })
    await pending
    expect(w.validationErrors).toEqual({})
  })
  it('加载、校验、冲突与缺依赖均禁止生成，解除后恢复', async () => {
    const w = useWizardStore()
    w.selectedJava = '17'
    w.selectedBoot = '3.2.5'
    w.meta = { group: 'com.example', artifact: 'demo', packageName: 'com.example.demo', packageTouched: true, buildTool: 'maven' }
    expect(w.canGenerate).toBe(true)
    for (const key of ['loading', 'validating', 'deriving'] as const) {
      w[key] = true
      expect(w.canGenerate).toBe(false)
      w[key] = false
    }
    w.starterRegistry = { web: { ...starters[0], requires: [['data-jpa']] } }
    w.selectedStarters = new Set(['web'])
    expect(w.canGenerate).toBe(false)
    w.selectedStarters.add('data-jpa')
    expect(w.canGenerate).toBe(true)
    w.conflictRules = [{ id: 'mutex', type: 'pair', starters: ['web', 'data-jpa'], message: '冲突' }]
    expect(await w.submit()).toBe(false)
    expect(mockApi.generateProject).not.toHaveBeenCalled()
  })
})
