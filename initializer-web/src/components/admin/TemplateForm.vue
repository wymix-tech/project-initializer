<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminStructuredApi } from '@/api/client'
import type { TemplateSummary } from '@/types/admin'
import { useAdminSession } from '@/composables/useAdminSession'

/**
 * 模板表单（T6.2）：DB 全量模板集（`cfg_template`）的列表浏览 + 在线编辑 + 预览。
 *
 * <p><b>CLOB 隔离</b>：列表只取 `name / updatedAt / sort`（`GET /api/admin/templates`），
 * 全文按需走 `GET /api/admin/templates/{name}`；保存走单模板幂等 upsert
 * （`PUT /api/admin/templates/{name}`），删除走 `DELETE /api/admin/templates/{name}`。
 *
 * <p><b>唯一数据源</b>（T5 语义）：模板集就是数据库里的那一份，生成期不再回退 classpath 内置模板；
 * 删掉模板再发布会让引用它的 starter 在生成时报 3001（缺失清单），因此预览区复用了
 * 「模板被谁引用」的提示（由后端模板可达性校验口径给出，前端只做静态提示）。
 *
 * <p><b>`*.ftl` 才做 FreeMarker 语法校验</b>：`application-*.yml` 片段含 Spring 占位符
 * `${VAR:default}`，绝不能按模板解析；前端预览因此不做语法高亮/校验，仅做纯文本预览。
 */
const props = defineProps<{ session: ReturnType<typeof useAdminSession> }>()

const loading = ref(false)
const saving = ref(false)
const version = ref<number | null>(null)
const templates = ref<TemplateSummary[]>([])
const summary = ref('')

/** 当前选中的模板名（null = 未选中，编辑区展示空态）。 */
const selected = ref<string | null>(null)
/** 编辑区全文（选中模板后按需拉取）。 */
const content = ref('')
/** 是否有未保存改动（与载入时快照比对）。 */
const loadedContent = ref('')

const dirty = computed(() => content.value !== loadedContent.value)

/** 与 ProjectGenerator.buildModel / LinkageRenderer 的实际上下文保持一致。 */
const parameters = [
  { name: 'artifact', description: '项目 artifactId', example: 'demo-app', scope: '全部模板及 YAML 片段' },
  { name: 'group', description: '项目 groupId', example: 'com.example', scope: '全部模板及 YAML 片段' },
  { name: 'basePackage', description: '项目基础包名', example: 'com.example.demo', scope: '全部模板及 YAML 片段' },
  { name: 'packagePath', description: '基础包名对应的目录', example: 'com/example/demo', scope: '全部模板及 YAML 片段' },
  { name: 'bootVersion', description: '所选 Spring Boot 版本 ID', example: '3.2.5', scope: '全部模板及 YAML 片段' },
  { name: 'javaVersion', description: '所选 Java 版本 ID', example: '17', scope: '全部模板及 YAML 片段' },
  { name: 'parentGroupId', description: '框架父 POM 的 groupId', example: 'org.springframework.boot', scope: '全部模板及 YAML 片段' },
  { name: 'parentArtifactId', description: '框架父 POM 的 artifactId', example: 'spring-boot-starter-parent', scope: '全部模板及 YAML 片段' },
  { name: 'parentVersion', description: '框架父 POM 版本；未配置时使用 Boot 版本', example: '3.2.5', scope: '全部模板及 YAML 片段' },
  { name: 'configPackage', description: '当前 starter 的配置包名', example: 'com.example.demo.config', scope: '仅声明 config-package 的 starter 联动模板及 YAML 片段' },
  { name: '自定义 alias', description: '当前 starter 的 abstract-classes[].alias，值为对应 fqcn', example: '${redisSupport} → com.company.RedisSupport', scope: '仅声明该 alias 的 starter 联动模板及 YAML 片段' },
  { name: 'starters', description: '所选 starter ID 列表（含自动补齐的必选项）', example: '<#list starters as id>${id}</#list>', scope: '仅 FreeMarker；不能直接作为标量插值' },
  { name: 'starterDependencies', description: '依赖列表：groupId、artifactId、可选 version', example: '<#list starterDependencies as dep>${dep.artifactId}</#list>', scope: '仅 FreeMarker；version 使用 dep.version! 或先检查是否存在' },
]
const parameterSyntax = '${artifact}'
const yamlExample = 'app:\n  name: "${artifact}"\n  base-package: "${basePackage}"\n  host: "${REDIS_HOST:localhost}"'
const yamlEscape = '\\${artifact}'
const ftlEscape = '${r"${REDIS_HOST:localhost}"}'
const pathExample = 'src/main/java/{packagePath}/config/AppConfig.java'

/** 新建模板名输入框内容。 */
const newName = ref('')

/** 选中模板的排序值（可空 = 追加至末尾）。 */
const selectedSort = ref<number | null>(null)

/**
 * 是否为 yml 片段（非 `.ftl`）。
 *
 * 片段含 Spring 占位符 `${VAR:default}`，先替换已知项目参数，再做 YAML 合并；不执行 FreeMarker 指令。
 */
