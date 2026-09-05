import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useStarterConfigWizardStore } from '@/stores/starterConfigWizard'
import type { AdminDraftView, AdminPublishResult } from '@/types/api'

/**
 * starter 配置向导 store 单测：分步校验、草稿追加（乐观锁）、发布链路。
 * 复用 adminApi 但 mock fetch，验证请求体与 If-Match 头。
 */
const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  setActivePinia(createPinia())
})

function ok(body: unknown): Response {
  return { ok: true, status: 200, json: () => Promise.resolve(body) } as unknown as Response
}

function noContent(): Response {
  return { ok: true, status: 204 } as unknown as Response
}

function draftView(yaml: string | null, version: number | null): AdminDraftView {
  return { kind: 'starter', contentYaml: yaml, version, updatedAt: null }
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

  it('id 与草稿重复时报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingDraft = 'id: web\ncategory: web\n'
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

describe('starterConfigWizard 草稿追加与发布', () => {
  it('saveToDraft 首次保存直接写入（无文档分隔符）', async () => {
    fetchMock.mockResolvedValueOnce(ok(draftView(null, null))) // loadExistingDraft
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'Spring Web'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }]
    await wizard.loadExistingDraft()
    fetchMock.mockResolvedValueOnce(noContent()) // PUT
    fetchMock.mockResolvedValueOnce(ok(draftView('id: web\n', 1))) // 重新 loadExistingDraft

    const result = await wizard.saveToDraft()
    await settle()
    expect(result).toBe(true)
    expect(wizard.actionState).toBe('done')
    // PUT 请求体不含 --- 分隔符（首次保存）
    const putCall = fetchMock.mock.calls.find((c) => c[0] === '/api/admin/config/starter' && (c[1] as RequestInit)?.method === 'PUT')
    expect(putCall).toBeTruthy()
    const body = JSON.parse((putCall![1] as RequestInit).body as string)
    expect(body.contentYaml).toContain('id: web')
    expect(body.contentYaml).not.toContain('---')
    // 首次保存无 If-Match
    expect((putCall![1] as RequestInit).headers).not.toHaveProperty('If-Match')
  })

  it('saveToDraft 追加到已有草稿带乐观锁 If-Match', async () => {
    fetchMock.mockResolvedValueOnce(ok(draftView('id: old\n', 5)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'Spring Web'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }]
    await wizard.loadExistingDraft()
    expect(wizard.draftVersion).toBe(5)
    fetchMock.mockResolvedValueOnce(noContent())
    fetchMock.mockResolvedValueOnce(ok(draftView('id: old\n---\nid: web\n', 6)))

    const result = await wizard.saveToDraft()
    await settle()
    expect(result).toBe(true)
    const putCall = fetchMock.mock.calls.find((c) => c[0] === '/api/admin/config/starter' && (c[1] as RequestInit)?.method === 'PUT')
    const body = JSON.parse((putCall![1] as RequestInit).body as string)
    expect(body.contentYaml).toContain('---')
    expect(body.contentYaml.indexOf('id: old')).toBeLessThan(body.contentYaml.indexOf('---'))
    expect(((putCall![1] as RequestInit).headers as Record<string, string>)['If-Match']).toBe('5')
  })

  it('saveToDraft 未登录时报错不请求', async () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    const result = await wizard.saveToDraft()
    expect(result).toBe(false)
    expect(wizard.messageType).toBe('error')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('publish 先保存草稿再触发发布', async () => {
    fetchMock.mockResolvedValueOnce(ok(draftView(null, null)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'Spring Web'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }]
    await wizard.loadExistingDraft()
    fetchMock.mockResolvedValueOnce(noContent()) // PUT
    fetchMock.mockResolvedValueOnce(ok(draftView('id: web\n', 1))) // reload draft
    const pub: AdminPublishResult = { releaseId: 9, javaVersionCount: 4, starterCount: 6, templateOverrideCount: 1, conflictRuleCount: 3 }
    fetchMock.mockResolvedValueOnce(ok(pub)) // publish

    const result = await wizard.publish()
    await settle()
    expect(result).toBe(true)
    expect(wizard.actionState).toBe('done')
    expect(wizard.message).toContain('发布 #9')
    expect(fetchMock.mock.calls.some((c) => c[0] === '/api/admin/config/publish')).toBe(true)
  })

  it('publish 校验失败返回 4002 明细', async () => {
    fetchMock.mockResolvedValueOnce(ok(draftView(null, null)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'Spring Web'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }]
    await wizard.loadExistingDraft()
    fetchMock.mockResolvedValueOnce(noContent()) // PUT ok
    fetchMock.mockResolvedValueOnce(ok(draftView('id: web\n', 1)))
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: () => Promise.resolve({ code: 4002, message: '校验失败明细', timestamp: 1 }),
    } as unknown as Response)

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

  it('loadExistingDraft 草稿不存在时置空不报错', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false, status: 404,
      json: () => Promise.resolve({ code: 4001, message: 'not found', timestamp: 1 }),
    } as unknown as Response)
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    await wizard.loadExistingDraft()
    expect(wizard.existingDraft).toBe('')
    expect(wizard.draftVersion).toBeNull()
  })

  it('previewYaml 与 hasLinkages getter 反映联动', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.form.id = 'demo'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    expect(wizard.hasLinkages).toBe(false)
    expect(wizard.previewYaml).toContain('linkages: {}')
    wizard.form.configPackage = '{basePackage}.config'
    expect(wizard.hasLinkages).toBe(true)
    expect(wizard.previewYaml).toContain('config-package:')
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

  it('成功时设置 existingDraft 和 draftVersion', async () => {
    fetchMock.mockResolvedValueOnce(ok(draftView('id: web\n', 3)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    await wizard.loadExistingDraft()
    expect(wizard.existingDraft).toBe('id: web\n')
    expect(wizard.draftVersion).toBe(3)
  })

  it('草稿 contentYaml 为 null 时置空', async () => {
    fetchMock.mockResolvedValueOnce(ok(draftView(null, null)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    await wizard.loadExistingDraft()
    expect(wizard.existingDraft).toBe('')
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
    fetchMock.mockResolvedValueOnce(ok(draftView(null, null)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    await wizard.loadExistingDraft()
    // PUT 返回非 JSON 错误体，handleResponse 中 res.json() 抛 SyntaxError → 非 ApiError
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: () => Promise.reject(new SyntaxError('bad')),
    } as unknown as Response)
    const result = await wizard.saveToDraft()
    await settle()
    expect(result).toBe(false)
    expect(wizard.actionState).toBe('error')
    // putDraft 对非 JSON 错误体抛 ApiError（adminErrorFallback），所以 detail 含 HTTP
    expect(wizard.message).toContain('HTTP 500')
  })

  it('保存失败（ApiError）时展示后端明细', async () => {
    fetchMock.mockResolvedValueOnce(ok(draftView(null, null)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    await wizard.loadExistingDraft()
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: () => Promise.resolve({ code: 4002, message: '校验失败明细', timestamp: 1 }),
    } as unknown as Response)
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
    fetchMock.mockResolvedValueOnce(ok(draftView(null, null)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    await wizard.loadExistingDraft()
    fetchMock.mockResolvedValueOnce(noContent()) // PUT ok
    fetchMock.mockResolvedValueOnce(ok(draftView('id: web\n', 1))) // reload
    // publish 返回非 JSON 错误体
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 502,
      json: () => Promise.reject(new SyntaxError('bad')),
    } as unknown as Response)

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

  it('id 前后空格但草稿查重仍匹配', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingDraft = 'id: web\ncategory: web\n'
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

describe('starterConfigWizard id 草稿查重', () => {
  it('草稿为多文档时 id 在第二个文档也能查重', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingDraft = 'id: old\n---\nid: web\n'
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.step = 1
    expect(wizard.stepErrors.id).toContain('已存在')
  })

  it('id 在草稿中不存在时不报错', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingDraft = 'id: other\n'
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.step = 1
    expect(wizard.stepErrors.id).toBeUndefined()
  })

  it('草稿为空时不报重复', () => {
    const wizard = useStarterConfigWizardStore()
    wizard.existingDraft = ''
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.step = 1
    expect(wizard.stepErrors.id).toBeUndefined()
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

describe('starterConfigWizard saveToDraft 成功路径细节', () => {
  it('saveToDraft 追加时正确拼接 DOC_SEPARATOR', async () => {
    fetchMock.mockResolvedValueOnce(ok(draftView('id: old\n', 5)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'Spring Web'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }]
    await wizard.loadExistingDraft()
    fetchMock.mockResolvedValueOnce(noContent())
    fetchMock.mockResolvedValueOnce(ok(draftView('id: old\n---\nid: web\n', 6)))

    const result = await wizard.saveToDraft()
    await settle()
    expect(result).toBe(true)
    expect(wizard.actionState).toBe('done')
    expect(wizard.message).toContain('已写入')
    const putCall = fetchMock.mock.calls.find((c) => c[0] === '/api/admin/config/starter' && (c[1] as RequestInit)?.method === 'PUT')
    const body = JSON.parse((putCall![1] as RequestInit).body as string)
    expect(body.contentYaml).toContain('id: old')
    expect(body.contentYaml).toContain('---')
    expect(body.contentYaml).toContain('id: web')
    // 顺序：old 在前，--- 在中间，web 在后
    expect(body.contentYaml.indexOf('id: old')).toBeLessThan(body.contentYaml.indexOf('---'))
    expect(body.contentYaml.indexOf('---')).toBeLessThan(body.contentYaml.indexOf('id: web'))
  })

  it('saveToDraft 成功后重新加载草稿', async () => {
    fetchMock.mockResolvedValueOnce(ok(draftView(null, null)))
    const wizard = useStarterConfigWizardStore()
    wizard.setToken('tk')
    wizard.form.id = 'web'
    wizard.form.description = 'd'
    wizard.form.dependencies = [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }]
    await wizard.loadExistingDraft()
    fetchMock.mockResolvedValueOnce(noContent()) // PUT
    fetchMock.mockResolvedValueOnce(ok(draftView('id: web\n', 1))) // reload

    await wizard.saveToDraft()
    await settle()
    expect(wizard.existingDraft).toBe('id: web\n')
    expect(wizard.draftVersion).toBe(1)
  })
})
