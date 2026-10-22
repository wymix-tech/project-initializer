import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MetadataForm from '@/components/admin/MetadataForm.vue'
import StarterForm from '@/components/admin/StarterForm.vue'
import ConflictRuleForm from '@/components/admin/ConflictRuleForm.vue'
import MultiSelect from '@/components/admin/MultiSelect.vue'
import TemplateForm from '@/components/admin/TemplateForm.vue'
import PublishConsole from '@/components/admin/PublishConsole.vue'
import ImportExportPanel from '@/components/admin/ImportExportPanel.vue'
import { useAdminSession } from '@/composables/useAdminSession'

/**
 * 管理端表单组件单测（T6.2/T6.3）：各分区的读写请求契约、空态、增删行、
 * 「保存草稿」乐观锁、模板 CLOB 隔离、导入导出的失败明细展示。
 *
 * 每个组件都接收同一个 `useAdminSession()`，测试里显式创建并注入，
 * 保证与 `AdminPanel` 的会话语义一致（同一 token / 同一提示条）。
 */
const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(body === null ? '' : JSON.stringify(body)),
  } as unknown as Response
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
  await new Promise((r) => setTimeout(r, 0))
}

/**
 * 创建已登录的会话（token 已填、loggedIn 为 true）。
 *
 * @param token token 值
 */
function session(token = 'tk') {
  const s = useAdminSession(token)
  s.loggedIn.value = true
  return s
}

/**
 * 按 URL → 响应体挂载任一表单组件（注入已登录会话）。
 *
 * @param component 组件定义
 * @param table     URL（或 `METHOD URL`）→ 响应体映射
 */
async function mountForm(
  component: unknown,
  table: Record<string, unknown>,
): Promise<ReturnType<typeof mount>> {
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    const key = `${(init?.method ?? 'GET')} ${url}`
    if (key in table) return Promise.resolve(jsonResponse(table[key]))
    if (url in table) return Promise.resolve(jsonResponse(table[url]))
    return Promise.resolve(jsonResponse({}))
  })
  // Vue 的 mount 泛型在「组件变量」场景无法收窄 props，这里显式放宽以便表驱动测试
  const wrapper = mount(component as never, {
    props: { session: session() },
  } as never) as ReturnType<typeof mount>
  await settle()
  return wrapper
}

/** 取指定方法与 URL 的最近一次请求。 */
function lastCall(method: string, url: string): RequestInit | undefined {
  return [...fetchMock.mock.calls]
    .reverse()
    .find((c) => c[0] === url && ((c[1] as RequestInit | undefined)?.method ?? 'GET') === method)?.[1] as
    | RequestInit
    | undefined
}