function isYmlFragment(name: string): boolean {
  return !name.endsWith('.ftl')
}

/** 读取模板列表（不含 content）。 */
async function load(): Promise<void> {
  loading.value = true
  const view = await props.session.run(
    () => adminStructuredApi.listTemplates(props.session.token.value),
    undefined,
    '模板列表读取失败',
  )
  if (view) templates.value = view.templates
  const draft = await props.session.run(
    () => adminStructuredApi.readDraftVersion(props.session.token.value, 'template'),
    undefined,
    '草稿版本读取失败',
  )
  if (draft !== null) version.value = draft
  loading.value = false
}

/**
 * 选中模板并拉取全文（按需单取，避免列表搬 CLOB）。
 *
 * @param name 模板名
 */
async function select(name: string): Promise<void> {
  selected.value = name
  const detail = await props.session.run(
    () => adminStructuredApi.getTemplate(props.session.token.value, name),
    undefined,
    '模板详情读取失败',
  )
  if (detail) {
    content.value = detail.content ?? ''
    loadedContent.value = content.value
    selectedSort.value = detail.sort ?? null
  }
}

/** 新建模板：以输入的名字选中一个空白编辑区（保存时才落库）。 */
function createTemplate(): void {
  const name = newName.value.trim()
  if (name === '') return
  selected.value = name
  content.value = ''
  loadedContent.value = ''
  selectedSort.value = templates.value.length
}

/** 保存当前选中模板（按 name 幂等 upsert，保留其余模板）。 */
async function saveCurrent(): Promise<void> {
  if (selected.value === null) return
  saving.value = true
  const result = await props.session.run(
    () =>
      adminStructuredApi.putTemplate(
        props.session.token.value,
        selected.value as string,
        content.value,
        version.value,
        selectedSort.value,
      ),
    '模板已保存',
    '模板保存失败',
  )
  if (result) {
    version.value = result.version
    summary.value = result.summary
    loadedContent.value = content.value
    await load()
  }
  saving.value = false
}

/** 删除当前选中模板（保留其余模板）。 */
async function removeCurrent(): Promise<void> {
  if (selected.value === null) return
  if (!window.confirm(`确认删除模板 ${selected.value}？发布后引用它的 starter 会在生成时报模板缺失。`)) {
    return
  }
  saving.value = true
  const result = await props.session.run(
    () =>
      adminStructuredApi.deleteTemplate(
        props.session.token.value,
        selected.value as string,
        version.value,
      ),
    '模板已删除',
    '模板删除失败',
  )
  if (result) {
    version.value = result.version
    summary.value = result.summary
    selected.value = null
    content.value = ''
    loadedContent.value = ''
    await load()
  }
  saving.value = false
}

onMounted(() => {
  void load()
})

defineExpose({ load })
</script>

