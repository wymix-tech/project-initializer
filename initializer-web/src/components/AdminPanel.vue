<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, ApiError } from '@/api/client'
import type { AdminAudit, AdminConfigKind, AdminDraftView, AdminRelease } from '@/types/api'
import StarterConfigWizard from '@/components/StarterConfigWizard.vue'
import { useStarterConfigWizardStore } from '@/stores/starterConfigWizard'
import { ADMIN_CONFIG_TEMPLATES, sampleOf } from '@/config/admin-config-templates'

/**
 * 管理后台面板（M3 评审遗留项 2）：草稿编辑（乐观锁）→ 发布 → 历史回滚 + 审计展示。
 * token 仅存于会话内存，刷新后需重新输入。
 *
 * M3.6 评审遗留项：新增「向导式接入」模式——把 starter 配置从手写 yml 拆成 4 步表单，
 * 字段级校验 + 占位符白名单前置提示，降低接入门槛；高级用户仍可切回 yml 模式手贴。
 * 向导复用同一 token 会话，后端零改动（写入草稿 + publish）。
 *
 * 空白草稿引导：草稿为空（首次维护）时自动填入带逐字段注释的示例 YAML，
 * 并在编辑区下方展示字段用途释义与发布注意事项，避免面对空白文本无从下手。
 * 示例仅写入本地编辑区，需显式「保存草稿」才落库。
 */

/** 草稿种类选项（与后端 ConfigKind 对外小写形式一致）。 */
const kinds: { value: AdminConfigKind; label: string }[] = [
  { value: 'metadata', label: '元数据' },
  { value: 'starter', label: 'starter 清单' },
  { value: 'conflict-rule', label: '冲突规则' },
  { value: 'template', label: '生成模板' },
]

/** starter 草稿编辑模式：表单向导（傻瓜化） / YAML 直编（高级） */
type StarterEditMode = 'wizard' | 'yaml'

const token = ref('')
const activeKind = ref<AdminConfigKind>('metadata')
const draftContent = ref('')
const draftVersion = ref<number | null>(null)
const savedVersion = ref<number | null>(null)
const releases = ref<AdminRelease[]>([])
const audits = ref<AdminAudit[]>([])
const loggedIn = ref(false)
const busy = ref(false)
const message = ref('')
const messageType = ref<'info' | 'error'>('info')
/** starter 草稿编辑模式（仅 starter kind 切换为向导时生效） */
const starterEditMode = ref<StarterEditMode>('yaml')

const wizardStore = useStarterConfigWizardStore()

const dirty = computed(() => draftVersion.value !== savedVersion.value)

/** 当前种类的示例模板与字段释义（切换 kind 时联动）。 */
const activeTemplate = computed(() => ADMIN_CONFIG_TEMPLATES[activeKind.value])

/**
 * 编辑区当前是否为「未被修改的示例内容」。
 *
 * 用于常驻提示：示例只是参照，需按需修改并显式保存草稿才落库；
 * 用户一旦编辑即自动消失（与示例文本不再全等）。
 */
const showingSample = computed(() => draftContent.value === sampleOf(activeKind.value))

function showText(type: 'info' | 'error', text: string): void {
  messageType.value = type
  message.value = text
}

/**
 * 用示例内容填充编辑区。
 *
 * 已有内容时先确认再覆盖（示例会清空未保存的草稿文本）；编辑区为空时直接填入。
 */
function fillSample(): void {
  if (draftContent.value.trim() !== '' && !window.confirm('将用示例内容覆盖当前编辑区文本，是否继续？')) {
    return
  }
  draftContent.value = sampleOf(activeKind.value)
  showText('info', '已填入示例配置（仅供参照，请按需修改后再保存草稿）')
}

async function login(): Promise<void> {
  busy.value = true
  try {
    await adminApi.getDraft('metadata', token.value)
    loggedIn.value = true
    // 向导复用同一会话 token
    wizardStore.setToken(token.value)
    await loadDraft()
    await refreshAll()
    showText('info', '登录成功')
  } catch (e) {
    showText('error', e instanceof ApiError ? e.detail : '登录失败')
  } finally {
    busy.value = false
  }
}

