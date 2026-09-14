import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useStarterConfigWizardStore, toStarterDto, findUnknownPlaceholders } from '@/stores/starterConfigWizard'
import { emptyStarterConfigForm } from '@/types/starter-config'
import type { AdminDraftView, AdminPublishResult } from '@/types/api'
import type { StarterCatalogView, StarterDto } from '@/types/admin'

/**
 * starter 配置向导 store 单测：分步校验、**结构化提交**（乐观锁）、发布链路。
 *
 * T6 起向导不再拼 YAML，改为读清单 → 合并一条 → `PUT /api/admin/starters` 整体提交；
 * 用例因此改断言结构化请求体与 If-Match 头。
 */
const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  setActivePinia(createPinia())
})

/**
 * 成功响应夹具。
 *
 * 结构化端点用 `handleStructured`（`res.text()` + JSON.parse）解析，
 * 而 YAML 端点用 `handleResponse`（`res.json()`）；两者都要覆盖，故同时提供 json / text。
 */
function ok(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(body === null ? '' : JSON.stringify(body)),
  } as unknown as Response
}

function draftView(version: number | null): AdminDraftView {
  return { kind: 'metadata', contentYaml: null, version, updatedAt: null }
}

/** 结构化 starter 清单响应（缺省空清单）。 */
function catalogView(starters: StarterDto[] = []): StarterCatalogView {
  return { starters }
}

/** 结构化保存结果。 */
function saveResult(version: number | null, summary = 'starter 1 项 / 依赖 1 条'): unknown {
  return { kind: 'starter', rows: 2, version, summary }
}

/** 由向导表单填充一份「合法且可提交」的最小表单。 */
function fillValidForm(wizard: ReturnType<typeof useStarterConfigWizardStore>): void {
  wizard.form.id = 'web'
  wizard.form.description = 'Spring Web'
  wizard.form.dependencies = [
    { rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' },
  ]
}

/** 空白结构化 starter 条目（测试夹具基座）。 */
function emptyStarterDto(): StarterDto {
  return {
    id: '',
    category: 'web',
    maintainer: 'official',
    description: '',
    required: false,
    hidden: false,
    configPackage: null,
    dependencies: [],
    requires: [],
    bootVersions: [],
    linkages: null,
    sort: 0,
  }
}

/** 取最近一次结构化 PUT 调用。 */
function lastPut(url: string): RequestInit | undefined {
  return [...fetchMock.mock.calls]
    .reverse()
    .find((c) => c[0] === url && (c[1] as RequestInit)?.method === 'PUT')?.[1] as RequestInit | undefined
}

async function settle(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  await new Promise((r) => setTimeout(r, 0))
}

describe('starterConfigWizard 分步校验', () => {
  it('id 为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    expect(wizard.stepErrors.id).toBe('starter id 不能为空')
    expect(wizard.canNext).toBe(false)
  })

  it('id 命名非法时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'Bad_Id'
    expect(wizard.stepErrors.id).toContain('小写字母')
    expect(wizard.canNext).toBe(false)
  })

  it('id 与清单重复时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingIds = ['web', 'data-jpa']
    wizard.form.id = 'web'
    expect(wizard.stepErrors.id).toContain('已存在')
  })

  it('合法 id 且依赖完整时可下一步', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'demo'
    wizard.step = 1
    expect(wizard.canNext).toBe(true)
    wizard.next()
    expect(wizard.step).toBe(2)
  })

  it('依赖 groupId/artifactId 缺失时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'demo'
    wizard.step = 2
    wizard.form.dependencies = [{ rowId: 'r1', groupId: '', artifactId: '', version: '' }]
    expect(wizard.stepErrors.dependencies).toContain('groupId')
    expect(wizard.canNext).toBe(false)
  })

  it('占位符越界时预览步不可发布', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.configPackage = '{foo}'
    wizard.step = 4
    expect(wizard.unknownPlaceholders).toContain('{foo}')
    expect(wizard.canNext).toBe(false)
  })
})

