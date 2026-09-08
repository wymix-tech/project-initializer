<script setup lang="ts">
import { useWizardStore } from '@/stores/wizard'

const wizard = useWizardStore()

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

/** M3.6：依赖联动——引入指定 starter 的已选项（显示为名称，用于「由 X 引入」提示） */
function importersOf(id: string): string[] {
  return (wizard.importedBy[id] ?? [])
    .filter((i) => wizard.selectedStarters.has(i))
    .map((i) => wizard.starterRegistry[i]?.label ?? i)
}

/** requires 依赖 id 转显示名称（注册表缺失时回退原始 id）。 */
function requiresLabel(starter: { requires: string[] }): string {
  return starter.requires.map((id) => wizard.starterRegistry[id]?.label ?? id).join('、')
}
</script>

<template>
  <div class="deps-panel">
    <div class="starter-toolbar">
      <input
        class="starter-search"
        type="search"
        placeholder="按名称 / 描述搜索…"
        :value="wizard.starterKeyword"
        @input="onKeywordInput"
      />
      <select
        class="starter-category"
        :value="wizard.starterCategory"
        @change="onCategoryChange"
      >
        <option v-for="c in categories" :key="c.value" :value="c.value">{{ c.label }}</option>
      </select>
    </div>
    <ul v-if="wizard.starters.length > 0" class="starter-list">
      <li v-for="st in wizard.starters" :key="st.id">
        <label
          class="starter"
          :class="{
            checked: wizard.selectedStarters.has(st.id),
            required: st.required,
            locked: wizard.lockedStarters.has(st.id),
          }"
        >
          <input
            type="checkbox"
            :checked="wizard.selectedStarters.has(st.id)"
            :disabled="wizard.lockedStarters.has(st.id)"
            @change="wizard.toggleStarter(st.id)"
          />
          <span class="starter-label">{{ st.id }}</span>
          <span v-if="st.required" class="required-tag" title="框架必选依赖，不可取消">必选</span>
          <span v-else-if="importersOf(st.id).length > 0" class="imported-tag">
            由 {{ importersOf(st.id).join('、') }} 引入
          </span>
          <span
            v-else-if="st.requires.length > 0"
            class="depends-tag"
            :title="`选中后自动带上：${requiresLabel(st)}`"
          >
            依赖 {{ requiresLabel(st) }}
          </span>
          <span v-if="st.category" class="category-tag">{{ st.category }}</span>
          <span v-if="st.maintainer === 'internal'" class="maintainer-tag">内部</span>
          <span class="starter-desc" :title="st.description">{{ st.description }}</span>
        </label>
      </li>
    </ul>
    <p v-else class="starter-empty">没有匹配的依赖，请调整搜索条件</p>
    <div v-if="wizard.triggeredConflicts.length > 0" class="conflict-warnings" role="alert">
      <div v-for="c in wizard.triggeredConflicts" :key="c.ruleId" class="conflict-warning">
        <span class="conflict-icon">⚠</span>
        <span>{{ c.message }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.starter-toolbar { display: flex; gap: 8px; margin: 8px 0 12px; }
.starter-search {
  flex: 1;
  padding: 7px 10px;
  font-size: 13px;
  border: 1px solid #d0d7de;
  border-radius: 4px;
  outline: none;
}
.starter-search:focus { border-color: #6db33f; }
.starter-category {
  padding: 7px 8px;
  font-size: 13px;
  border: 1px solid #d0d7de;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}
.starter-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 6px;
  max-height: 480px;
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: #c9ced4 transparent;
}
.starter-list::-webkit-scrollbar { width: 8px; }
.starter-list::-webkit-scrollbar-track { background: transparent; }
.starter-list::-webkit-scrollbar-thumb {
  background: #c9ced4;
  border-radius: 4px;
}
.starter-list::-webkit-scrollbar-thumb:hover { background: #aab1b8; }
.starter-list li { min-width: 0; }
.starter {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid #e2e5e9;
  border-left: 3px solid transparent;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  overflow: hidden;
  min-width: 0;
  transition: background 0.1s ease;
}
.starter:hover { background: #f6f8fa; }
.starter.checked {
  border-color: #6db33f;
  border-left-color: #6db33f;
  background: #f4faee;
}
.starter input[type='checkbox'] { accent-color: #6db33f; flex-shrink: 0; }
.starter.required { cursor: default; border-left-color: #6db33f; }
.starter.required input { cursor: not-allowed; }
.starter.locked { cursor: default; }
.starter.locked input { cursor: not-allowed; }
.starter-label { font-weight: 500; flex-shrink: 0; white-space: nowrap; }
.starter-desc {
  margin-left: auto;
  flex: 1 1 0;
  min-width: 0;
  text-align: right;
  color: #8b949e;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.imported-tag {
  font-size: 11px; color: #0550ae; border: 1px solid #54aeff;
  border-radius: 10px; padding: 0 6px; line-height: 16px; background: #ddf4ff;
  flex-shrink: 0;
}
.depends-tag {
  font-size: 11px; color: #6a737d; border: 1px dashed #afb8c1;
  border-radius: 10px; padding: 0 6px; line-height: 16px;
  flex-shrink: 0;
}
.required-tag {
  font-size: 11px; color: #0a3622; border: 1px solid #1a7f37;
  border-radius: 10px; padding: 0 6px; line-height: 16px; background: #dafbe1;
  flex-shrink: 0;
}
.category-tag {
  font-size: 11px; color: #0550ae; border: 1px solid #54aeff;
  border-radius: 10px; padding: 0 6px; line-height: 16px;
  flex-shrink: 0;
}
.maintainer-tag {
  font-size: 11px; color: #8250df; border: 1px solid #d2a8ff;
  border-radius: 10px; padding: 0 6px; line-height: 16px;
  flex-shrink: 0;
}
.starter-empty { color: #6a737d; font-size: 13px; padding: 12px 0; }
.conflict-warnings { display: grid; gap: 6px; margin-top: 12px; }
.conflict-warning {
  display: flex; align-items: center; gap: 8px; padding: 10px 14px;
  border: 1px solid #d4a72c; background: #fff8c5; border-radius: 4px;
  color: #4d2d00; font-size: 13px;
}
.conflict-icon { color: #9a6700; }
</style>
