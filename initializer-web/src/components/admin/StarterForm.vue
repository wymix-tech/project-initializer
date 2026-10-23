<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminStructuredApi } from '@/api/client'
import type {
  AbstractClassDto,
  BootVersionDto,
  DependencyDto,
  LinkageDto,
  StarterCatalogView,
  StarterDto,
  TemplateLinkageDto,
  TemplateSummary,
  YmlFragmentDto,
} from '@/types/admin'
import { useAdminSession } from '@/composables/useAdminSession'
import StarterConfigWizard from '@/components/StarterConfigWizard.vue'

/**
 * starter 清单表单（T6.2）：手工通道（`source = MANUAL`）starter 的结构化维护。
 *
 * <p>两种编辑模式：
 * <ul>
 *   <li><b>清单表</b>：逐行维护已登记 starter 的基本字段 / 依赖 / requires / Boot 关联 / 联动，
 *       整体提交 `PUT /api/admin/starters`。</li>
 *   <li><b>新增向导</b>：复用 T1 已交付的 4 步向导组件（`StarterConfigWizard`），
 *       T6 起向导改为结构化提交（不再拼 YAML），追加一条 starter 到清单草稿。</li>
 * </ul>
 *
 * <p>契约要点：
 * <ul>
 *   <li><b>`hidden` 开关必留</b>（PR #40 语义）：`cfg_starter.hidden` 直落列，
 *       表单缺失该开关会让 `hidden: true` 在保存时被静默丢弃。</li>
 *   <li><b>同步通道只读贡献者</b>：`source = SYNC` 行由制品库同步链路机器写入，
 *       不在本表单提交范围内（后端 `PUT /starters` 只写 MANUAL 行）。</li>
 *   <li><b>发布期合并</b>：同名条目手工优先，由发布期合并语义保证，前端不做去重拦截。</li>
 * </ul>
 */
const props = defineProps<{ session: ReturnType<typeof useAdminSession> }>()

/** 编辑模式：清单表（结构化直编） / 新增向导（4 步表单追加）。 */
type StarterMode = 'table' | 'wizard'

const mode = ref<StarterMode>('table')
const loading = ref(false)
const saving = ref(false)
const version = ref<number | null>(null)
const starters = ref<StarterDto[]>([])
const requiresDrafts = ref<string[]>([])
const summary = ref('')

const categories = ['web', 'data', 'middleware', 'tool']
const maintainers = ['official', 'internal']

/**
 * 「关联 Boot 版本」的勾选候选：取自元数据草稿里已维护的 Boot 版本。
 *
 * <p>草稿即用户最新维护结果——在「元数据」分区维护了 boot `a` 后回到本表单即可勾选，
 * 无需等发布：发布只决定初始化页是否可见，草稿始终是维护态的权威数据源。
 */
const bootOptions = ref<BootVersionDto[]>([])

/**
 * 加载 Boot 版本候选（静默降级）。
 *
 * 元数据未维护或读取失败时保持空清单，表单回退为手输，不打断 starter 清单维护。
 */
async function loadBootOptions(): Promise<void> {
  try {
    const view = await adminStructuredApi.getMetadata(props.session.token.value)
    bootOptions.value = view?.bootVersions ?? []
  } catch {
    bootOptions.value = []
  }
}

/**
 * 某 starter 的可勾选项 = 元数据候选 ∪ 已选但候选里没有的值（孤儿，避免静默丢失）。
 *
 * @param st starter 行
 */
function bootChoicesOf(st: StarterDto): { id: string; label: string; official: boolean; orphan: boolean }[] {
  const known = new Map(bootOptions.value.map((bv) => [bv.id, bv]))
  const orphans = (st.bootVersions ?? []).filter((id) => !known.has(id))
  return [
    ...bootOptions.value.map((bv) => ({
      id: bv.id,
      label: bv.label || bv.id,
      official: bv.official === true,
      orphan: false,
    })),
    ...orphans.map((id) => ({ id, label: id, official: false, orphan: true })),
  ]
}

/**
 * 勾选 / 取消勾选一个 Boot 版本关联。
 *
 * @param st starter 行
 * @param id Boot 版本 ID
 * @param on 是否勾选
 */
