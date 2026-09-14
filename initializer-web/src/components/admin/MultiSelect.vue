<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 轻量多选下拉（chip + 弹出 checkbox 列表 + 可手输）。
 *
 * <p>用于「冲突规则涉及 starter」「抽象类 alias 列表」等需要从候选清单勾选、
 * 同时允许自由补充未登记项的场景。无第三方 UI 库依赖。
 *
 * <p>数据契约：`modelValue` 始终为去重后的字符串数组，UI 操作等价于
 * `add/remove/contains` 集合语义；`update:modelValue` 派发新数组。
 *
 * <p>行为：
 * <ul>
 *   <li>顶部 chips：每个已选项一个 chip，带 ✕ 删除（点击 chip 不打开弹层）。</li>
 *   <li>输入框：按 Enter / 失焦提交 draft；空 draft + Backspace 删除最后一个 chip。</li>
 *   <li>弹层：列出 `options` 全部项，checkbox 切换；点击空白处关闭。</li>
 *   <li>空 `options` 时仍可手输（向后兼容未登记 starter 名）。</li>
 * </ul>
 */
const props = withDefaults(
  defineProps<{
    modelValue: string[]
    options: string[]
    placeholder?: string
  }>(),
  { placeholder: '选择或输入…' },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string[]): void
}>()

const draft = ref('')
const open = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)
const rootRef = ref<HTMLElement | null>(null)

/** 单次实例内 datalist 唯一 id（同一页面多个 MultiSelect 不冲突）。 */
const listId = `ms-list-${Math.random().toString(36).slice(2, 10)}`

/**
 * 写入一个值：优先填掉第一个空槽位，无空槽才追加。
 *
 * <p>冲突规则 `pair` 初始为 `['', '']`（两个待填槽位），按「填空槽」语义勾选两次
 * 即得到恰好两个 starter；若改用 Set 去重或一律追加，空串会被压成一项或越填越多。
 */
function fillSlot(v: string): string[] {
  const next = [...props.modelValue]
  const idx = next.indexOf('')
  if (idx !== -1) next[idx] = v
  else next.push(v)
  return next
}

/** draft → 写入 modelValue 并清空 draft。 */
function commit(): void {
  const v = draft.value.trim()
  if (v !== '') emit('update:modelValue', fillSlot(v))
  draft.value = ''
}

/** Backspace 在空 draft 上：弹出最后一个 chip（chip 删除体验）。 */
function onBackspace(): void {
  if (draft.value === '' && props.modelValue.length > 0) {
    emit('update:modelValue', props.modelValue.slice(0, -1))
  }
}

/** 弹层 checkbox 勾选：已含该值则移除，否则填入（优先空槽位）。 */
function toggle(opt: string, on: boolean): void {
  if (!on) {
    emit(
      'update:modelValue',
      props.modelValue.filter((x) => x !== opt),
    )
    return
  }
  if (props.modelValue.includes(opt)) return
  emit('update:modelValue', fillSlot(opt))
}

/** chip ✕ 按**下标**移除一项（同值 chip 可能并存，不能按值删）。 */
function removeAt(index: number): void {
  const next = [...props.modelValue]
  next.splice(index, 1)
  emit('update:modelValue', next)
}

/** 弹层 caret 显式切换；点击 trigger 其他区域则 focus 输入框（自然打开）。 */
function toggleOpen(): void {
  open.value = !open.value
  if (open.value) inputRef.value?.focus()
}

function focusInput(): void {
  inputRef.value?.focus()
  open.value = true
}

function onDocMouseDown(e: MouseEvent): void {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) open.value = false
}

onMounted(() => {
  document.addEventListener('mousedown', onDocMouseDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocMouseDown)
})
</script>

<template>
  <div ref="rootRef" class="multi-select">
    <div class="ms-trigger" @click="focusInput">
      <!--
        key 用「值 + 下标」：pair 空槽位会出现重复空串（['', '']），
        纯值 key 会触发重复 key；空值渲染占位文案便于看清未填槽位。
      -->
      <span
        v-for="(v, vi) in modelValue"
        :key="`${v}-${vi}`"
        class="ms-chip"
        :class="{ 'ms-chip-empty': v === '' }"
        @click.stop
      >
        {{ v === '' ? '（未填写）' : v }}
        <button type="button" class="ms-chip-x" title="移除" @click.stop="removeAt(vi)">✕</button>
      </span>
      <input
        ref="inputRef"
        v-model="draft"
        type="text"
        class="ms-input"
        :placeholder="modelValue.length === 0 ? placeholder : ''"
        :list="listId"
        @keydown.enter.prevent="commit"
        @keydown.backspace="onBackspace"
        @blur="commit"
        @focus="open = true"
      />
      <datalist :id="listId">
        <option v-for="opt in options" :key="opt" :value="opt" />
      </datalist>
      <button
        type="button"
        class="ms-caret"
        :aria-expanded="open"
        title="切换候选列表"
        @click.stop="toggleOpen"
      >
        ▾
      </button>
    </div>

    <div v-if="open" class="ms-menu" @mousedown.prevent>
      <ul>
        <li v-for="opt in options" :key="opt">
          <label>
            <input
              type="checkbox"
              :checked="modelValue.includes(opt)"
              @change="toggle(opt, ($event.target as HTMLInputElement).checked)"
            />
            <span>{{ opt }}</span>
          </label>
        </li>
        <li v-if="options.length === 0" class="ms-empty">暂无可选候选，可直接在上方输入</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.multi-select { position: relative; width: 100%; }
.ms-trigger {
  display: flex; flex-wrap: wrap; align-items: center; gap: 4px;
  padding: 4px 8px; border: 1px solid #d0d7de; border-radius: 4px;
  background: #fff; min-height: 32px; cursor: text; font-size: 13px;
}
.ms-trigger:focus-within { border-color: #0969da; box-shadow: 0 0 0 2px rgba(9, 105, 218, .15); }
.ms-chip {
  display: inline-flex; align-items: center; gap: 2px;
  padding: 2px 6px; background: #eef2f7; border-radius: 3px;
  color: #1f2328; line-height: 1.4;
}
.ms-chip-x {
  background: transparent; border: 0; cursor: pointer;
  padding: 0 2px; color: #57606a; font-size: 12px; line-height: 1;
}
.ms-chip-x:hover { color: #cf222e; }
/* 未填写槽位（空串）：弱化成占位样式，便于与已填项区分 */
.ms-chip-empty { background: #f6f8fa; color: #8c959f; }
.ms-input {
  flex: 1; min-width: 100px; border: 0; outline: 0;
  font-size: 13px; background: transparent; padding: 2px 0;
}
.ms-caret {
  background: transparent; border: 0; cursor: pointer;
  color: #57606a; padding: 0 4px; font-size: 12px;
}
.ms-caret:hover { color: #1f2328; }
.ms-menu {
  position: absolute; top: calc(100% + 2px); left: 0; right: 0;
  background: #fff; border: 1px solid #d0d7de; border-radius: 4px;
  max-height: 240px; overflow: auto; z-index: 20;
  box-shadow: 0 4px 12px rgba(0, 0, 0, .08);
}
.ms-menu ul { list-style: none; margin: 0; padding: 4px 0; }
.ms-menu li label {
  display: flex; align-items: center; gap: 6px;
  padding: 4px 10px; cursor: pointer; font-size: 13px; user-select: none;
}
.ms-menu li label:hover { background: #f6f8fa; }
.ms-menu li input[type='checkbox'] { margin: 0; }
.ms-empty { padding: 8px 10px; color: #8c959f; font-size: 12px; }
</style>