async function refreshAll(): Promise<void> {
  releases.value = await adminApi.listReleases(token.value)
  audits.value = await adminApi.listAudits(token.value)
}

async function loadDraft(): Promise<void> {
  const view: AdminDraftView = await adminApi.getDraft(activeKind.value, token.value)
  const content = view.contentYaml ?? ''
  // 空白草稿（首次维护）自动填入带注释的示例，避免面对空编辑区无从下手
  draftContent.value = content.trim() === '' ? sampleOf(activeKind.value) : content
  draftVersion.value = view.version
  savedVersion.value = view.version
  // starter kind 时同步向导草稿基线（查重与乐观锁）
  if (activeKind.value === 'starter') {
    wizardStore.existingDraft = view.contentYaml ?? ''
    wizardStore.draftVersion = view.version
  }
}

async function switchKind(): Promise<void> {
  busy.value = true
  try {
    await loadDraft()
  } catch (e) {
    showText('error', e instanceof ApiError ? e.detail : '草稿读取失败')
  } finally {
    busy.value = false
  }
}

async function saveDraft(): Promise<void> {
  busy.value = true
  try {
    await adminApi.putDraft(activeKind.value, token.value, draftContent.value, draftVersion.value)
    await loadDraft()
    showText('info', '草稿已保存')
  } catch (e) {
    showText('error', e instanceof ApiError ? e.detail : '草稿保存失败')
  } finally {
    busy.value = false
  }
}

async function publish(): Promise<void> {
  busy.value = true
  try {
    const result = await adminApi.publish(token.value)
    showText(
      'info',
      `发布 #${result.releaseId}：Java ${result.javaVersionCount} / starter ${result.starterCount}`
        + ` / 模板 ${result.templateOverrideCount} / 冲突规则 ${result.conflictRuleCount}`,
    )
    await refreshAll()
  } catch (e) {
    showText('error', e instanceof ApiError ? e.detail : '发布失败')
  } finally {
    busy.value = false
  }
}

async function rollback(releaseId: number): Promise<void> {
  busy.value = true
  try {
    const result = await adminApi.rollback(token.value, releaseId)
    showText('info', `已回滚为发布 #${result.releaseId}`)
    await refreshAll()
  } catch (e) {
    showText('error', e instanceof ApiError ? e.detail : '回滚失败')
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  // 空实现占位：token 由用户输入后 login 拉取数据
})
</script>

