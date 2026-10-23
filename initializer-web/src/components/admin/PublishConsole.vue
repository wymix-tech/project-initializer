<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '@/api/client'
import type { AdminAudit, AdminRelease, AdminSyncOutcome } from '@/types/api'
import { useAdminSession } from '@/composables/useAdminSession'

/**
 * 发布台（T6.2）：发布 / 回滚 / 制品库同步 + 发布历史与审计日志。
 *
 * <p>发布是「全量校验 → 快照构建 → 原子切换」的一步动作，四类草稿（元数据 / starter 清单 /
 * 冲突规则 / 模板）一起生效；校验失败返回 **4002 + 逐条明细**（如跨模块悬空引用、
 * `requires` 成环、模板可达性缺失），失败时旧快照继续服务，提示条直接展示明细全文。
 */
const props = defineProps<{ session: ReturnType<typeof useAdminSession>; operator: string }>()

const releases = ref<AdminRelease[]>([])
const audits = ref<AdminAudit[]>([])
const remark = ref('')
const loading = ref(false)

const emit = defineEmits<{ (e: 'published'): void }>()

/** 刷新发布历史与审计日志。 */
async function refresh(): Promise<void> {
  loading.value = true
  const rel = await props.session.run(
    () => adminApi.listReleases(props.session.token.value),
    undefined,
    '发布历史读取失败',
  )
  if (rel) releases.value = rel
  const aud = await props.session.run(
    () => adminApi.listAudits(props.session.token.value),
    undefined,
    '审计日志读取失败',
  )
  if (aud) audits.value = aud
  loading.value = false
}

/** 发布：全量校验 + 快照热切换（失败给出 4002 明细）。 */
async function publish(): Promise<void> {
  const result = await props.session.run(
    () => adminApi.publish(props.session.token.value, props.operator, remark.value || null),
    undefined,
    '发布失败',
  )
  if (result) {
    props.session.showText(
      'info',
      `发布 #${result.releaseId}：Java ${result.javaVersionCount} / starter ${result.starterCount}`
        + ` / 模板 ${result.templateOverrideCount} / 冲突规则 ${result.conflictRuleCount}`,
    )
    await refresh()
    emit('published')
  }
}

/**
 * 回滚到指定历史发布。
 *
 * @param releaseId 目标发布 ID
 */
async function rollback(releaseId: number): Promise<void> {
  const result = await props.session.run(
    () => adminApi.rollback(props.session.token.value, releaseId, props.operator),
    undefined,
    '回滚失败',
  )
  if (result) {
    props.session.showText('info', `已回滚为发布 #${result.releaseId}`)
    await refresh()
    emit('published')
  }
}

/** 手动触发制品库同步（拉取 → 合并 → 同步层草稿重建 → 热生效）。 */
async function sync(): Promise<void> {
  const outcome: AdminSyncOutcome | null = await props.session.run(
    () => adminApi.sync(props.session.token.value, props.operator),
    undefined,
    '同步失败',
  )
  if (outcome) {
    props.session.showText('info', outcome.summary ?? `同步完成：采纳 ${outcome.accepted ?? 0} 条`)
  }
}

onMounted(() => {
  void refresh()
})

defineExpose({ refresh })
</script>

<template>
  <section class="card">
    <header class="card-head">
      <div>
        <h3>发布台</h3>
        <p class="hint">
          发布 = 全量校验 → 快照构建 → 原子切换（四类草稿一起生效）；校验失败返回 4002 逐条明细，
          旧快照继续服务。预检包含引用与样例试渲染，不执行外部依赖构建；回滚使用相同预检。
        </p>
      </div>
      <div class="card-actions">
        <button type="button" class="btn" :disabled="session.busy.value" @click="refresh">刷新</button>
        <button type="button" class="btn" :disabled="session.busy.value" @click="sync">制品库同步</button>
        <button type="button" class="btn btn-primary" :disabled="session.busy.value" @click="publish">
          发布
        </button>
      </div>
    </header>

    <label class="field remark">
      <span>发布备注（可选，写入发布历史）</span>
      <input v-model="remark" type="text" placeholder="如：接入 xxx-oss starter" />
    </label>

    <div class="history-grid">
      <div>
        <h4>发布历史</h4>
        <ul class="list">
          <li v-for="r in releases" :key="r.id">
            <span>#{{ r.id }} {{ r.releasedAt ?? '' }} {{ r.releasedBy ?? '' }} {{ r.remark ?? '' }}</span>
            <button type="button" class="btn btn-sm" :disabled="session.busy.value" @click="rollback(r.id)">
              回滚
            </button>
          </li>
          <li v-if="!loading && releases.length === 0" class="empty">暂无发布记录</li>
        </ul>
      </div>
      <div>
        <h4>审计日志</h4>
        <ul class="list">
          <li v-for="a in audits" :key="a.id">
            <span>{{ a.createdAt ?? '' }} [{{ a.action }}] {{ a.operator ?? '' }}：{{ a.detail ?? '' }}</span>
          </li>
          <li v-if="!loading && audits.length === 0" class="empty">暂无审计记录</li>
        </ul>
      </div>
    </div>
  </section>
</template>

<style scoped>
@import './admin-form.css';

.remark { margin: 14px 0 6px; }
.remark input { padding: 6px 8px; border: 1px solid #d0d7de; border-radius: 4px; font-size: 13px; }
.history-grid { display: grid; gap: 16px; grid-template-columns: 1fr; }
@media (min-width: 900px) { .history-grid { grid-template-columns: 1fr 1fr; } }
.list { list-style: none; padding: 0; display: grid; gap: 6px; font-size: 13px; margin: 0; }
.list li {
  padding: 6px 10px; border: 1px solid #d0d7de; border-radius: 6px;
  display: flex; justify-content: space-between; gap: 8px; align-items: center;
}
.list li.empty { display: block; }
</style>