describe('MetadataForm 元数据表单', () => {
  /**
   * 元数据视图夹具。
   *
   * 必须是**工厂**：表单把响应对象直接挂进响应式 ref 并就地编辑，
   * 共享同一个字面量会让后序用例看到前序用例的修改（跨用例污染）。
   */
  const view = () => ({
    javaVersions: [
      { id: '17', label: 'Java 17', deprecated: false, recommendedBootVersion: '3.2.5', sort: 0 },
      { id: '21', label: 'Java 21', deprecated: true, recommendedBootVersion: null, sort: 1 },
    ],
    bootVersions: [
      {
        id: '3.2.5',
        label: 'Spring Boot 3.2.5',
        official: true,
        supportedJavaVersions: ['17', '21'],
        starters: ['web'],
        frameworkParent: { groupId: 'com.x', artifactId: 'parent', version: '1.0.0' },
        sort: 0,
      },
    ],
  })

  it('载入后渲染 Java / Boot 版本行', async () => {
    const wrapper = await mountForm(MetadataForm, {
      '/api/admin/metadata': view(),
      '/api/admin/config/metadata': { kind: 'metadata', contentYaml: null, version: 4, updatedAt: null },
    })
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    const inputs = wrapper.findAll('tbody input[type="text"]')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('17')
    expect(wrapper.find('.ver').text()).toContain('4')
  })

  it('新增 / 删除 Java 版本行', async () => {
    const wrapper = await mountForm(MetadataForm, {
      '/api/admin/metadata': { javaVersions: [], bootVersions: [] },
      '/api/admin/config/metadata': { kind: 'metadata', contentYaml: null, version: null, updatedAt: null },
    })
    expect(wrapper.findAll('tbody tr')).toHaveLength(1) // 「暂无」占位行
    const addJava = wrapper.findAll('button').find((b) => b.text().includes('新增 Java 版本'))
    await addJava!.trigger('click')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)

    await wrapper.find('tbody .btn-icon').trigger('click')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
  })

  it('保存时整体提交并携带 If-Match', async () => {
    const wrapper = await mountForm(MetadataForm, {
      '/api/admin/metadata': view(),
      '/api/admin/config/metadata': { kind: 'metadata', contentYaml: null, version: 4, updatedAt: null },
      'PUT /api/admin/metadata': { kind: 'metadata', rows: 5, version: 5, summary: 'Java 2 个 / Boot 1 个' },
    })
    const saveBtn = wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))
    await saveBtn!.trigger('click')
    await settle()
    const init = lastCall('PUT', '/api/admin/metadata')!
    expect((init.headers as Record<string, string>)['If-Match']).toBe('4')
    const body = JSON.parse(init.body as string)
    expect(body.operator).toBe('admin')
    expect(body.javaVersions).toHaveLength(2)
    expect(body.bootVersions[0].frameworkParent.groupId).toBe('com.x')
    expect(wrapper.find('.summary').text()).toContain('Java 2 个')
  })

  it('保存失败（4002）时提示条展示明细', async () => {
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/api/admin/metadata' && init?.method === 'PUT') {
        return Promise.resolve(jsonResponse({ code: 4002, message: 'Boot 版本 x 引用了未定义的 Java 版本 y', timestamp: 1 }, false, 400))
      }
      if (url === '/api/admin/metadata') return Promise.resolve(jsonResponse({ javaVersions: [], bootVersions: [] }))
      return Promise.resolve(jsonResponse({ kind: 'metadata', contentYaml: null, version: null, updatedAt: null }))
    })
    const s = session()
    const wrapper = mount(MetadataForm, { props: { session: s } })
    await settle()
    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    expect(s.message.value).toContain('引用了未定义的 Java 版本')
    expect(s.messageType.value).toBe('error')
  })

  it('新增 Boot 版本行并编辑兼容 Java / starter 白名单', async () => {
    const wrapper = await mountForm(MetadataForm, {
      '/api/admin/metadata': { javaVersions: [], bootVersions: [] },
      '/api/admin/config/metadata': { kind: 'metadata', contentYaml: null, version: null, updatedAt: null },
      'PUT /api/admin/metadata': { kind: 'metadata', rows: 1, version: 1, summary: 's' },
    })
    await wrapper.findAll('button').find((b) => b.text().includes('+ 新增 Boot 版本'))!.trigger('click')
    expect(wrapper.findAll('.boot-card')).toHaveLength(1)

    const card = wrapper.find('.boot-card')
    await card.find('input[placeholder="如 3.2.5"]').setValue('3.2.5')
    await card.find('input[placeholder="如 17, 21"]').setValue('17, 21, 17')
    await card.find('input[placeholder="如 web, actuator"]').setValue('web, actuator')
    // 勾选框架父 POM 后才出现三字段
    const checkboxes = card.findAll('input[type="checkbox"]')
    await checkboxes[checkboxes.length - 1]!.setValue(true)
    expect(card.find('input[placeholder*="revision"]').exists()).toBe(true)

    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    const body = JSON.parse(lastCall('PUT', '/api/admin/metadata')!.body as string)
    expect(body.bootVersions[0].id).toBe('3.2.5')
    // 兼容 Java 去重
    expect(body.bootVersions[0].supportedJavaVersions).toEqual(['17', '21'])
    expect(body.bootVersions[0].starters).toEqual(['web', 'actuator'])
    expect(body.bootVersions[0].frameworkParent).toEqual({ groupId: '', artifactId: '', version: '' })
  })

  it('兼容 Java / starter 白名单输入框可以逐字键入逗号', async () => {
    const wrapper = await mountForm(MetadataForm, {
      '/api/admin/metadata': { javaVersions: [], bootVersions: [] },
      '/api/admin/config/metadata': { kind: 'metadata', contentYaml: null, version: null, updatedAt: null },
      'PUT /api/admin/metadata': { kind: 'metadata', rows: 1, version: 1, summary: 's' },
    })
    await wrapper.findAll('button').find((b) => b.text().includes('+ 新增 Boot 版本'))!.trigger('click')
    const card = wrapper.find('.boot-card')
    const javaInput = card.find('input[placeholder="如 17, 21"]')
    const starterInput = card.find('input[placeholder="如 web, actuator"]')

    // 逐字符键入：每个字符后都不能被规范化回写吞掉
    for (const text of ['1', '17', '17,', '17, ', '17, 2', '17, 21']) {
      await javaInput.setValue(text)
      expect((javaInput.element as HTMLInputElement).value).toBe(text)
    }
    for (const text of ['w', 'web', 'web,', 'web, a', 'web, actuator']) {
      await starterInput.setValue(text)
      expect((starterInput.element as HTMLInputElement).value).toBe(text)
    }

    await javaInput.trigger('blur')
    await starterInput.trigger('blur')
    expect((javaInput.element as HTMLInputElement).value).toBe('17,21')
    expect((starterInput.element as HTMLInputElement).value).toBe('web,actuator')

    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    const body = JSON.parse(lastCall('PUT', '/api/admin/metadata')!.body as string)
    expect(body.bootVersions[0].supportedJavaVersions).toEqual(['17', '21'])
    expect(body.bootVersions[0].starters).toEqual(['web', 'actuator'])
  })

  it('清空列表输入框后提交空数组', async () => {
    const wrapper = await mountForm(MetadataForm, {
      '/api/admin/metadata': view(),
      '/api/admin/config/metadata': { kind: 'metadata', contentYaml: null, version: null, updatedAt: null },
      'PUT /api/admin/metadata': { kind: 'metadata', rows: 1, version: 1, summary: 's' },
    })
    const card = wrapper.find('.boot-card')
    const starterInput = card.find('input[placeholder="如 web, actuator"]')
    await starterInput.setValue('')
    await starterInput.trigger('blur')
    expect((starterInput.element as HTMLInputElement).value).toBe('')

    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    const body = JSON.parse(lastCall('PUT', '/api/admin/metadata')!.body as string)
    expect(body.bootVersions[0].starters).toEqual([])
    expect(body.bootVersions[0].supportedJavaVersions).toEqual(['17', '21'])
  })

  it('删除 Boot 版本行', async () => {
    const wrapper = await mountForm(MetadataForm, {
      '/api/admin/metadata': view(),
      '/api/admin/config/metadata': { kind: 'metadata', contentYaml: null, version: null, updatedAt: null },
    })
    expect(wrapper.findAll('.boot-card')).toHaveLength(1)
    await wrapper.find('.boot-card .btn-icon').trigger('click')
    expect(wrapper.findAll('.boot-card')).toHaveLength(0)
  })

  it('草稿版本读取失败时保持 null 不阻断编辑', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/admin/metadata') return Promise.resolve(jsonResponse(view()))
      if (url === '/api/admin/config/metadata') {
        return Promise.resolve(jsonResponse({ code: 4001, message: '草稿不存在', timestamp: 1 } as never, false, 404))
      }
      return Promise.resolve(jsonResponse({}))
    })
    const s = session()
    const wrapper = mount(MetadataForm, { props: { session: s } })
    await settle()
    expect(wrapper.find('.ver').exists()).toBe(false)
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  })

  it('取消勾选框架父 POM 时整节置空', async () => {
    const wrapper = await mountForm(MetadataForm, {
      '/api/admin/metadata': view(),
      '/api/admin/config/metadata': { kind: 'metadata', contentYaml: null, version: null, updatedAt: null },
      'PUT /api/admin/metadata': { kind: 'metadata', rows: 4, version: 5, summary: 's' },
    })
    // Boot 卡片内的选择器：框架父 POM 三字段只在勾选时才渲染
    const frameworkInput = wrapper.find('.boot-card input[placeholder*="revision"]')
    expect(frameworkInput.exists()).toBe(true)

    const checkboxes = wrapper.findAll('.boot-card input[type="checkbox"]')
    const frameworkToggle = checkboxes[checkboxes.length - 1]!
    expect((frameworkToggle.element as HTMLInputElement).checked).toBe(true)
    await frameworkToggle.setValue(false)
    expect(wrapper.find('.boot-card input[placeholder*="revision"]').exists()).toBe(false)

    // 取消后提交的 frameworkParent 为 null（不写成空对象三字段）
    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    const body = JSON.parse(lastCall('PUT', '/api/admin/metadata')!.body as string)
    expect(body.bootVersions[0].frameworkParent).toBeNull()
  })
})