describe('starterConfigWizard 结构化提交与发布', () => {
  it('saveToDraft 读取清单后整体提交（首次保存无 If-Match）', async () => {
    fetchMock.mockResolvedValueOnce(ok(catalogView())) // getStarters（合并前读取）
    fetchMock.mockResolvedValueOnce(ok(saveResult(1))) // PUT /starters
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    fillValidForm(wizard)

    const result = await wizard.saveToDraft()
    await settle()
    expect(result).toBe(true)
    expect(wizard.actionState).toBe('done')

    const put = lastPut('/api/admin/starters')
    expect(put).toBeTruthy()
    const body = JSON.parse(put!.body as string)
    expect(body.operator).toBe('admin')
    expect(body.starters).toHaveLength(1)
    expect(body.starters[0].id).toBe('web')
    expect(body.starters[0].dependencies[0].artifactId).toBe('spring-boot-starter-web')
    // 首次保存（draftVersion 为 null）不携带 If-Match
    expect((put!.headers as Record<string, string>)['If-Match']).toBeUndefined()
  })

  it('saveToDraft 保留清单中其余条目并带乐观锁 If-Match', async () => {
    const existing: StarterDto = {
      id: 'old',
      category: 'web',
      maintainer: 'official',
      description: 'old',
      required: false,
      hidden: false,
      configPackage: null,
      dependencies: [],
      requires: [],
      bootVersions: [],
      linkages: null,
      sort: 0,
    }
    fetchMock.mockResolvedValueOnce(ok(catalogView([existing])))
    fetchMock.mockResolvedValueOnce(ok(saveResult(6)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.draftVersion = 5
    fillValidForm(wizard)

    const result = await wizard.saveToDraft()
    await settle()
    expect(result).toBe(true)

    const put = lastPut('/api/admin/starters')
    const body = JSON.parse(put!.body as string)
    expect(body.starters.map((s: StarterDto) => s.id)).toEqual(['old', 'web'])
    expect((put!.headers as Record<string, string>)['If-Match']).toBe('5')
    expect(wizard.draftVersion).toBe(6)
  })

  it('saveToDraft 同名条目覆盖而非重复追加', async () => {
    const existing: StarterDto = {
      id: 'web',
      category: 'data',
      maintainer: 'internal',
      description: 'legacy',
      required: true,
      hidden: true,
      configPackage: null,
      dependencies: [],
      requires: [],
      bootVersions: [],
      linkages: null,
      sort: 0,
    }
    fetchMock.mockResolvedValueOnce(ok(catalogView([existing])))
    fetchMock.mockResolvedValueOnce(ok(saveResult(7)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    fillValidForm(wizard)

    await wizard.saveToDraft()
    const body = JSON.parse(lastPut('/api/admin/starters')!.body as string)
    expect(body.starters).toHaveLength(1)
    expect(body.starters[0].description).toBe('Spring Web')
  })

  it('saveToDraft 未登录时报错不请求', async () => {
    const wizard = useStarterConfigWizardStore()
    fillValidForm(wizard)
    const result = await wizard.saveToDraft()
    expect(result).toBe(false)
    expect(wizard.messageType).toBe('error')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('publish 先保存草稿再触发发布', async () => {
    fetchMock.mockResolvedValueOnce(ok(catalogView()))
    fetchMock.mockResolvedValueOnce(ok(saveResult(1)))
    const pub: AdminPublishResult = { releaseId: 9, javaVersionCount: 4, starterCount: 6, templateOverrideCount: 1, conflictRuleCount: 3 }
    fetchMock.mockResolvedValueOnce(ok(pub))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    fillValidForm(wizard)

    const result = await wizard.publish()
    await settle()
    expect(result).toBe(true)
    expect(wizard.actionState).toBe('done')
    expect(wizard.message).toContain('发布 #9')
    expect(fetchMock.mock.calls.some((c) => c[0] === '/api/admin/config/publish')).toBe(true)
  })

  it('publish 校验失败返回 4002 明细', async () => {
    fetchMock.mockResolvedValueOnce(ok(catalogView()))
    fetchMock.mockResolvedValueOnce(ok(saveResult(1)))
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: () => Promise.resolve({ code: 4002, message: '校验失败明细', timestamp: 1 }),
    } as unknown as Response)
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    fillValidForm(wizard)

    const result = await wizard.publish()
    await settle()
    expect(result).toBe(false)
    expect(wizard.actionState).toBe('error')
    expect(wizard.message).toContain('校验失败明细')
  })
})

describe('starterConfigWizard 行 actions', () => {
  it('增删依赖行（保留至少一条）', () => {
    const wizard = useStarterConfigWizardStore()
    expect(wizard.form.dependencies).toHaveLength(1)
    wizard.addDependency()
    expect(wizard.form.dependencies).toHaveLength(2)
    const firstId = wizard.form.dependencies[0].rowId
    wizard.removeDependency(firstId)
    expect(wizard.form.dependencies).toHaveLength(1)
    // 删除唯一一条时不删
    wizard.removeDependency(wizard.form.dependencies[0].rowId)
    expect(wizard.form.dependencies).toHaveLength(1)
  })

  it('增删模板 / yml 片段 / 抽象类', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.addTemplate()
    wizard.addYmlFragment()
    wizard.addAbstractClass()
    expect(wizard.form.templates).toHaveLength(1)
    expect(wizard.form.ymlFragments).toHaveLength(1)
    expect(wizard.form.abstractClasses).toHaveLength(1)
    const tid = wizard.form.templates[0].rowId
    const fid = wizard.form.ymlFragments[0].rowId
    const aid = wizard.form.abstractClasses[0].rowId
    wizard.removeTemplate(tid)
    wizard.removeYmlFragment(fid)
    wizard.removeAbstractClass(aid)
    expect(wizard.form.templates).toHaveLength(0)
    expect(wizard.form.ymlFragments).toHaveLength(0)
    expect(wizard.form.abstractClasses).toHaveLength(0)
  })

  it('reset 重置表单与步骤', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.step = 3
    wizard.actionState = 'done'
    wizard.reset()
    expect(wizard.step).toBe(1)
    expect(wizard.form.id).toBe('')
    expect(wizard.actionState).toBe('idle')
  })

  it('setToken 写入 token', () => {
    const wizard = useStarterConfigWizardStore()
    expect(wizard.token).toBe('')
    wizard.setToken('abc')
    expect(wizard.token).toBe('abc')
  })

  it('next/prev/goto 步骤导航', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    expect(wizard.step).toBe(1)
    wizard.next()
    expect(wizard.step).toBe(2)
    wizard.next() // deps ok -> 3
    expect(wizard.step).toBe(3)
    wizard.prev()
    expect(wizard.step).toBe(2)
    wizard.goto(4)
    expect(wizard.step).toBe(4)
    wizard.goto(0) // 越界忽略
    expect(wizard.step).toBe(4)
    wizard.goto(99) // 越界忽略
    expect(wizard.step).toBe(4)
  })

  it('next 在校验失败时不前进', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = '' // 校验失败
    wizard.next()
    expect(wizard.step).toBe(1)
  })

  it('showText 设置消息类型', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.showText('error', 'boom')
    expect(wizard.messageType).toBe('error')
    expect(wizard.message).toBe('boom')
    wizard.showText('info', 'ok')
    expect(wizard.messageType).toBe('info')
  })

  it('清单读取失败时置空不报错', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false, status: 404,
      json: () => Promise.resolve({ code: 4001, message: 'not found', timestamp: 1 }),
    } as unknown as Response)
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    await wizard.loadExistingDraft()
    expect(wizard.existingIds).toEqual([])
    expect(wizard.draftVersion).toBeNull()
  })

  it('previewDto 与 hasLinkages getter 反映联动', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    expect(wizard.hasLinkages).toBe(false)
    expect(wizard.previewDto.linkages?.templates).toEqual([])
    wizard.form.configPackage = '{basePackage}.config'
    expect(wizard.hasLinkages).toBe(true)
    expect(wizard.previewDto.configPackage).toBe('{basePackage}.config')
  })
})

