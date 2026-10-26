<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useAdminSession } from '@/composables/useAdminSession'
import type { AdminSection } from '@/types/admin'
import MetadataForm from '@/components/admin/MetadataForm.vue'
import StarterForm from '@/components/admin/StarterForm.vue'
import ConflictRuleForm from '@/components/admin/ConflictRuleForm.vue'
import TemplateForm from '@/components/admin/TemplateForm.vue'
import PublishConsole from '@/components/admin/PublishConsole.vue'
import ImportExportPanel from '@/components/admin/ImportExportPanel.vue'

/**
 * 管理后台工作台（T6.1）：左侧分区导航 + 右侧卡片式表单外壳。
 *
 * <p>由「YAML 文本框」改为模块化表单工作台，六个分区：
 * 发布台 / 元数据 / starter 清单 / 冲突规则 / 模板 / 导入导出。
 * 所有分区共享同一 {@link useAdminSession}（token / 登录态 / 忙碌态 / 提示条），
 * 数据全部走 T3 结构化契约与 T4 归档契约，**不再拼 YAML 文本**。
 *
 * <p>视觉使用统一的浅色绿色主题，与项目生成页共享颜色与焦点样式；
 * 校验错误明细（4002 / 4008 / 4009）以提示条整段呈现，不弹窗打断。
 *
 * <p><b>发布即全量生效</b>：各分区「保存草稿」只写 `release_id = 0` 草稿区，
 * 需到「发布台」点发布才切换快照热生效；分区表单保存后会提示当前草稿版本。
 */
const session = useAdminSession()

/** 六个分区（顺序即左侧导航顺序）。 */
const sections: { key: AdminSection; label: string; icon: string; desc: string }[] = [
  { key: 'publish', label: '发布台', icon: '🚀', desc: '发布 / 回滚 / 历史与审计' },
  { key: 'metadata', label: '元数据', icon: '🧩', desc: 'Java / Boot 版本与兼容矩阵' },
  { key: 'starter', label: 'starter 清单', icon: '📦', desc: '坐标 / 联动 / 必选与隐藏' },
  { key: 'conflict-rule', label: '冲突规则', icon: '⚔️', desc: 'starter 互斥约束' },
  { key: 'template', label: '模板', icon: '📄', desc: '模板集在线编辑与预览' },
  { key: 'transfer', label: '导入导出', icon: '🔁', desc: 'ZIP 归档双向通道' },
]

const active = ref<AdminSection>('publish')

/** 操作人（写入草稿与审计；当前为固定值，后续可做成可配置项）。 */
const operator = session.operator

/**
 * 登录：先验证 token 有效性再进入工作台。
 *
 * 登录成功后懒加载当前分区数据（各分区组件在 `active` 变化时自行载入）。
 */
async function login(): Promise<void> {
  await session.login()
}

/**
 * 分区切换（登录后由导航点击触发）。
 *
 * @param key 目标分区
 */
function go(key: AdminSection): void {
  active.value = key
}

// 登录成功后默认停留在「发布台」；未登录时不加载任何分区数据
watch(
  () => session.loggedIn.value,
  (logged) => {
    if (logged) active.value = 'publish'
  },
)

onMounted(() => {
  // token 由用户输入后 login 拉取数据；此处不做自动登录
})
</script>

<template>
  <section class="admin-panel">
    <header class="admin-intro"><p class="eyebrow">CONFIGURATION WORKSPACE</p><h1>配置工作台</h1><p>从依赖到模板，集中维护项目生成规则。</p></header>
    <form v-if="!session.loggedIn.value" class="admin-login" @submit.prevent="login">
      <span class="login-symbol" aria-hidden="true">PI</span><h2>登录管理后台</h2>
      <p class="login-hint">
        表单化维护元数据 / starter 清单 / 冲突规则 / 模板，草稿保存后可一键发布热生效，
        也支持 ZIP 归档导入导出。
      </p>
      <label class="token-label" for="admin-token">管理令牌</label>
      <input
        id="admin-token"
        autocomplete="current-password"
        v-model="session.token.value"
        class="admin-token"
        type="password"
        placeholder="管理端 token（X-Admin-Token）"
      />
      <button
        type="submit"
        class="btn btn-primary"
        :disabled="session.busy.value || session.token.value === ''"
      >
        登录
      </button>
      <p
        v-if="session.hasMessage.value"
        class="admin-msg"
        :class="`admin-msg-${session.messageType.value}`"
        role="status"
      >
        {{ session.message.value }}
      </p>
    </form>

    <div v-else class="admin-body">
      <aside class="admin-nav">
        <div class="nav-brand">
          <span class="nav-dot"></span>
          <span>配置工作台</span>
        </div>
        <button
          v-for="s in sections"
          :key="s.key"
          type="button"
          class="nav-item"
          :class="{ active: active === s.key }"
          :aria-current="active === s.key ? 'page' : undefined"
          @click="go(s.key)"
        >
          <span class="nav-icon" aria-hidden="true">{{ s.icon }}</span>
          <span class="nav-text">
            <strong>{{ s.label }}</strong>
            <em>{{ s.desc }}</em>
          </span>
        </button>
        <label class="nav-operator">
          <span>操作人</span>
          <input v-model="operator" type="text" />
        </label>
      </aside>

      <div class="admin-main">
        <p
          v-if="session.hasMessage.value"
          class="admin-msg"
          :class="`admin-msg-${session.messageType.value}`"
          role="status"
          @click="session.clearText()"
        >
          {{ session.message.value }}
        </p>

        <PublishConsole
          v-if="active === 'publish'"
          :session="session"
          :operator="operator"
        />
        <MetadataForm v-else-if="active === 'metadata'" :session="session" />
        <StarterForm v-else-if="active === 'starter'" :session="session" />
        <ConflictRuleForm v-else-if="active === 'conflict-rule'" :session="session" />
        <TemplateForm v-else-if="active === 'template'" :session="session" />
        <ImportExportPanel v-else :session="session" :operator="operator" />
      </div>
    </div>
  </section>