describe('StarterForm starter 清单表单', () => {
  const starter = {
    id: 'web',
    category: 'web',
    maintainer: 'official',
    description: 'Spring Web',
    required: true,
    hidden: true,
    configPackage: null,
    dependencies: [{ groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: null }],
    requires: [['a'], ['b', 'c']],
    bootVersions: ['3.2.5'],
    linkages: { templates: [], ymlFragments: [], abstractClasses: [] },
    sort: 0,
  }

  it('渲染清单条目并保留 hidden / required 开关状态', async () => {
    const wrapper = await mountForm(StarterForm, {
      '/api/admin/starters': { starters: [starter] },
      '/api/admin/config/starter': { kind: 'starter', contentYaml: null, version: 2, updatedAt: null },
    })
    const toggles = wrapper.findAll('.starter-head input[type="checkbox"]')
    expect((toggles[0]!.element as HTMLInputElement).checked).toBe(true) // required
    expect((toggles[1]!.element as HTMLInputElement).checked).toBe(true) // hidden
  })

  it('保存时提交 hidden 字段（PR #40 语义不丢）', async () => {
    const wrapper = await mountForm(StarterForm, {
      '/api/admin/starters': { starters: [starter] },
      '/api/admin/config/starter': { kind: 'starter', contentYaml: null, version: 2, updatedAt: null },
      'PUT /api/admin/starters': { kind: 'starter', rows: 3, version: 3, summary: 'starter 1 项 / 依赖 1 条' },
    })
    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    const body = JSON.parse(lastCall('PUT', '/api/admin/starters')!.body as string)
    expect(body.starters[0].hidden).toBe(true)
    expect(body.starters[0].required).toBe(true)
    expect(body.starters[0].requires).toEqual([['a'], ['b', 'c']])
  })

  it('requires 文本可编辑并解析为 AND-of-OR 分组', async () => {
    const wrapper = await mountForm(StarterForm, {
      '/api/admin/starters': { starters: [starter] },
      '/api/admin/config/starter': { kind: 'starter', contentYaml: null, version: null, updatedAt: null },
      'PUT /api/admin/starters': { kind: 'starter', rows: 1, version: 1, summary: 's' },
    })
    const textarea = wrapper.find('textarea')
    expect((textarea.element as HTMLTextAreaElement).value).toBe('a\nb, c')
    await textarea.setValue('x, y\nz')
    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    const body = JSON.parse(lastCall('PUT', '/api/admin/starters')!.body as string)
    expect(body.starters[0].requires).toEqual([['x', 'y'], ['z']])
  })

  it('requires 编辑时末尾换行保留，保存时才规范化', async () => {
    const wrapper = await mountForm(StarterForm, {
      '/api/admin/starters': { starters: [starter] },
      '/api/admin/config/starter': { kind: 'starter', contentYaml: null, version: null, updatedAt: null },
      'PUT /api/admin/starters': { kind: 'starter', rows: 1, version: 1, summary: 's' },
    })
    const textarea = wrapper.find('textarea')
    await textarea.setValue('x\n')
    // setValue 会触发 change，模型会过滤掉末尾空组，但编辑态草稿仍保留换行
    expect((textarea.element as HTMLTextAreaElement).value).toBe('x\n')
    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    const body = JSON.parse(lastCall('PUT', '/api/admin/starters')!.body as string)
    expect(body.starters[0].requires).toEqual([['x']])
  })

  it('空白 id 条目在保存成功后被剔除', async () => {
    const wrapper = await mountForm(StarterForm, {
      '/api/admin/starters': { starters: [] },
      '/api/admin/config/starter': { kind: 'starter', contentYaml: null, version: null, updatedAt: null },
      'PUT /api/admin/starters': { kind: 'starter', rows: 0, version: 1, summary: 's' },
    })
    await wrapper.findAll('button').find((b) => b.text().includes('新增 starter 条目'))!.trigger('click')
    expect(wrapper.findAll('.starter-card')).toHaveLength(1)
    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    expect(wrapper.findAll('.starter-card')).toHaveLength(0)
  })

  it('切到新增向导模式渲染 StarterConfigWizard', async () => {
    const wrapper = await mountForm(StarterForm, {
      '/api/admin/starters': { starters: [] },
      '/api/admin/config/starter': { kind: 'starter', contentYaml: null, version: null, updatedAt: null },
    })
    const wizardBtn = wrapper.findAll('button.mode-btn').find((b) => b.text().includes('新增向导'))
    await wizardBtn!.trigger('click')
    await settle()
    expect(wrapper.findComponent({ name: 'StarterConfigWizard' }).exists()).toBe(true)
  })

  it('增删依赖行与联动行', async () => {
    const wrapper = await mountForm(StarterForm, {
      '/api/admin/starters': { starters: [starter] },
      '/api/admin/config/starter': { kind: 'starter', contentYaml: null, version: null, updatedAt: null },
    })
    expect(wrapper.findAll('.pair-row')).toHaveLength(1) // 依赖 1 条，联动为空
    const addDep = wrapper.findAll('button').find((b) => b.text().includes('+ 增加依赖'))
    await addDep!.trigger('click')
    expect(wrapper.findAll('.pair-row')).toHaveLength(2)

    const addTpl = wrapper.findAll('button').find((b) => b.text().includes('+ 增加模板'))
    await addTpl!.trigger('click')
    expect(wrapper.findAll('.pair-row')).toHaveLength(3)
    // 删除新增的模板行
    await wrapper.findAll('.pair-row .btn-icon')[2]!.trigger('click')
    expect(wrapper.findAll('.pair-row')).toHaveLength(2)
  })
})