<template>
  <section class="admin-panel">
    <div v-if="!loggedIn" class="admin-login">
      <h2>管理后台</h2>
      <input
        v-model="token"
        class="admin-token"
        type="password"
        placeholder="管理端 token（X-Admin-Token）"
        @keyup.enter="login"
      />
      <button type="button" class="btn btn-primary" :disabled="busy || !token" @click="login">
        登录
      </button>
      <p v-if="message" class="admin-msg" :class="`admin-msg-${messageType}`" role="status">
        {{ message }}
      </p>
    </div>

    <div v-else class="admin-body">
      <h2>管理后台</h2>

      <!-- starter 草稿：模式切换（向导 / YAML） -->
      <div v-if="activeKind === 'starter'" class="mode-switch">
        <button
          type="button"
          class="mode-btn"
          :class="{ active: starterEditMode === 'wizard' }"
          @click="starterEditMode = 'wizard'"
        >
          🪄 向导式接入（推荐）
        </button>
        <button
          type="button"
          class="mode-btn"
          :class="{ active: starterEditMode === 'yaml' }"
          @click="starterEditMode = 'yaml'"
        >
          ✍ YAML 直编
        </button>
      </div>

      <!-- 向导模式：仅 starter kind 展示 -->
      <StarterConfigWizard
        v-if="activeKind === 'starter' && starterEditMode === 'wizard'"
        :token="token"
      />

      <!-- YAML 直编模式：编辑区全宽 + 释义右栏；窄屏自动回退到上下堆叠 -->
      <div v-else class="admin-draft">
        <div class="admin-toolbar">
          <select v-model="activeKind" class="admin-kind" @change="switchKind">
            <option v-for="k in kinds" :key="k.value" :value="k.value">{{ k.label }}</option>
          </select>
          <div class="admin-toolbar-actions">
            <button type="button" class="btn" :disabled="busy" @click="saveDraft">保存草稿</button>
            <button type="button" class="btn btn-primary" :disabled="busy" @click="publish">发布</button>
            <button type="button" class="btn btn-sample" :disabled="busy" @click="fillSample">
              填入示例
            </button>
            <span v-if="dirty" class="admin-dirty">有未保存修改</span>
          </div>
        </div>

        <p v-if="showingSample" class="admin-sample-hint" role="status">
          当前为示例配置（仅供参照），请按需修改后点击「保存草稿」；示例不会自动落库。
        </p>

        <div class="admin-draft-grid">
          <!-- 编辑区：占满主区域，最小高度撑满视口，余下空间释义侧栏可滚动 -->
          <div class="admin-editor">
            <textarea
              v-model="draftContent"
              class="admin-yaml"
              spellcheck="false"
              placeholder="配置 YAML 全文"
            ></textarea>
          </div>

          <!-- 字段释义：与 YAML 并列；窄屏在编辑区下方 -->
          <details class="admin-doc" open>
            <summary>字段说明（{{ activeTemplate.fields.length }} 项）</summary>
            <p class="admin-doc-summary">{{ activeTemplate.summary }}</p>
            <table class="admin-doc-table">
              <thead>
                <tr>
                  <th>字段</th>
                  <th>必填</th>
                  <th>用途</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="f in activeTemplate.fields" :key="f.path">
                  <td><code>{{ f.path }}</code></td>
                  <td class="admin-doc-req" :class="{ optional: !f.required }">
                    {{ f.required ? '必填' : '可选' }}
                  </td>
                  <td>{{ f.purpose }}</td>
                </tr>
              </tbody>
            </table>
            <ul v-if="activeTemplate.notes.length > 0" class="admin-doc-notes">
              <li v-for="n in activeTemplate.notes" :key="n">{{ n }}</li>
            </ul>
          </details>
        </div>
      </div>

      <div v-if="message" class="admin-msg" :class="`admin-msg-${messageType}`" role="status">
        {{ message }}
      </div>

      <div class="admin-history-grid">
        <div>
          <h3>发布历史</h3>
          <ul class="admin-releases">
            <li v-for="r in releases" :key="r.id">
              <span>#{{ r.id }} {{ r.releasedAt ?? '' }} {{ r.remark ?? '' }}</span>
              <button type="button" class="btn" :disabled="busy" @click="rollback(r.id)">回滚</button>
            </li>
            <li v-if="releases.length === 0" class="admin-empty">暂无发布记录</li>
          </ul>
        </div>
        <div>
          <h3>审计日志</h3>
          <ul class="admin-audits">
            <li v-for="a in audits" :key="a.id">
              <span>{{ a.createdAt ?? '' }} [{{ a.action }}] {{ a.operator ?? '' }}：{{ a.detail ?? '' }}</span>
            </li>
            <li v-if="audits.length === 0" class="admin-empty">暂无审计记录</li>
          </ul>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.admin-panel { background: #fff; border: 1px solid #d0d7de; border-radius: 8px; padding: 20px 24px; }
.admin-panel h2 { font-size: 18px; margin-bottom: 12px; }
.admin-panel h3 { font-size: 15px; margin: 20px 0 8px; }
.admin-login { display: flex; flex-direction: column; gap: 10px; max-width: 360px; }
.admin-token { padding: 8px 10px; border: 1px solid #d0d7de; border-radius: 6px; }
.admin-draft { display: flex; flex-direction: column; gap: 10px; }

/* 工具栏：下拉框在左、操作按钮在右；窄屏自动换行 */
.admin-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; flex-wrap: wrap;
}
.admin-toolbar-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.admin-kind { padding: 6px 10px; border: 1px solid #d0d7de; border-radius: 6px; min-width: 180px; }

/* YAML 编辑区：占满主列，撑满视口高度并允许纵向滚动 */
.admin-editor { min-width: 0; display: flex; }
.admin-yaml {
  width: 100%;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  line-height: 1.55;
  padding: 12px;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  resize: vertical;
  /* 视口高度减去头部/工具栏/底部，编辑区可滚动，避免受字符串行数限制 */
  min-height: min(70vh, 600px);
  height: min(70vh, 600px);
}
.admin-dirty { color: #9a6700; font-size: 13px; }
.btn-sample { background: #f6f8fa; }
.admin-sample-hint {
  padding: 8px 12px; border-radius: 6px; font-size: 12px;
  background: #fff8c5; border: 1px solid #d4a72c; color: #7d4e00;
}

/* YAML 编辑区 + 释义双栏布局：宽屏（≥1024px）分两列，窄屏单列堆叠 */
.admin-draft-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 1fr;
}
@media (min-width: 1024px) {
  .admin-draft-grid {
    grid-template-columns: minmax(0, 1.6fr) minmax(280px, 1fr);
  }
  .admin-doc { align-self: start; max-height: min(70vh, 600px); overflow: auto; }
}

/* 字段说明面板：默认展开，可折叠让出编辑区空间 */
.admin-doc { border: 1px solid #d0d7de; border-radius: 6px; padding: 10px 14px; background: #f6f8fa; }
.admin-doc summary { cursor: pointer; font-size: 13px; font-weight: 600; color: #0969da; }
.admin-doc-summary { margin: 8px 0; font-size: 13px; color: #57606a; }
.admin-doc-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.admin-doc-table { width: 100%; border-collapse: collapse; font-size: 13px; table-layout: fixed; }
.admin-doc-table th, .admin-doc-table td {
  border: 1px solid #d0d7de; padding: 6px 10px; text-align: left; vertical-align: top;
}
.admin-doc-table th { background: #eaeef2; white-space: nowrap; }
/* 列宽分配：字段路径 36% / 必填 12% / 用途占剩余，固定布局避免被字段路径挤窄说明列 */
.admin-doc-table th:nth-child(1), .admin-doc-table td:nth-child(1) { width: 36%; }
.admin-doc-table th:nth-child(2), .admin-doc-table td:nth-child(2) { width: 12%; }
.admin-doc-table th:nth-child(3), .admin-doc-table td:nth-child(3) { width: auto; }
.admin-doc-table code { font-size: 12px; word-break: break-all; }
.admin-doc-req { color: #cf222e; white-space: nowrap; }
.admin-doc-req.optional { color: #57606a; }
.admin-doc-notes { margin: 8px 0 0; padding-left: 18px; font-size: 12px; color: #57606a; }
.admin-doc-notes li { margin-bottom: 3px; }
.admin-msg { padding: 8px 12px; border-radius: 6px; font-size: 14px; }
.admin-msg-info { background: #ddf4ff; color: #0550ae; }
.admin-msg-error { background: #ffebe9; color: #cf222e; }
.admin-releases, .admin-audits { list-style: none; padding: 0; display: grid; gap: 6px; font-size: 13px; }
.admin-releases li, .admin-audits li { padding: 6px 10px; border: 1px solid #d0d7de; border-radius: 6px; display: flex; justify-content: space-between; gap: 8px; }
.admin-empty { color: #57606a; }

/* 历史 + 审计：宽屏下并排两列；窄屏堆叠 */
.admin-history-grid {
  display: grid; gap: 16px; grid-template-columns: 1fr;
}
@media (min-width: 768px) {
  .admin-history-grid { grid-template-columns: 1fr 1fr; }
}

.mode-switch { display: flex; gap: 8px; margin-bottom: 16px; }
.mode-btn {
  padding: 6px 14px; border-radius: 6px; border: 1px solid #d0d7de;
  background: #f6f8fa; cursor: pointer; font-size: 14px;
}
.mode-btn.active { background: #0969da; border-color: #0969da; color: #fff; }
</style>