</template>

<style scoped>
.admin-panel { min-width: 0; }
.admin-intro { margin-bottom: 28px; }
.admin-intro h1 { font-size: 28px; margin: 10px 0; }
.admin-intro > p:last-child { font-size: 14px; color: var(--muted); }
.eyebrow { color: var(--accent); font-size: 11px; letter-spacing: 1.6px; font-weight: 700; }
.login-symbol { display: grid; place-items: center; width: 48px; height: 48px; background: var(--accent-soft); color: var(--accent); border-radius: 14px; font-weight: 700; margin-bottom: 12px; }
.token-label { color: #52675b; font-size: 12px; margin-top: 12px; }
.admin-panel h2 { font-size: 18px; margin-bottom: 12px; }
.admin-login { display: flex; flex-direction: column; gap: 12px; max-width: 480px; margin: 48px auto; padding: 36px; background: white; border: 1px solid var(--line); border-radius: 18px; box-shadow: 0 12px 40px #244b3008; }
.login-hint { color: #57606a; font-size: 13px; line-height: 1.6; }
.admin-token { padding: 12px; border: 1px solid var(--line); border-radius: 8px; font-size: 14px; width: 100%; }

.admin-body { display: grid; gap: 18px; grid-template-columns: 1fr; }
@media (min-width: 960px) {
  .admin-body { grid-template-columns: minmax(200px, 232px) minmax(0, 1fr); align-items: start; }
}

/* 左侧分区导航 */
.admin-nav {
  border: 1px solid #d0d7de; border-radius: 8px; padding: 10px;
  background: #f6f8fa; display: flex; flex-direction: column; gap: 4px;
  position: sticky; top: 16px;
}
.nav-brand { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; padding: 4px 8px 10px; color: #24292f; }
.nav-dot { width: 10px; height: 10px; border-radius: 50%; background: #6db33f; display: inline-block; }
.nav-item {
  display: flex; gap: 10px; align-items: flex-start; text-align: left;
  border: 1px solid transparent; background: transparent; border-radius: 6px;
  padding: 13px 10px; cursor: pointer; font-size: 13px; color: #24292f;
}
.nav-item:hover { background: #eaf1ec; }
.nav-item.active { background: var(--accent-soft); border-color: #b5d3bf; box-shadow: inset 3px 0 0 var(--accent); color: var(--accent); }
.nav-icon { font-size: 15px; line-height: 1.4; }
.nav-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.nav-text strong { font-weight: 600; }
.nav-text em { font-style: normal; color: #57606a; font-size: 11px; }
.nav-operator { display: flex; flex-direction: column; gap: 4px; font-size: 11px; color: #57606a; padding: 8px 8px 2px; }
.nav-operator input { padding: 5px 8px; border: 1px solid #d0d7de; border-radius: 4px; font-size: 12px; }

/* 右侧主区 */
.admin-main { min-width: 0; display: flex; flex-direction: column; gap: 12px; }
.admin-msg { padding: 8px 12px; border-radius: 6px; font-size: 13px; cursor: pointer; white-space: pre-wrap; word-break: break-word; }
.admin-msg-info { background: #ddf4ff; color: #0550ae; }
.admin-msg-error { background: #ffebe9; color: #cf222e; }

.btn { padding: 11px 16px; border-radius: 8px; border: 1px solid #d0d7de; background: #f6f8fa; cursor: pointer; font-size: 13px; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: var(--accent); border-color: var(--accent); color: #fff; }
@media (max-width: 959px) { .admin-nav { position: static; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } .nav-brand, .nav-operator { grid-column: 1 / -1; } }
@media (max-width: 480px) { .admin-login { padding: 24px; margin: 24px auto; } .nav-text em { display: none; } }
</style>