describe('ConflictRuleForm 冲突规则表单', () => {
  const rule = { id: 'web-vs-webflux', type: 'pair', starters: ['web', 'webflux'], message: '不可同时选择', sort: 0 }

  it('渲染规则并保存', async () => {
    const wrapper = await mountForm(ConflictRuleForm, {
      '/api/admin/conflict-rules': { rules: [rule] },
      '/api/admin/config/conflict-rule': { kind: 'conflict-rule', contentYaml: null, version: 1, updatedAt: null },
      '/api/admin/starters': { starters: [{ id: 'web' }, { id: 'webflux' }] },
      'PUT /api/admin/conflict-rules': { kind: 'conflict-rule', rows: 3, version: 2, summary: '规则 1 条 / 涉及 starter 2 项' },
    })
    // 规则 ID + starter 列表 + 提示文案
    expect(wrapper.findAll('.rule-card input[type="text"]')).toHaveLength(3)
    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click')
    await settle()
    const body = JSON.parse(lastCall('PUT', '/api/admin/conflict-rules')!.body as string)
    expect(body.rules[0].starters).toEqual(['web', 'webflux'])
    expect(wrapper.find('.summary').text()).toContain('规则 1 条')
  })

  it('切换 pair/group 时调整 starter 槽位数', async () => {
    const wrapper = await mountForm(ConflictRuleForm, {
      '/api/admin/conflict-rules': { rules: [] },
      '/api/admin/config/conflict-rule': { kind: 'conflict-rule', contentYaml: null, version: null, updatedAt: null },
      '/api/admin/starters': { starters: [{ id: 'a' }, { id: 'b' }, { id: 'c' }] },
    })
    await wrapper.findAll('button').find((b) => b.text().includes('新增规则'))!.trigger('click')
    const select = wrapper.find('.rule-card select')
    expect((select.element as HTMLSelectElement).value).toBe('pair')

    // 涉及 starter 改由 MultiSelect 承载：断言其 modelValue 而非逗号分隔文本
    expect(wrapper.findComponent(MultiSelect).props('modelValue')).toEqual(['', '']) // 两个空槽位

    // 选 3 个后切 group 保留全部；切回 pair 收敛为恰好 2 个
    await wrapper.findComponent(MultiSelect).vm.$emit('update:modelValue', ['a', 'b', 'c'])
    await select.setValue('group')
    expect(wrapper.findComponent(MultiSelect).props('modelValue')).toEqual(['a', 'b', 'c'])

    await select.setValue('pair')
    expect(wrapper.findComponent(MultiSelect).props('modelValue')).toEqual(['a', 'b'])
  })

  it('涉及 starter 可从候选清单勾选，也可手输未登记项', async () => {
    const wrapper = await mountForm(ConflictRuleForm, {
      '/api/admin/conflict-rules': { rules: [] },
      '/api/admin/config/conflict-rule': { kind: 'conflict-rule', contentYaml: null, version: null, updatedAt: null },
      '/api/admin/starters': { starters: [{ id: 'web' }, { id: 'webflux' }] },
    })
    await wrapper.findAll('button').find((b) => b.text().includes('新增规则'))!.trigger('click')

    // 候选清单来自 starter 草稿 id 集合（去重排序）
    const ms = wrapper.findComponent(MultiSelect)
    expect(ms.props('options')).toEqual(['web', 'webflux'])

    // 弹层按 ▾ 打开，勾选「web」→ 追加到 modelValue（首槽位为空串需先清掉）
    await ms.find('.ms-caret').trigger('click')
    expect(wrapper.find('.ms-menu').exists()).toBe(true)
    const boxes = wrapper.findAll('.ms-menu input[type="checkbox"]')
    expect(boxes).toHaveLength(2)
    await boxes[0]!.setValue(true)
    // 勾选优先填掉第一个空槽位：['', ''] → ['web', '']（pair 仍恰好 2 个）
    expect(wrapper.findComponent(MultiSelect).props('modelValue')).toEqual(['web', ''])

    // 手输未登记 starter：Enter 提交 draft，填掉剩余空槽位
    await ms.find('.ms-input').setValue('custom-x')
    await ms.find('.ms-input').trigger('keydown.enter')
    expect(wrapper.findComponent(MultiSelect).props('modelValue')).toEqual(['web', 'custom-x'])
  })

  it('删除规则行', async () => {
    const wrapper = await mountForm(ConflictRuleForm, {
      '/api/admin/conflict-rules': { rules: [rule] },
      '/api/admin/config/conflict-rule': { kind: 'conflict-rule', contentYaml: null, version: null, updatedAt: null },
    })
    await wrapper.find('.rule-card .btn-icon').trigger('click')
    expect(wrapper.findAll('.rule-card')).toHaveLength(0)
  })
})