describe('starterConfigWizard stepErrors 联动步骤校验', () => {
  it('templates source 或 target 为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.templates = [{ rowId: 'r1', source: '', target: 't.java' }]
    expect(wizard.stepErrors.templates).toContain('source')
    expect(wizard.canNext).toBe(false)
  })

  it('templates target 为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.templates = [{ rowId: 'r1', source: 's.ftl', target: '' }]
    expect(wizard.stepErrors.templates).toContain('target')
    expect(wizard.canNext).toBe(false)
  })

  it('templates source 和 target 均为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.templates = [{ rowId: 'r1', source: '', target: '' }]
    expect(wizard.stepErrors.templates).toBeDefined()
  })

  it('ymlFragments source 为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.ymlFragments = [{ rowId: 'r1', source: '', mergeStrategy: 'append' }]
    expect(wizard.stepErrors.ymlFragments).toContain('source')
    expect(wizard.canNext).toBe(false)
  })

  it('abstractClasses fqcn 为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.abstractClasses = [{ rowId: 'r1', fqcn: '', alias: 'a' }]
    expect(wizard.stepErrors.abstractClasses).toContain('fqcn')
    expect(wizard.canNext).toBe(false)
  })

  it('abstractClasses alias 为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.abstractClasses = [{ rowId: 'r1', fqcn: 'com.x.A', alias: '' }]
    expect(wizard.stepErrors.abstractClasses).toContain('alias')
    expect(wizard.canNext).toBe(false)
  })

  it('联动步骤全部合法时 canNext 为 true', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.templates = [{ rowId: 'r1', source: 's.ftl', target: 't.java' }]
    wizard.form.ymlFragments = [{ rowId: 'r1', source: 'f.yml', mergeStrategy: 'append' }]
    wizard.form.abstractClasses = [{ rowId: 'r1', fqcn: 'com.x.A', alias: 'a' }]
    expect(wizard.stepErrors).not.toHaveProperty('templates')
    expect(wizard.stepErrors).not.toHaveProperty('ymlFragments')
    expect(wizard.stepErrors).not.toHaveProperty('abstractClasses')
    expect(wizard.canNext).toBe(true)
  })

  it('仅 templates 有错时 ymlFragments 无错不阻断', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.templates = [{ rowId: 'r1', source: '', target: '' }]
    wizard.form.ymlFragments = [{ rowId: 'r1', source: 'ok.yml', mergeStrategy: 'append' }]
    expect(wizard.stepErrors).toHaveProperty('templates')
    expect(wizard.stepErrors).not.toHaveProperty('ymlFragments')
  })

  it('多条 template 中第一条出错即 break', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.templates = [
      { rowId: 'r1', source: '', target: '' },
      { rowId: 'r2', source: 's.ftl', target: 't.java' },
    ]
    expect(wizard.stepErrors.templates).toBeDefined()
  })

  it('依赖步骤为空时报错（length === 0）', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = []
    wizard.step = 2
    expect(wizard.stepErrors.dependencies).toContain('至少声明')
  })
})

