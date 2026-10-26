<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useWizardStore, type MutexInfo } from '@/stores/wizard'

const wizard = useWizardStore()
const selectedOnly = ref(false)
const visibleStarters = computed(() => selectedOnly.value
  ? wizard.displayStarters.filter(starter => wizard.selectedStarters.has(starter.id))
  : wizard.displayStarters)
onBeforeUnmount(() => {
  if (debounceTimer !== undefined) { clearTimeout(debounceTimer) }
})

/** 分类选项（M3.4）：与后端 starter 清单 category 枚举一致 */
const categories = [
  { value: '', label: '全部分类' },
  { value: 'web', label: 'Web' },
  { value: 'data', label: '数据' },
  { value: 'middleware', label: '中间件' },
  { value: 'tool', label: '工具' },
]

/** 输入防抖：300ms 后触发条件检索 */
let debounceTimer: ReturnType<typeof setTimeout> | undefined
function onKeywordInput(event: Event): void {
  const value = (event.target as HTMLInputElement).value
  if (debounceTimer !== undefined) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    void wizard.setSearch(value, wizard.starterCategory)
  }, 300)
}

function onCategoryChange(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  void wizard.setSearch(wizard.starterKeyword, value)
}

/** starter id 转展示名（注册表缺失时回退原始 id）。 */
function labelOf(id: string): string {
  return wizard.starterRegistry[id]?.label ?? id
}

/** M3.8：该 starter 当前生效的互斥提示（取首条，无则 undefined）。 */
function mutexOf(starter: { id: string }): MutexInfo | undefined {
  return wizard.mutexInfo[starter.id]?.[0]
}

/** M3.8：互斥提示 tooltip（互斥对象 + 规则文案 + 切换指引）。 */
function mutexTitle(starter: { id: string }): string {
  const info = mutexOf(starter)
  if (!info) return ''
  return `与 ${(info.partners ?? []).map(labelOf).join('、')} 互斥（只能选一个），点击可随时切换。${info.message}`
}
</script>