describe('TemplateForm 模板表单', () => {
  it('列表只取 name/updatedAt（不含 content）', async () => {
    const wrapper = await mountForm(TemplateForm, {
      '/api/admin/templates': { templates: [{ name: 'pom.xml.ftl', updatedAt: '2026-09-12T00:00:00Z', sort: 0 }] },
      '/api/admin/config/template': { kind: 'template', contentYaml: null, version: 2, updatedAt: null },
    })
    const items = wrapper.findAll('.tpl-list li')
    expect(items).toHaveLength(1)
    expect(items[0]!.text()).toContain('pom.xml.ftl')
    expect(wrapper.find('textarea.tpl-content').exists()).toBe(false)
  })

  it('选中模板后按需拉取全文并回填编辑区', async () => {
    const wrapper = await mountForm(TemplateForm, {
      '/api/admin/templates': { templates: [{ name: 'pom.xml.ftl', updatedAt: null, sort: 0 }] },
      '/api/admin/templates/pom.xml.ftl': { name: 'pom.xml.ftl', content: '<project/>', updatedAt: null, sort: 0 },
      '/api/admin/config/template': { kind: 'template', contentYaml: null, version: 2, updatedAt: null },
    })
    await wrapper.find('.tpl-list li').trigger('click')
    await settle()
    expect((wrapper.find('textarea.tpl-content').element as HTMLTextAreaElement).value).toBe('<project/>')
    expect(wrapper.find('.tag-ftl').text()).toBe('FreeMarker')
  })

  it('yml 片段标注为非 FreeMarker 模板', async () => {
    const wrapper = await mountForm(TemplateForm, {
      '/api/admin/templates': { templates: [{ name: 'starters/x/application-x.yml', updatedAt: null, sort: 0 }] },
      '/api/admin/templates/starters%2Fx%2Fapplication-x.yml': {
        name: 'starters/x/application-x.yml', content: 'host: ${REDIS_HOST:localhost}', updatedAt: null, sort: 0,
      },
      '/api/admin/config/template': { kind: 'template', contentYaml: null, version: null, updatedAt: null },
    })
    await wrapper.find('.tpl-list li').trigger('click')
    await settle()
    expect(wrapper.find('.tag').text()).toBe('yml 片段')
    expect(wrapper.find('.preview pre').text()).toContain('${REDIS_HOST:localhost}')
    const help = wrapper.find('.placeholder-help')
    expect(help.find('summary').text()).toContain('占位参数说明')
    expect(help.findAll('tbody tr').map((row) => row.find('td').text())).toEqual([
      'artifact', 'group', 'basePackage', 'packagePath', 'bootVersion', 'javaVersion',
      'parentGroupId', 'parentArtifactId', 'parentVersion', 'configPackage', '自定义 alias',
      'starters', 'starterDependencies',
    ])
    expect(help.text()).toContain('未知参数和带默认值的 Spring 占位符保留原文')
    expect(help.text()).toContain('target 和 config-package 使用单层大括号')
    expect(help.find('pre').text()).toContain('name: "${artifact}"')
  })

  it('保存单模板走 PUT /templates/{name} 并保留其余模板', async () => {
    const wrapper = await mountForm(TemplateForm, {
      '/api/admin/templates': { templates: [{ name: 'a.ftl', updatedAt: null, sort: 0 }] },
      '/api/admin/templates/a.ftl': { name: 'a.ftl', content: 'old', updatedAt: null, sort: 0 },
      '/api/admin/config/template': { kind: 'template', contentYaml: null, version: 3, updatedAt: null },
      'PUT /api/admin/templates/a.ftl': { kind: 'template', rows: 1, version: 4, summary: '模板 1 个' },
    })
    await wrapper.find('.tpl-list li').trigger('click')
    await settle()
    await wrapper.find('textarea.tpl-content').setValue('new')
    await wrapper.findAll('button').find((b) => b.text().includes('保存模板'))!.trigger('click')
    await settle()
    const init = lastCall('PUT', '/api/admin/templates/a.ftl')!
    expect((init.headers as Record<string, string>)['If-Match']).toBe('3')
    expect(JSON.parse(init.body as string)).toEqual({ name: 'a.ftl', content: 'new', operator: 'admin', sort: 0 })
  })

  it('新建模板名后可编辑，保存时按名 upsert', async () => {
    const wrapper = await mountForm(TemplateForm, {
      '/api/admin/templates': { templates: [] },
      '/api/admin/config/template': { kind: 'template', contentYaml: null, version: null, updatedAt: null },
      'PUT /api/admin/templates/new.ftl': { kind: 'template', rows: 1, version: 1, summary: 's' },
    })
    await wrapper.find('.new-row input').setValue('new.ftl')
    await wrapper.findAll('button').find((b) => b.text() === '新建')!.trigger('click')
    expect(wrapper.find('textarea.tpl-content').exists()).toBe(true)
    await wrapper.find('textarea.tpl-content').setValue('x')
    await wrapper.findAll('button').find((b) => b.text().includes('保存模板'))!.trigger('click')
    await settle()
    expect(lastCall('PUT', '/api/admin/templates/new.ftl')).toBeTruthy()
  })

  it('删除模板需二次确认，取消则不请求', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = await mountForm(TemplateForm, {
      '/api/admin/templates': { templates: [{ name: 'a.ftl', updatedAt: null, sort: 0 }] },
      '/api/admin/templates/a.ftl': { name: 'a.ftl', content: 'x', updatedAt: null, sort: 0 },
      '/api/admin/config/template': { kind: 'template', contentYaml: null, version: null, updatedAt: null },
    })
    await wrapper.find('.tpl-list li').trigger('click')
    await settle()
    await wrapper.findAll('button').find((b) => b.text() === '删除')!.trigger('click')
    await settle()
    expect(fetchMock.mock.calls.some((c) => (c[1] as RequestInit)?.method === 'DELETE')).toBe(false)
    vi.restoreAllMocks()
  })

  it('确认删除时走 DELETE 并清空编辑区', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = await mountForm(TemplateForm, {
      '/api/admin/templates': { templates: [{ name: 'a.ftl', updatedAt: null, sort: 0 }] },
      '/api/admin/templates/a.ftl': { name: 'a.ftl', content: 'x', updatedAt: null, sort: 0 },
      '/api/admin/config/template': { kind: 'template', contentYaml: null, version: 5, updatedAt: null },
      'DELETE /api/admin/templates/a.ftl?operator=admin': { kind: 'template', rows: 0, version: 6, summary: 's' },
      'DELETE /api/admin/templates/a.ftl': { kind: 'template', rows: 0, version: 6, summary: 's' },
    })
    await wrapper.find('.tpl-list li').trigger('click')
    await settle()
    await wrapper.findAll('button').find((b) => b.text() === '删除')!.trigger('click')
    await settle()
    const delCall = fetchMock.mock.calls.find((c) => (c[1] as RequestInit)?.method === 'DELETE')
    expect(delCall).toBeTruthy()
    expect(wrapper.find('textarea.tpl-content').exists()).toBe(false)
    vi.restoreAllMocks()
  })
})