describe('starterConfigWizard canNext 各步骤', () => {
  it('步骤 1 合法时 canNext 为 true', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.step = 1
    expect(wizard.canNext).toBe(true)
  })

  it('步骤 2 合法时 canNext 为 true', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 2
    expect(wizard.canNext).toBe(true)
  })

  it('步骤 4 无越界占位符时 canNext 为 true', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 4
    expect(wizard.canNext).toBe(true)
  })

  it('步骤 1 id 有错时 canNext 为 false', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = ''
    wizard.step = 1
    expect(wizard.canNext).toBe(false)
  })

  it('步骤 2 依赖有错时 canNext 为 false', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: '', artifactId: 'a', version: '' }]
    wizard.step = 2
    expect(wizard.canNext).toBe(false)
  })
})

describe('starterConfigWizard loadExistingDraft', () => {
  it('token 为空时不请求', async () => {
    const wizard = useStarterConfigWizardStore()
    wizard.token = ''
    await wizard.loadExistingDraft()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('成功时设置 existingIds 和 draftVersion', async () => {
    fetchMock.mockResolvedValueOnce(ok(catalogView([{ ...emptyStarterDto(), id: 'web' }])))
    fetchMock.mockResolvedValueOnce(ok(draftView(3)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    await wizard.loadExistingDraft()
    expect(wizard.existingIds).toEqual(['web'])
    expect(wizard.draftVersion).toBe(3)
  })

  it('清单为空时 existingIds 为空数组、版本为 null', async () => {
    fetchMock.mockResolvedValueOnce(ok(catalogView([])))
    fetchMock.mockResolvedValueOnce(ok(draftView(null)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    await wizard.loadExistingDraft()
    expect(wizard.existingIds).toEqual([])
    expect(wizard.draftVersion).toBeNull()
  })

  it('请求失败时不阻断向导，按首次保存语义置空', async () => {
    fetchMock.mockRejectedValueOnce(new Error('network'))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    await wizard.loadExistingDraft()
    expect(wizard.existingIds).toEqual([])
    expect(wizard.draftVersion).toBeNull()
  })
})

describe('starterConfigWizard saveToDraft 错误路径', () => {
  it('表单校验有错时不保存', async () => {
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = '' // 校验失败
    const result = await wizard.saveToDraft()
    expect(result).toBe(false)
    expect(wizard.messageType).toBe('error')
    expect(wizard.message).toContain('校验错误')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('占位符越界时不保存', async () => {
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.configPackage = '{badPlaceholder}'
    const result = await wizard.saveToDraft()
    expect(result).toBe(false)
    expect(wizard.messageType).toBe('error')
    expect(wizard.message).toContain('占位符越界')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('保存失败（非 ApiError）时展示通用错误', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: () => Promise.reject(new SyntaxError('bad')),
    } as unknown as Response)
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    fillValidForm(wizard)
    const result = await wizard.saveToDraft()
    await settle()
    expect(result).toBe(false)
    expect(wizard.actionState).toBe('error')
    // 非 JSON 错误体 → handleStructured 回退默认错误体，detail 含 HTTP 状态
    expect(wizard.message).toContain('HTTP 500')
  })

  it('保存失败（ApiError）时展示后端明细', async () => {
    fetchMock.mockResolvedValueOnce(ok(catalogView()))
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: () => Promise.resolve({ code: 4002, message: '校验失败明细', timestamp: 1 }),
    } as unknown as Response)
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    fillValidForm(wizard)
    const result = await wizard.saveToDraft()
    await settle()
    expect(result).toBe(false)
    expect(wizard.actionState).toBe('error')
    expect(wizard.message).toContain('校验失败明细')
  })
})

describe('starterConfigWizard publish 错误路径', () => {
  it('publish 保存失败时不触发发布', async () => {
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = '' // 校验失败 → saveToDraft 失败
    const result = await wizard.publish()
    expect(result).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('publish 发布失败（非 ApiError）时展示通用错误', async () => {
    fetchMock.mockResolvedValueOnce(ok(catalogView()))
    fetchMock.mockResolvedValueOnce(ok(saveResult(1)))
    // publish 返回非 JSON 错误体
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 502,
      json: () => Promise.reject(new SyntaxError('bad')),
    } as unknown as Response)
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    fillValidForm(wizard)

    const result = await wizard.publish()
    await settle()
    expect(result).toBe(false)
    expect(wizard.actionState).toBe('error')
    expect(wizard.message).toContain('HTTP 502')
  })
})

describe('starterConfigWizard hasLinkages getter', () => {
  it('仅 templates 有值时 hasLinkages 为 true', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.templates = [{ rowId: 'r1', source: 's', target: 't' }]
    expect(wizard.hasLinkages).toBe(true)
  })

  it('仅 ymlFragments 有值时 hasLinkages 为 true', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.ymlFragments = [{ rowId: 'r1', source: 'f', mergeStrategy: 'append' }]
    expect(wizard.hasLinkages).toBe(true)
  })

  it('仅 abstractClasses 有值时 hasLinkages 为 true', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.abstractClasses = [{ rowId: 'r1', fqcn: 'c', alias: 'a' }]
    expect(wizard.hasLinkages).toBe(true)
  })

  it('configPackage 仅有空格时 hasLinkages 为 false', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.configPackage = '   '
    expect(wizard.hasLinkages).toBe(false)
  })
})

describe('starterConfigWizard step navigation 边界', () => {
  it('next 在最后一步不前进', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 4
    wizard.next()
    expect(wizard.step).toBe(4)
  })

  it('prev 在第一步不后退', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.step = 1
    wizard.prev()
    expect(wizard.step).toBe(1)
  })

  it('goto 到有效步骤', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.goto(3)
    expect(wizard.step).toBe(3)
    wizard.goto(1)
    expect(wizard.step).toBe(1)
  })
})