<template>
  <div class="deps-panel">
    <div class="starter-toolbar">
      <input
        class="starter-search"
        aria-label="搜索依赖名称或描述"
        type="search"
        placeholder="按名称 / 描述搜索…"
        :value="wizard.starterKeyword"
        @input="onKeywordInput"
      />
      <select
        class="starter-category"
        aria-label="依赖分类"
        :value="wizard.starterCategory"
        @change="onCategoryChange"
      >
        <option v-for="c in categories" :key="c.value" :value="c.value">{{ c.label }}</option>
      </select>
    </div>
    <div class="list-options">
      <span>{{ visibleStarters.length }} 项可用依赖</span>
      <button type="button" class="selected-filter" :aria-pressed="selectedOnly" @click="selectedOnly = !selectedOnly">
        仅看已选
      </button>
    </div>
    <ul v-if="visibleStarters.length > 0" class="starter-list">
      <li v-for="st in visibleStarters" :key="st.id">
        <label
          class="starter"
          :class="{
            checked: wizard.selectedStarters.has(st.id),
            required: st.required,
            locked: wizard.lockedStarters.has(st.id),
            mutex: wizard.mutexIds.has(st.id),
            pending: wizard.pendingOrOptions.has(st.id),
          }"
          :title="wizard.mutexIds.has(st.id) ? mutexTitle(st) : undefined"
        >
          <input
            type="checkbox"
            :checked="wizard.selectedStarters.has(st.id)"
            :disabled="wizard.lockedStarters.has(st.id)"
            @change="wizard.toggleStarter(st.id)"
          />
          <span
            v-if="wizard.pendingOrOptions.has(st.id)"
            class="pending-mark"
            title="已选组件需要你在可选项中最少勾选一项"
          >*</span>
          <span class="starter-info">
            <span class="starter-id">{{ st.id }}</span>
            <span v-if="st.label && st.label !== st.id" class="starter-label">{{ st.label }}</span>
            <span v-if="st.description && st.description !== st.label" class="starter-desc">{{ st.description }}</span>
          </span>
          <span v-if="st.required" class="required-tag" title="框架必选依赖，不可取消">必选</span>
          <span v-else-if="wizard.lockedStarters.has(st.id)" class="dep-locked-tag" title="由已选组件的强制依赖自动选中，取消父组件后自动释放">依赖锁定</span>
          <span v-if="st.category" class="category-tag">{{ st.category }}</span>
          <span v-if="st.maintainer === 'internal'" class="maintainer-tag">内部</span>
        </label>
      </li>
    </ul>
    <p v-else class="starter-empty">{{ selectedOnly ? '当前筛选下没有已选依赖，可取消「仅看已选」或调整搜索条件' : '没有匹配的依赖，请调整搜索条件' }}</p>
    <div v-if="wizard.triggeredConflicts.length > 0" class="conflict-warnings" role="alert">
      <div v-for="c in wizard.triggeredConflicts" :key="c.ruleId" class="conflict-warning">
        <span class="conflict-icon">⚠</span>
        <span>{{ c.message }}</span>
      </div>
    </div>
    <div
      v-if="wizard.unsatisfiedRequires.length > 0"
      class="conflict-warnings requires-warnings"
      role="alert"
    >
      <div v-for="(m, i) in wizard.unsatisfiedRequires" :key="i" class="conflict-warning">
        <span class="conflict-icon">⚠</span>
        <span>
          <b>{{ wizard.starterRegistry[m.id]?.label ?? m.id }}</b>
          需要 {{ m.group.map(labelOf).join(' 或 ') }}，请至少勾选一项
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.starter-toolbar { display: flex; gap: 8px; margin: 8px 0 14px; }
.starter-search { flex: 1; min-width: 0; padding: 11px 12px; font-size: 13px; border: 1px solid var(--line); border-radius: 8px; background: #f8faf8; }
.starter-search:focus { border-color: var(--accent); background: white; }
.starter-category { max-width: 42%; padding: 9px; font-size: 12px; border: 1px solid var(--line); border-radius: 8px; background: white; cursor: pointer; color: #52675b; }
.list-options { display: flex; align-items: center; justify-content: space-between; color: var(--muted); font-size: 11px; margin: 0 0 12px; gap: 10px; }
.selected-filter { display: flex; align-items: center; gap: 6px; cursor: pointer; min-height: 32px; border: 1px solid var(--line); border-radius: 6px; padding: 4px 9px; background: white; color: var(--muted); }
.selected-filter[aria-pressed="true"] { color: var(--accent); background: var(--accent-soft); border-color: #8cbaa0; }
.starter-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 9px; max-height: 620px; overflow-y: auto; scrollbar-width: thin; scrollbar-color: #b6c9bc transparent; padding-right: 4px; }
.starter-list li { min-width: 0; }
.starter { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; padding: 14px; border: 1px solid var(--line); border-radius: 10px; font-size: 12px; cursor: pointer; min-width: 0; }
.starter:hover { background: #f7faf8; border-color: #a3bfad; }
.starter.checked { border-color: #8cbaa0; background: #f0f8f3; }
.starter-info { display: flex; flex-direction: column; gap: 4px; flex: 1 1 150px; min-width: 0; overflow-wrap: anywhere; }
.starter-id { font-size: 13px; font-weight: 600; line-height: 1.5; }
.starter-label { color: var(--muted); font-size: 12px; line-height: 1.65; }
.starter-desc { color: var(--muted); font-size: 12px; line-height: 1.65; }
.starter input[type='checkbox'] { flex-shrink: 0; }
.starter.required, .starter.locked { cursor: default; }
.starter.required input, .starter.locked input { cursor: not-allowed; }
.starter.pending:not(.checked) { background: #fff8df; border-color: #d8bd69; }
.pending-mark { color: #b32937; font-weight: 700; }
.starter.mutex { border-right: 3px solid #d58b60; }
.required-tag, .dep-locked-tag, .category-tag, .maintainer-tag { font-size: 10px; border-radius: 5px; padding: 2px 6px; line-height: 16px; }
.required-tag { color: #236043; background: #dcefe1; }
.dep-locked-tag, .category-tag { color: #386680; background: #e9f1f7; }
.maintainer-tag { color: #775785; background: #f0eaf6; }
.starter-empty { color: var(--muted); font-size: 13px; padding: 40px 20px; text-align: center; line-height: 1.8; border: 1px dashed var(--line); border-radius: 10px; }
.conflict-warnings { display: grid; gap: 8px; margin-top: 14px; }
.conflict-warning { display: flex; align-items: flex-start; gap: 8px; padding: 12px; border: 1px solid #e8d89e; background: #fff9e7; border-radius: 8px; color: #73551c; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.conflict-icon { color: #9a6700; }
</style>