describe('PublishConsole 发布台', () => {
  it('发布成功提示发布结果并刷新历史', async () => {
    const s = session()
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/admin/config/publish') {
        return Promise.resolve(jsonResponse({ releaseId: 9, javaVersionCount: 2, starterCount: 5, templateOverrideCount: 1, conflictRuleCount: 3 }))
      }
      if (url === '/api/admin/releases') return Promise.resolve(jsonResponse([{ id: 9, releasedBy: 'admin', releasedAt: null, remark: null }]))
      return Promise.resolve(jsonResponse([]))
    })
    const wrapper = mount(PublishConsole, { props: { session: s, operator: 'admin' } })
    await settle()
    // 发布历史 1 条 + 审计空态占位 1 条
    expect(wrapper.findAll('.history-grid > div')[0]!.findAll('.list li')).toHaveLength(1)
    await wrapper.findAll('button').find((b) => b.text() === '发布')!.trigger('click')
    await settle()
    expect(s.message.value).toContain('发布 #9')
  })

  it('发布校验失败时提示条展示 4002 明细', async () => {
    const s = session()
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/admin/config/publish') {
        return Promise.resolve(jsonResponse({ code: 4002, message: 'starter X 模板缺失: a.ftl', timestamp: 1 }, false, 400))
      }
      return Promise.resolve(jsonResponse([]))
    })
    const wrapper = mount(PublishConsole, { props: { session: s, operator: 'admin' } })
    await settle()
    await wrapper.findAll('button').find((b) => b.text() === '发布')!.trigger('click')
    await settle()
    expect(s.message.value).toContain('模板缺失')
    expect(s.messageType.value).toBe('error')
  })

  it('回滚按钮走 rollback 接口', async () => {
    const s = session()
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/admin/releases/7/rollback') {
        return Promise.resolve(jsonResponse({ releaseId: 10, javaVersionCount: 1, starterCount: 1, templateOverrideCount: 0, conflictRuleCount: 0 }))
      }
      if (url === '/api/admin/releases') return Promise.resolve(jsonResponse([{ id: 7, releasedBy: null, releasedAt: null, remark: null }]))
      return Promise.resolve(jsonResponse([]))
    })
    const wrapper = mount(PublishConsole, { props: { session: s, operator: 'admin' } })
    await settle()
    await wrapper.find('.list button').trigger('click')
    await settle()
    expect(s.message.value).toContain('#10')
  })

  it('制品库同步展示后端摘要', async () => {
    const s = session()
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/admin/sync') return Promise.resolve(jsonResponse({ summary: '同步完成：采纳 3 条' }))
      return Promise.resolve(jsonResponse([]))
    })
    const wrapper = mount(PublishConsole, { props: { session: s, operator: 'admin' } })
    await settle()
    await wrapper.findAll('button').find((b) => b.text() === '制品库同步')!.trigger('click')
    await settle()
    expect(s.message.value).toContain('采纳 3 条')
  })

  it('无发布历史与审计时展示空态', async () => {
    const wrapper = await mountForm(PublishConsole, { '/api/admin/releases': [], '/api/admin/audits': [] })
    expect(wrapper.findAll('.empty').length).toBeGreaterThanOrEqual(2)
  })
})

