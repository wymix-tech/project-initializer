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
</script>

<template>
  <div>
    <h2>选择依赖（可多选）</h2>
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
          :class="{ checked: wizard.selectedStarters.has(st.id), required: st.required }"
        >
          <input
            type="checkbox"
            :checked="wizard.selectedStarters.has(st.id)"
            :disabled="st.required"
            @change="wizard.toggleStarter(st.id)"
          />
          <span>{{ st.label }}</span>
          <span v-if="st.required" class="required-tag" title="框架必选依赖，不可取消">必选</span>
          <span v-if="st.category" class="category-tag">{{ st.category }}</span>
          <span v-if="st.maintainer === 'internal'" class="maintainer-tag">内部</span>
          <code class="starter-id">{{ st.id }}</code>
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
h2 { font-size: 16px; margin-bottom: 12px; }
.starter-toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.starter-search {
  flex: 1; padding: 6px 10px; font-size: 14px;
  border: 1px solid #d0d7de; border-radius: 6px;
}
.starter-category {
  padding: 6px 10px; font-size: 14px;
  border: 1px solid #d0d7de; border-radius: 6px; background: #fff;
}
.starter-list { list-style: none; padding: 0; display: grid; gap: 8px; }
.starter {
  display: flex; align-items: center; gap: 8px; padding: 10px 14px;
  border: 1px solid #d0d7de; border-radius: 6px; font-size: 14px; cursor: pointer;
}
.starter.checked { border-color: #0969da; background: #ddf4ff; }
.starter.required { cursor: default; border-color: #1a7f37; }
.starter.required input { cursor: not-allowed; }
.required-tag {
  font-size: 11px; color: #0a3622; border: 1px solid #1a7f37;
  border-radius: 10px; padding: 0 6px; line-height: 16px; background: #dafbe1;
}
.starter-id { margin-left: auto; color: #57606a; font-size: 12px; }
.category-tag {
  font-size: 11px; color: #0550ae; border: 1px solid #54aeff;
  border-radius: 10px; padding: 0 6px; line-height: 16px;
}
.maintainer-tag {
  font-size: 11px; color: #8250df; border: 1px solid #d2a8ff;
  border-radius: 10px; padding: 0 6px; line-height: 16px;
}
.starter-empty { color: #57606a; font-size: 14px; padding: 12px 0; }
.conflict-warnings { display: grid; gap: 6px; margin-top: 12px; }
.conflict-warning {
  display: flex; align-items: center; gap: 8px; padding: 10px 14px;
  border: 1px solid #d4a72c; background: #fff8c5; border-radius: 6px;
  color: #4d2d00; font-size: 14px;
}
.conflict-icon { color: #9a6700; }
</style>