describe('starterConfigWizard stepErrors 边界', () => {
  it('id 含前后空格时 trim 后校验', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = '  web  '
    wizard.form.description = 'd'
    wizard.step = 1
    expect(wizard.stepErrors.id).toBeUndefined()
    expect(wizard.canNext).toBe(true)
  })

  it('id 含空格但 trim 后非法时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = '  Bad_Id  '
    wizard.step = 1
    expect(wizard.stepErrors.id).toContain('小写字母')
  })

  it('description 含空格时 trim 后为空报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = '   '
    wizard.step = 1
    expect(wizard.stepErrors.description).toContain('描述建议填写')
  })

  it('id 前后空格但清单查重仍匹配', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingIds = ['web']
    wizard.form.id = '  web  '
    wizard.form.description = 'd'
    wizard.step = 1
    expect(wizard.stepErrors.id).toContain('已存在')
  })

  it('步骤 1 时不检查依赖校验', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = []
    wizard.step = 1
    expect(wizard.stepErrors).not.toHaveProperty('dependencies')
  })

  it('步骤 1 时不检查联动校验', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.templates = [{ rowId: 'r1', source: '', target: '' }]
    wizard.step = 1
    expect(wizard.stepErrors).not.toHaveProperty('templates')
  })

  it('步骤 2 时不检查联动校验', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.templates = [{ rowId: 'r1', source: '', target: '' }]
    wizard.step = 2
    expect(wizard.stepErrors).not.toHaveProperty('templates')
  })

  it('依赖 groupId 含空格 trim 后为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: '  ', artifactId: 'a', version: '' }]
    wizard.step = 2
    expect(wizard.stepErrors.dependencies).toContain('groupId')
  })

  it('依赖 artifactId 含空格 trim 后为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: '  ', version: '' }]
    wizard.step = 2
    expect(wizard.stepErrors.dependencies).toContain('artifactId')
  })

  it('多条依赖中第二条 groupId 为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [
      { rowId: 'r1', groupId: 'g1', artifactId: 'a1', version: '' },
      { rowId: 'r2', groupId: '', artifactId: 'a2', version: '' },
    ]
    wizard.step = 2
    expect(wizard.stepErrors.dependencies).toContain('groupId')
  })

  it('template source 含空格 trim 后为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.templates = [{ rowId: 'r1', source: '  ', target: 't.java' }]
    expect(wizard.stepErrors.templates).toBeDefined()
  })

  it('template target 含空格 trim 后为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.templates = [{ rowId: 'r1', source: 's.ftl', target: '  ' }]
    expect(wizard.stepErrors.templates).toBeDefined()
  })

  it('ymlFragment source 含空格 trim 后为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.ymlFragments = [{ rowId: 'r1', source: '  ', mergeStrategy: 'append' }]
    expect(wizard.stepErrors.ymlFragments).toBeDefined()
  })

  it('abstractClass fqcn 含空格 trim 后为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.abstractClasses = [{ rowId: 'r1', fqcn: '  ', alias: 'a' }]
    expect(wizard.stepErrors.abstractClasses).toBeDefined()
  })

  it('abstractClass alias 含空格 trim 后为空时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.step = 3
    wizard.form.abstractClasses = [{ rowId: 'r1', fqcn: 'com.x.A', alias: '  ' }]
    expect(wizard.stepErrors.abstractClasses).toBeDefined()
  })
})

