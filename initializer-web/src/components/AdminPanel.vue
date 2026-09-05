<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, ApiError } from '@/api/client'
import type { AdminAudit, AdminConfigKind, AdminDraftView, AdminRelease } from '@/types/api'
import StarterConfigWizard from '@/components/StarterConfigWizard.vue'
import { useStarterConfigWizardStore } from '@/stores/starterConfigWizard'

/**
 * 管理后台面板（M3 评审遗留项 2）：草稿编辑（乐观锁）→ 发布 → 历史回滚 + 审计展示。
 * token 仅存于会话内存，刷新后需重新输入。
 *
 * M3.6 评审遗留项：新增「向导式接入」模式——把 starter 配置从手写 yml 拆成 4 步表单，
 * 字段级校验 + 占位符白名单前置提示，降低接入门槛；高级用户仍可切回 yml 模式手贴。
 * 向导复用同一 token 会话，后端零改动（写入草稿 + publish）。
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

function showText(type: 'info' | 'error', text: string): void {
  messageType.value = type
  message.value = text
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
  draftContent.value = view.contentYaml ?? ''
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

      <!-- YAML 直编模式（原行为） -->
      <div v-else class="admin-draft">
        <select v-model="activeKind" class="admin-kind" @change="switchKind">
          <option v-for="k in kinds" :key="k.value" :value="k.value">{{ k.label }}</option>
        </select>
        <textarea
          v-model="draftContent"
          class="admin-yaml"
          rows="12"
          spellcheck="false"
          placeholder="配置 YAML 全文"
        ></textarea>
        <div class="admin-draft-actions">
          <button type="button" class="btn" :disabled="busy" @click="saveDraft">保存草稿</button>
          <button type="button" class="btn btn-primary" :disabled="busy" @click="publish">发布</button>
          <span v-if="dirty" class="admin-dirty">有未保存修改</span>
        </div>
      </div>

      <div v-if="message" class="admin-msg" :class="`admin-msg-${messageType}`" role="status">
        {{ message }}
      </div>

      <h3>发布历史</h3>
      <ul class="admin-releases">
        <li v-for="r in releases" :key="r.id">
          <span>#{{ r.id }} {{ r.releasedAt ?? '' }} {{ r.remark ?? '' }}</span>
          <button type="button" class="btn" :disabled="busy" @click="rollback(r.id)">回滚</button>
        </li>
        <li v-if="releases.length === 0" class="admin-empty">暂无发布记录</li>
      </ul>

      <h3>审计日志</h3>
      <ul class="admin-audits">
        <li v-for="a in audits" :key="a.id">
          <span>{{ a.createdAt ?? '' }} [{{ a.action }}] {{ a.operator ?? '' }}：{{ a.detail ?? '' }}</span>
        </li>
        <li v-if="audits.length === 0" class="admin-empty">暂无审计记录</li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.admin-panel { background: #fff; border: 1px solid #d0d7de; border-radius: 8px; padding: 20px; }
.admin-panel h2 { font-size: 18px; margin-bottom: 12px; }
.admin-panel h3 { font-size: 15px; margin: 16px 0 8px; }
.admin-login { display: flex; flex-direction: column; gap: 10px; max-width: 360px; }
.admin-token { padding: 8px 10px; border: 1px solid #d0d7de; border-radius: 6px; }
.admin-draft { display: flex; flex-direction: column; gap: 8px; }
.admin-draft-actions { display: flex; gap: 8px; align-items: center; }
.admin-kind { padding: 6px 10px; border: 1px solid #d0d7de; border-radius: 6px; width: 200px; }
.admin-yaml { width: 100%; font-family: monospace; font-size: 13px; padding: 8px; border: 1px solid #d0d7de; border-radius: 6px; }
.admin-dirty { color: #9a6700; font-size: 13px; }
.admin-msg { padding: 8px 12px; border-radius: 6px; font-size: 14px; }
.admin-msg-info { background: #ddf4ff; color: #0550ae; }
.admin-msg-error { background: #ffebe9; color: #cf222e; }
.admin-releases, .admin-audits { list-style: none; padding: 0; display: grid; gap: 6px; font-size: 13px; }
.admin-releases li, .admin-audits li { padding: 6px 10px; border: 1px solid #d0d7de; border-radius: 6px; display: flex; justify-content: space-between; gap: 8px; }
.admin-empty { color: #57606a; }

.mode-switch { display: flex; gap: 8px; margin-bottom: 16px; }
.mode-btn {
  padding: 6px 14px; border-radius: 6px; border: 1px solid #d0d7de;
  background: #f6f8fa; cursor: pointer; font-size: 14px;
}
.mode-btn.active { background: #0969da; border-color: #0969da; color: #fff; }
</style>