function toggleBoot(st: StarterDto, id: string, on: boolean): void {
  const set = new Set(st.bootVersions ?? [])
  if (on) set.add(id)
  else set.delete(id)
  st.bootVersions = [...set]
}

/**
 * 「模板联动 / yml 片段」source 字段的候选模板清单。
 *
 * 后端 `GET /api/admin/templates` 返回的 {@link TemplateSummary} 仅含
 * `name/updatedAt/sort`，按 name 后缀做轻量分类：
 * `.ftl` 用于模板联动、`.yml` / `.yaml` 用于 yml 片段联动。
 * 加载失败时回退为空（保留原手输能力）。
 */
const templateList = ref<TemplateSummary[]>([])

/** 模板联动 source 候选（仅 `.ftl`）。 */
const ftlTemplates = computed(() =>
  templateList.value.filter((t) => t.name.endsWith('.ftl')),
)

/** yml 片段联动 source 候选（`.yml` / `.yaml`）。 */
const ymlTemplates = computed(() =>
  templateList.value.filter((t) => /\.(yml|yaml)$/.test(t.name)),
)

/** 静默加载模板候选清单（失败不回退到手输，由模板渲染层降级）。 */
async function loadTemplates(): Promise<void> {
  try {
    const view = await adminStructuredApi.listTemplates(props.session.token.value)
    templateList.value = view?.templates ?? []
  } catch {
    templateList.value = []
  }
}

/** 新建一条空白 starter 行（含一条空依赖行，降低多选门槛）。 */
function addStarter(): void {
  starters.value.push({
    id: '',
    category: 'web',
    maintainer: 'official',
    description: '',
    required: false,
    hidden: false,
    configPackage: '',
    dependencies: [emptyDependency()],
    requires: [],
    bootVersions: [],
    linkages: emptyLinkage(),
    sort: starters.value.length,
  })
  requiresDrafts.value.push('')
}

/** 删除指定 starter 行。 */
function removeStarter(index: number): void {
  starters.value.splice(index, 1)
  requiresDrafts.value.splice(index, 1)
}

/** @returns 一条空白依赖坐标行 */
function emptyDependency(): DependencyDto {
  return { groupId: '', artifactId: '', version: '' }
}

/** @returns 一份空的联动规则（三类联动均空） */
function emptyLinkage(): LinkageDto {
  return { templates: [], ymlFragments: [], abstractClasses: [] }
}

/**
 * 取 starter 的联动规则对象（缺省就地在父行上补一份空联动）。
 *
 * @param st starter 行
 * @returns 可写的联动对象（非空）
 */
function linkageOf(st: StarterDto): LinkageDto {
  if (!st.linkages) st.linkages = emptyLinkage()
  return st.linkages
}

/** 逗号 / 空白分隔文本 → 字符串数组。 */
function splitIds(text: string): string[] {
  return [...new Set(text.split(/[\s,，]+/).map((s) => s.trim()).filter((s) => s !== ''))]
}

/** 数组 → 逗号分隔文本。 */
function joinIds(ids: string[]): string {
  return ids.join(', ')
}

/**
 * requires 分组 ↔ 文本的双向映射（一行一组，组内逗号分隔）。
 *
 * 「一行一组」：单元素行 = 强制依赖（前端自动勾选并锁定）；多元素行 = OR（仅提示）。
 */
function requiresText(st: StarterDto): string {
  return st.requires.map((group) => group.join(', ')).join('\n')
}

/**
 * 解析 requires 文本为 AND-of-OR 分组。
 *
 * @param text 多行文本，每行一个分组，组内逗号分隔
 * @returns 分组清单（空行跳过）
 */
function parseRequires(text: string): string[][] {
  return text
    .split('\n')
    .map((line) => splitIds(line))
    .filter((group) => group.length > 0)
}

/**
 * 将 requires 编辑草稿写回 starter 模型。
 *
 * <p>仅在 change / blur 时提交，避免输入过程中按回车产生的空行被立即吞掉。
 *
 * @param index starter 在列表中的索引
 */
function commitRequiresDraft(index: number): void {
  const st = starters.value[index]
  if (!st) return
  st.requires = parseRequires(requiresDrafts.value[index] ?? '')
}