describe('ImportExportPanel 导入导出面板', () => {
  it('导出成功后展示文件名', async () => {
    const s = session()
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      blob: () => Promise.resolve(new Blob(['zip'])),
      headers: { get: () => 'attachment; filename="initializer-config.zip"' },
    } as unknown as Response)
    URL.createObjectURL = vi.fn(() => 'blob:mock')
    URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const wrapper = mount(ImportExportPanel, { props: { session: s, operator: 'admin' } })
    await wrapper.findAll('button').find((b) => b.text().includes('下载 ZIP 归档'))!.trigger('click')
    await settle()
    expect(wrapper.find('.file-name').text()).toBe('initializer-config.zip')
    vi.restoreAllMocks()
  })

  it('选择文件后可导入并展示写入行数与版本', async () => {
    const s = session()
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ rows: 12, versions: { metadata: 3, starter: 5 }, summary: '元数据 2 项；starter 3 项' }),
    )
    const wrapper = mount(ImportExportPanel, { props: { session: s, operator: 'admin' } })
    const input = wrapper.find('input[type="file"]')
    const file = new File(['zip'], 'cfg.zip', { type: 'application/zip' })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await wrapper.findAll('button').find((b) => b.text().includes('导入到草稿'))!.trigger('click')
    await settle()
    expect(wrapper.find('.result').text()).toContain('12')
    expect(wrapper.find('.result').text()).toContain('metadata = 3')
  })

  it('未选文件时导入按钮禁用', async () => {
    const wrapper = await mountForm(ImportExportPanel, {})
    const btn = wrapper.findAll('button').find((b) => b.text().includes('导入到草稿'))!
    expect((btn.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('导入失败（4008 路径穿越）时展示后端明细', async () => {
    const s = session()
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: () => Promise.resolve({ code: 4008, message: '归档非法: 路径穿越 templates/../../x.ftl', timestamp: 1 }),
    } as unknown as Response)
    const wrapper = mount(ImportExportPanel, { props: { session: s, operator: 'admin' } })
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['zip'], 'x.zip')] })
    await input.trigger('change')
    await wrapper.findAll('button').find((b) => b.text().includes('导入到草稿'))!.trigger('click')
    await settle()
    expect(s.message.value).toContain('路径穿越')
    expect(wrapper.find('.result').exists()).toBe(false)
  })
})
