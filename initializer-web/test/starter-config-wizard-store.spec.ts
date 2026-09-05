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