describe('starterConfigWizard id 清单查重', () => {
  it('清单中存在同名 id 即报重复', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingIds = ['webflux']
    wizard.form.id = 'web'
    expect(wizard.stepErrors.id).toBeUndefined()
    wizard.form.id = 'webflux'
    expect(wizard.stepErrors.id).toContain('已存在')
  })

  it('id 在清单中不存在时不报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingIds = ['webflux']
    wizard.form.id = 'web'
    expect(wizard.stepErrors.id).toBeUndefined()
  })

  it('清单为空时不报重复', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    expect(wizard.stepErrors.id).toBeUndefined()
  })

  it('id 前后空格时按 trim 后比对', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingIds = ['web']
    wizard.form.id = '  web  '
    expect(wizard.stepErrors.id).toContain('已存在')
  })
})

describe('starterConfigWizard unknownPlaceholders getter', () => {
  it('configPackage 有越界占位符时返回', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.configPackage = '{badPlaceholder}'
    expect(wizard.unknownPlaceholders).toContain('{badPlaceholder}')
  })

  it('无越界占位符时返回空数组', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    wizard.form.configPackage = '{basePackage}.config'
    expect(wizard.unknownPlaceholders).toEqual([])
  })
})

describe('starterConfigWizard 结构化提交成功路径细节', () => {
  it('保存成功后同步 existingIds 与 draftVersion', async () => {
    fetchMock.mockResolvedValueOnce(ok(catalogView([{ ...emptyStarterDto(), id: 'old' }])))
    fetchMock.mockResolvedValueOnce(ok(saveResult(6)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    fillValidForm(wizard)

    const result = await wizard.saveToDraft()
    await settle()
    expect(result).toBe(true)
    expect(wizard.message).toContain('已写入')
    expect(wizard.existingIds).toEqual(['old', 'web'])
    expect(wizard.draftVersion).toBe(6)
  })

  it('previewDto 与提交体同源（含 hidden / required / requires）', () => {
    const wizard = useStarterConfigWizardStore()
    fillValidForm(wizard)
    wizard.form.required = true
    wizard.form.hidden = true
    wizard.form.bootVersions = ['3.2.5']
    wizard.setRequires('b, c\nd')
    const dto = wizard.previewDto
    expect(dto.id).toBe('web')
    expect(dto.required).toBe(true)
    expect(dto.hidden).toBe(true)
    expect(dto.bootVersions).toEqual(['3.2.5'])
    expect(dto.requires).toEqual([['b', 'c'], ['d']])
    expect(dto.linkages).toEqual({ templates: [], ymlFragments: [], abstractClasses: [] })
  })

  it('requiresText 与 setRequires 往返保持分组结构', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.setRequires('b, c\n\nd\n')
    expect(wizard.form.requires).toEqual([['b', 'c'], ['d']])
    expect(wizard.requiresText).toBe('b, c\nd')
  })

  it('setBootVersions 去重去空', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.setBootVersions(' 3.2.5, 2.7.18,3.2.5 , ')
    expect(wizard.form.bootVersions).toEqual(['3.2.5', '2.7.18'])
  })
})