/** 读取 starter 清单草稿。 */
async function load(): Promise<void> {
  loading.value = true
  const view: StarterCatalogView | null = await props.session.run(
    () => adminStructuredApi.getStarters(props.session.token.value),
    undefined,
    'starter 清单读取失败',
  )
  if (view) {
    starters.value = view.starters
    requiresDrafts.value = view.starters.map((s) => requiresText(s))
  }
  const draft = await props.session.run(
    () => adminStructuredApi.readDraftVersion(props.session.token.value, 'starter'),
    undefined,
    '草稿版本读取失败',
  )
  if (draft !== null) version.value = draft
  await Promise.all([loadBootOptions(), loadTemplates()])
  loading.value = false
}

/** 整体保存 starter 清单草稿。 */
async function save(): Promise<void> {
  saving.value = true
  const result = await props.session.run(
    () =>
      adminStructuredApi.putStarters(
        props.session.token.value,
        { starters: starters.value, operator: props.session.operator.value },
        version.value,
      ),
    'starter 清单草稿已保存',
    'starter 清单保存失败',
  )
  if (result) {
    version.value = result.version
    summary.value = result.summary
    starters.value = starters.value.filter((s) => s.id.trim() !== '')
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
        <h3>starter 清单</h3>
        <p class="hint">
          手工通道（source = MANUAL）的权威数据源：基本信息、依赖坐标、requires 联动分组、
          Boot 版本关联与三类联动规则。制品库同步条目（source = SYNC）不在此维护，
          发布期按「同名手工优先」合并。
        </p>
      </div>
      <div class="card-actions">
        <span v-if="version !== null" class="ver">草稿版本 {{ version }}</span>
        <button type="button" class="btn" :disabled="saving" @click="load">重新载入</button>
        <button type="button" class="btn btn-primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存草稿' }}
        </button>
      </div>
    </header>

    <div class="mode-switch">
      <button
        type="button"
        class="mode-btn"
        :class="{ active: mode === 'table' }"
        @click="mode = 'table'"
      >
        清单表
      </button>
      <button
        type="button"
        class="mode-btn"
        :class="{ active: mode === 'wizard' }"
        @click="mode = 'wizard'"
      >
        🪄 新增向导（4 步）
      </button>
    </div>

    <p v-if="summary" class="summary">{{ summary }}</p>

    <StarterConfigWizard v-if="mode === 'wizard'" :token="session.token.value" :operator="session.operator.value" @saved="load" />

    <template v-else>
      <p v-if="loading" class="empty">载入中…</p>

      <div v-for="(st, i) in starters" :key="`st-${i}`" class="starter-card">
        <div class="starter-head">
          <label class="field">
            <span>ID</span>
            <input v-model="st.id" type="text" placeholder="如 web" />
          </label>
          <label class="field">
            <span>分类</span>
            <select v-model="st.category">
              <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
            </select>
          </label>
          <label class="field">
            <span>维护方</span>
            <select v-model="st.maintainer">
              <option v-for="m in maintainers" :key="m" :value="m">{{ m }}</option>
            </select>
          </label>
          <label class="field field-inline">
            <input v-model="st.required" type="checkbox" />
            <span>必选</span>
          </label>
          <label class="field field-inline" title="仅前端依赖选择列表默认隐藏，被已选组件 requires 引用时强制展示">
            <input v-model="st.hidden" type="checkbox" />
            <span>隐藏</span>
          </label>
          <button type="button" class="btn-icon" title="删除此 starter" @click="removeStarter(i)">✕</button>
        </div>

        <label class="field">
          <span>描述（同时作为展示名）</span>
          <input v-model="st.description" type="text" placeholder="如 Spring MVC + 内嵌 Tomcat" />
        </label>

        <div class="two-col">
          <label class="field">
            <span>
              关联 Boot 版本（勾选；全不勾 = 全部版本可用）
              <em class="field-note">选项来自「元数据」分区维护的 Boot 版本</em>
            </span>
            <div v-if="bootChoicesOf(st).length > 0" class="boot-picker">
              <label
                v-for="bv in bootChoicesOf(st)"
                :key="`boot-${i}-${bv.id}`"
                class="boot-chip"
                :class="{ picked: st.bootVersions.includes(bv.id) }"
                :title="bv.orphan ? '元数据草稿中已无此 Boot 版本' : bv.id"
              >
                <input
                  type="checkbox"
                  :checked="st.bootVersions.includes(bv.id)"
                  @change="toggleBoot(st, bv.id, ($event.target as HTMLInputElement).checked)"
                />
                <span>{{ bv.label }}</span>
                <span v-if="bv.label !== bv.id" class="chip-id">{{ bv.id }}</span>
                <span v-if="bv.official" class="chip-badge">官方</span>
                <span v-if="bv.orphan" class="chip-orphan">未登记</span>
              </label>
            </div>
            <input
              v-else
              :value="joinIds(st.bootVersions)"
              type="text"
              placeholder="如 3.2.5, 2.7.18（元数据未维护 Boot 版本时手输）"
              @input="st.bootVersions = splitIds(($event.target as HTMLInputElement).value)"
            />
          </label>
          <label class="field">
            <span>配置类包名 config-package（支持 {basePackage} 占位符）</span>
            <input
              :value="st.configPackage ?? ''"
              type="text"
              placeholder="如 {basePackage}.config"
              @input="st.configPackage = ($event.target as HTMLInputElement).value"
            />
          </label>
        </div>

        <label class="field">
          <span>requires 依赖联动（一行一组，组内逗号分隔；单元素行 = 强制依赖）</span>
          <textarea
            v-model="requiresDrafts[i]"
            rows="2"
            placeholder="b, c&#10;d"
            @change="commitRequiresDraft(i)"
            @blur="commitRequiresDraft(i)"
          ></textarea>
        </label>

        <div class="block">
          <div class="block-head">
            <span>依赖坐标（{{ st.dependencies.length }}）</span>
            <button type="button" class="btn btn-sm" @click="st.dependencies.push(emptyDependency())">
              + 增加依赖
            </button>
          </div>
          <div v-for="(dep, di) in st.dependencies" :key="`dep-${i}-${di}`" class="pair-row">
            <input v-model="dep.groupId" type="text" placeholder="groupId" class="pair-input" />
            <input v-model="dep.artifactId" type="text" placeholder="artifactId" class="pair-input" />
            <input v-model="dep.version" type="text" placeholder="version（可空 = Boot BOM）" class="pair-input dep-ver" />
            <button type="button" class="btn-icon" @click="st.dependencies.splice(di, 1)">✕</button>
          </div>
        </div>

        <div class="block">
          <div class="block-head">
            <span>模板联动（{{ linkageOf(st).templates.length }}）</span>
            <button
              type="button"
              class="btn btn-sm"
              @click="linkageOf(st).templates.push({ source: '', target: '' } as TemplateLinkageDto)"
            >
              + 增加模板
            </button>
          </div>
          <div v-for="(t, ti) in linkageOf(st).templates" :key="`tpl-${i}-${ti}`" class="pair-row">
            <input v-model="t.source" type="text" placeholder="source，如 starters/x/X.java.ftl" class="pair-input" :list="`tpl-src-${i}-${ti}`" />
            <datalist :id="`tpl-src-${i}-${ti}`">
              <option v-for="tpl in ftlTemplates" :key="tpl.name" :value="tpl.name" />
            </datalist>
            <input v-model="t.target" type="text" placeholder="target，如 src/main/java/{packagePath}/X.java" class="pair-input" />
            <button type="button" class="btn-icon" @click="linkageOf(st).templates.splice(ti, 1)">✕</button>
          </div>
        </div>

        <div class="block">
          <div class="block-head">
            <span>application.yml 片段（{{ linkageOf(st).ymlFragments.length }}）</span>
            <button
              type="button"
              class="btn btn-sm"
              @click="linkageOf(st).ymlFragments.push({ source: '', mergeStrategy: 'append' } as YmlFragmentDto)"
            >
              + 增加片段
            </button>
          </div>
          <div v-for="(f, fi) in linkageOf(st).ymlFragments" :key="`frag-${i}-${fi}`" class="pair-row">
            <input v-model="f.source" type="text" placeholder="source，如 starters/x/application-x.yml" class="pair-input" :list="`yml-src-${i}-${fi}`" />
            <datalist :id="`yml-src-${i}-${fi}`">
              <option v-for="tpl in ymlTemplates" :key="tpl.name" :value="tpl.name" />
            </datalist>
            <select v-model="f.mergeStrategy" class="pair-select">
              <option value="append">append（追加去重）</option>
              <option value="override">override（整体替换）</option>
            </select>
            <button type="button" class="btn-icon" @click="linkageOf(st).ymlFragments.splice(fi, 1)">✕</button>
          </div>
        </div>

        <div class="block">
          <div class="block-head">
            <span>集成抽象类（{{ linkageOf(st).abstractClasses.length }}）</span>
            <button
              type="button"
              class="btn btn-sm"
              @click="linkageOf(st).abstractClasses.push({ fqcn: '', alias: '' } as AbstractClassDto)"
            >
              + 增加抽象类
            </button>
          </div>
          <div v-for="(a, ai) in linkageOf(st).abstractClasses" :key="`abs-${i}-${ai}`" class="pair-row">
            <input v-model="a.fqcn" type="text" placeholder="fqcn，如 com.company.redis.RedisSupport" class="pair-input" />
            <input v-model="a.alias" type="text" placeholder="alias，如 redisSupport" class="pair-input" />
            <button type="button" class="btn-icon" @click="linkageOf(st).abstractClasses.splice(ai, 1)">✕</button>
          </div>
        </div>
      </div>

      <p v-if="!loading && starters.length === 0" class="empty">暂无手工 starter 条目</p>
      <button type="button" class="btn btn-sm" @click="addStarter">+ 新增 starter 条目</button>
    </template>
  </section>
</template>

<style scoped>
@import './admin-form.css';

.mode-switch { display: flex; gap: 8px; margin: 14px 0 6px; }
.mode-btn {
  padding: 6px 14px; border-radius: 6px; border: 1px solid #d0d7de;
  background: #f6f8fa; cursor: pointer; font-size: 13px;
}
.mode-btn.active { background: #6db33f; border-color: #6db33f; color: #fff; }
.starter-card { border: 1px solid #d0d7de; border-radius: 6px; padding: 12px; margin-bottom: 12px; background: #fbfcfd; }
.starter-head { display: flex; gap: 10px; flex-wrap: wrap; align-items: flex-end; margin-bottom: 8px; }
.starter-card .field { margin-bottom: 8px; }
.starter-card .field select,
.starter-card .field textarea {
  padding: 6px 8px; border: 1px solid #d0d7de; border-radius: 4px; font-size: 13px;
  background: #fff; font-family: inherit;
}
.starter-card .field textarea {
  width: 100%;
  resize: vertical;
  white-space: pre-wrap;
  overflow-wrap: break-word;
}
.two-col { display: grid; gap: 10px; grid-template-columns: 1fr; }
@media (min-width: 900px) { .two-col { grid-template-columns: 1fr 1fr; } }
.field-note { font-style: normal; font-size: 11px; color: #6a737d; margin-left: 4px; }
.boot-picker { display: flex; flex-wrap: wrap; gap: 6px 8px; padding: 4px 0; }
.boot-chip {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 9px; border: 1px solid #d0d7de; border-radius: 12px;
  background: #fff; font-size: 12px; cursor: pointer; user-select: none;
}
.boot-chip:hover { border-color: #8c959f; }
.boot-chip.picked { border-color: #6db33f; background: #f2f9ee; }
.chip-id { color: #6a737d; font-size: 11px; }
.chip-badge { color: #4e8a2c; font-size: 10px; border: 1px solid #6db33f; border-radius: 3px; padding: 0 3px; }
.chip-orphan { color: #b08800; font-size: 10px; border: 1px solid #d4a72c; border-radius: 3px; padding: 0 3px; }
.block { margin-bottom: 10px; }
.block-head { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: #24292f; margin-bottom: 6px; }
.pair-row { display: flex; gap: 8px; margin-bottom: 6px; align-items: center; }
.pair-input { flex: 1; padding: 6px 8px; border: 1px solid #d0d7de; border-radius: 4px; font-size: 13px; }
.dep-ver { max-width: 180px; }
.pair-select { padding: 6px 8px; border: 1px solid #d0d7de; border-radius: 4px; font-size: 13px; background: #fff; }
</style>