<template>
  <section class="card">
    <header class="card-head">
      <div>
        <h3>生成模板</h3>
        <p class="hint">
          模板集就是数据库里的这一份（T5 起为唯一数据源，运行时不再回退 classpath 内置模板）。
          列表只取模板名与更新时间，全文按需加载。
        </p>
      </div>
      <div class="card-actions">
        <span v-if="version !== null" class="ver">草稿版本 {{ version }}</span>
        <button type="button" class="btn" :disabled="saving" @click="load">重新载入</button>
      </div>
    </header>

    <p v-if="summary" class="summary">{{ summary }}</p>

    <div class="tpl-grid">
      <aside class="tpl-list">
        <div class="new-row">
          <input v-model="newName" type="text" placeholder="新模板名，如 pom.xml.ftl" @keyup.enter="createTemplate" />
          <button type="button" class="btn btn-sm" :disabled="newName.trim() === ''" @click="createTemplate">
            新建
          </button>
        </div>
        <ul>
          <li
            v-for="t in templates"
            :key="t.name"
            :class="{ active: t.name === selected }"
            @click="select(t.name)"
          >
            <code>{{ t.name }}</code>
            <span class="tpl-meta">{{ t.updatedAt ?? '' }}</span>
          </li>
          <li v-if="!loading && templates.length === 0" class="empty">暂无模板</li>
        </ul>
      </aside>

      <div class="tpl-editor">
        <div v-if="selected === null" class="empty">从左侧选择模板，或输入名字新建。</div>
        <template v-else>
          <div class="editor-head">
            <span>
              <code>{{ selected }}</code>
              <em v-if="isYmlFragment(selected)" class="tag" title="替换项目参数后合并 YAML，保留 Spring 占位符；不执行 FreeMarker 指令">
                yml 片段
              </em>
              <em v-else class="tag tag-ftl" title=".ftl 模板，发布期做 FreeMarker 语法预校验">FreeMarker</em>
            </span>
            <span class="editor-actions">
              <span v-if="dirty" class="dirty">有未保存修改</span>
              <button type="button" class="btn btn-sm" :disabled="saving" @click="removeCurrent">删除</button>
              <button type="button" class="btn btn-primary btn-sm" :disabled="saving" @click="saveCurrent">
                {{ saving ? '保存中…' : '保存模板' }}
              </button>
            </span>
          </div>
          <p class="hint parameter-hint">
            使用 <code>{{ parameterSyntax }}</code> 引用项目参数。YAML 片段支持标量参数替换，
            Spring 运行时占位符原样保留。下方可展开完整参数说明。
          </p>
          <textarea v-model="content" class="tpl-content" spellcheck="false"></textarea>
          <details class="preview">
            <summary>预览（纯文本渲染，不做语法校验）</summary>
            <pre>{{ content }}</pre>
          </details>
          <details class="placeholder-help">
            <summary>占位参数说明（名称、含义、示例及适用范围）</summary>
            <div class="parameter-table">
              <table>
                <thead><tr><th>参数名</th><th>含义</th><th>示例</th><th>适用范围</th></tr></thead>
                <tbody>
                  <tr v-for="parameter in parameters" :key="parameter.name">
                    <td><code>{{ parameter.name }}</code></td>
                    <td>{{ parameter.description }}</td>
                    <td><code>{{ parameter.example }}</code></td>
                    <td>{{ parameter.scope }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p>示例值仅用于说明，生成时取当前项目及所选版本的实际配置。</p>
            <p>
              YAML 片段只替换已知标量参数；未知参数和带默认值的 Spring 占位符保留原文，
              不执行 FreeMarker 表达式或指令。使用 <code>{{ yamlEscape }}</code> 可保留同名运行时参数。
              建议将含占位符的 YAML 值加引号。
            </p>
            <pre>{{ yamlExample }}</pre>
            <p>
              FreeMarker 模板支持表达式与列表指令；保留 Spring 占位符需使用原始字符串，例如
              <code>{{ ftlEscape }}</code>，不能用反斜杠转义。
            </p>
            <p>
              starter 的 target 和 config-package 使用单层大括号，如 <code>{{ pathExample }}</code>；
              仅支持 basePackage、packagePath、artifact、group、bootVersion、javaVersion。
              语法由联动用途决定：templates 中的 source 走 FreeMarker，application-yml-fragments 中的 source 走 YAML 参数替换与合并。
            </p>
          </details>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
@import './admin-form.css';

.tpl-grid { display: grid; gap: 14px; grid-template-columns: 1fr; margin-top: 14px; }
@media (min-width: 1024px) { .tpl-grid { grid-template-columns: minmax(220px, 1fr) minmax(0, 2.4fr); } }
.tpl-list { border: 1px solid #d0d7de; border-radius: 6px; padding: 10px; background: #f6f8fa; }
.tpl-list ul { list-style: none; padding: 0; margin: 8px 0 0; display: grid; gap: 4px; max-height: 360px; overflow: auto; }
.tpl-list li {
  padding: 6px 8px; border-radius: 4px; cursor: pointer; display: flex;
  justify-content: space-between; gap: 8px; align-items: center;
}
.tpl-list li:hover { background: #eaeef2; }
.tpl-list li.active { background: #ddf4ff; }
.tpl-list code { font-size: 12px; word-break: break-all; }
.tpl-meta { color: #8c959f; font-size: 11px; white-space: nowrap; }
.new-row { display: flex; gap: 6px; }
.new-row input { flex: 1; padding: 6px 8px; border: 1px solid #d0d7de; border-radius: 4px; font-size: 13px; }
.tpl-editor { min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.editor-head { display: flex; justify-content: space-between; gap: 10px; align-items: center; flex-wrap: wrap; font-size: 13px; }
.editor-actions { display: flex; gap: 8px; align-items: center; }
.tag { font-style: normal; font-size: 11px; background: #fff8c5; border: 1px solid #d4a72c; color: #7d4e00; border-radius: 4px; padding: 1px 6px; margin-left: 6px; }
.tag-ftl { background: #dafbe1; border-color: #aceebb; color: #1a7f37; }
.dirty { color: #9a6700; font-size: 12px; }
.tpl-content {
  width: 100%; min-height: 320px; font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 13px; line-height: 1.55; padding: 10px; border: 1px solid #d0d7de;
  border-radius: 6px; resize: vertical;
}
.preview { border: 1px solid #d0d7de; border-radius: 6px; padding: 8px 10px; background: #f6f8fa; }
.preview summary { cursor: pointer; font-size: 12px; color: #0969da; }
.preview pre { margin: 8px 0 0; font-size: 12px; white-space: pre-wrap; word-break: break-all; max-height: 240px; overflow: auto; }
.placeholder-help { border: 1px solid #d0d7de; border-radius: 6px; padding: 10px; font-size: 12px; line-height: 1.6; }
.placeholder-help summary { cursor: pointer; color: #0969da; }
.placeholder-help p { margin: 10px 0 0; }
.placeholder-help pre { white-space: pre-wrap; overflow-wrap: anywhere; background: #f6f8fa; padding: 8px; }
.parameter-table { overflow-x: auto; margin-top: 10px; }
.parameter-table table { width: 100%; border-collapse: collapse; text-align: left; }
.parameter-table th, .parameter-table td { padding: 6px 8px; border-bottom: 1px solid #d8dee4; vertical-align: top; overflow-wrap: anywhere; }
.parameter-table code { white-space: pre-wrap; }
</style>