describe('toStarterDto 与 findUnknownPlaceholders 纯函数', () => {
  it('空串归一化为 null，占位符计入无异常', () => {
    const form = emptyStarterConfigForm()
    form.id = 'web'
    // 类型上是枚举字面量，这里刻意用空串验证「空串 → null」归一化（表单允许清空后提交）
    form.category = '' as unknown as typeof form.category
    form.maintainer = 'official'
    form.description = ''
    form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    const dto = toStarterDto(form, 3)
    expect(dto.category).toBeNull()
    expect(dto.description).toBeNull()
    expect(dto.configPackage).toBeNull()
    expect(dto.dependencies[0].version).toBeNull()
    expect(dto.sort).toBe(3)
  })

  it('findUnknownPlaceholders 扫描模板 / 片段 / 抽象类', () => {
    const form = emptyStarterConfigForm()
    form.configPackage = '{basePackage}.config'
    form.templates = [{ rowId: 't1', source: 's.ftl', target: 'src/{packagePath}/{foo}.java' }]
    form.ymlFragments = [{ rowId: 'f1', source: 'starters/{bar}/x.yml', mergeStrategy: 'append' }]
    form.abstractClasses = [{ rowId: 'a1', fqcn: 'com.{baz}.X', alias: 'x' }]
    expect(findUnknownPlaceholders(form)).toEqual(['{foo}', '{bar}', '{baz}'])
  })

  it('无占位符时返回空数组', () => {
    expect(findUnknownPlaceholders(emptyStarterConfigForm())).toEqual([])
  })
})
