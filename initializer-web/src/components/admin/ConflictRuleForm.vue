<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminStructuredApi } from '@/api/client'
import type { ConflictRuleDto, ConflictRuleView } from '@/types/admin'
import { useAdminSession } from '@/composables/useAdminSession'

/**
 * 冲突规则表单（T6.2）：starter 互斥约束的结构化维护。
 *
 * <p>规则类型：`pair`（恰好两个 starter 互斥）/ `group`（组内任意两个及以上互斥）。
 * 前端生成向导会即时提示（`StepStarters`），生成请求由后端按同一份规则兜底拦截。
 *
 * <p>契约要点：涉及 starter 的**悬空引用**（ID 不在清单内）不在保存期拦截，
 * 发布期统一收敛为 4002 明细；`pair` 必须恰好 2 个、`group` 至少 2 个的数量约束
 * 同样留到发布期，草稿可边补边存。
 */
const props = defineProps<{ session: ReturnType<typeof useAdminSession> }>()

const loading = ref(false)
const saving = ref(false)
const version = ref<number | null>(null)
const rules = ref<ConflictRuleDto[]>([])
const summary = ref('')

/** 新建一条空白规则（默认 pair 类型 + 两个空 starter 占位）。 */
function addRule(): void {
  rules.value.push({
    id: '',
    type: 'pair',
    starters: ['', ''],
    message: '',
    sort: rules.value.length,
  })
}

/** 删除指定规则行。 */
function removeRule(index: number): void {
  rules.value.splice(index, 1)
}

/**
 * 切换规则类型：`pair` 固定为 2 个 starter 槽位，`group` 至少保留 2 个。
 *
 * @param rule 规则行（就地修改）
 * @param type 目标类型
 */
function changeType(rule: ConflictRuleDto, type: string): void {
  rule.type = type
  if (type === 'pair') {
    // pair 恰好两个槽位：多余的丢弃，不足的补空
    rule.starters = [rule.starters[0] ?? '', rule.starters[1] ?? '']
  } else if (rule.starters.length < 2) {
    rule.starters = [...rule.starters, ...Array(2 - rule.starters.length).fill('')]
  }
}

/** 逗号 / 空白分隔文本 → 字符串数组。 */
function splitIds(text: string): string[] {
  return [...new Set(text.split(/[\s,，]+/).map((s) => s.trim()).filter((s) => s !== ''))]
}

/** 数组 → 逗号分隔文本。 */
function joinIds(ids: string[]): string {
  return ids.join(', ')
}

/** 读取冲突规则草稿。 */
async function load(): Promise<void> {
  loading.value = true
  const view: ConflictRuleView | null = await props.session.run(
    () => adminStructuredApi.getConflictRules(props.session.token.value),
    undefined,
    '冲突规则读取失败',
  )
  if (view) rules.value = view.rules
  const draft = await props.session.run(
    () => adminStructuredApi.readDraftVersion(props.session.token.value),
    undefined,
    '草稿版本读取失败',
  )
  if (draft !== null) version.value = draft
  loading.value = false
}

/** 整体保存冲突规则草稿。 */
async function save(): Promise<void> {
  saving.value = true
  const result = await props.session.run(
    () =>
      adminStructuredApi.putConflictRules(
        props.session.token.value,
        { rules: rules.value },
        version.value,
      ),
    '冲突规则草稿已保存',
    '冲突规则保存失败',
  )
  if (result) {
    version.value = result.version
    summary.value = result.summary
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
        <h3>冲突规则</h3>
        <p class="hint">
          pair = 两个 starter 互斥；group = 组内任意两个及以上同时选中即冲突。
          前端选依赖时即时提示，生成请求按同一份规则兜底拦截。
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

    <p v-if="summary" class="summary">{{ summary }}</p>
    <p v-if="loading" class="empty">载入中…</p>

    <div v-for="(rule, i) in rules" :key="`rule-${i}`" class="rule-card">
      <div class="rule-head">
        <label class="field">
          <span>规则 ID</span>
          <input v-model="rule.id" type="text" placeholder="如 web-vs-webflux" />
        </label>
        <label class="field">
          <span>类型</span>
          <select
            :value="rule.type ?? 'pair'"
            @change="changeType(rule, ($event.target as HTMLSelectElement).value)"
          >
            <option value="pair">pair（恰好两个互斥）</option>
            <option value="group">group（组内互斥）</option>
          </select>
        </label>
        <button type="button" class="btn-icon" title="删除此规则" @click="removeRule(i)">✕</button>
      </div>

      <label class="field">
        <span>涉及 starter（逗号分隔；pair 恰好 2 个，group 至少 2 个）</span>
        <input
          :value="joinIds(rule.starters)"
          type="text"
          placeholder="如 web, webflux"
          @input="rule.starters = splitIds(($event.target as HTMLInputElement).value)"
        />
      </label>

      <label class="field">
        <span>冲突提示文案</span>
        <input v-model="rule.message" type="text" placeholder="如 Web 与 WebFlux 不可同时选择" />
      </label>
    </div>

    <p v-if="!loading && rules.length === 0" class="empty">暂无冲突规则</p>
    <button type="button" class="btn btn-sm" @click="addRule">+ 新增规则</button>
  </section>
</template>

<style scoped>
@import './admin-form.css';

.rule-card { border: 1px solid #d0d7de; border-radius: 6px; padding: 12px; margin-bottom: 12px; background: #fbfcfd; }
.rule-head { display: flex; gap: 10px; flex-wrap: wrap; align-items: flex-end; margin-bottom: 8px; }
.rule-card .field { margin-bottom: 8px; }
.rule-card .field select,
.rule-card .field input[type='text'] {
  padding: 6px 8px; border: 1px solid #d0d7de; border-radius: 4px; font-size: 13px; background: #fff;
}
</style>
