<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useWizardStore } from '@/stores/wizard'
import { useToast } from '@/composables/useToast'

const wizard = useWizardStore()
const toast = useToast()

let debounceTimer: ReturnType<typeof setTimeout> | undefined

/** 输入后 300ms 防抖触发即时校验。 */
function debouncedValidate(): void {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    wizard.validateMeta().catch(() => toast.push('error', '校验服务暂不可用，请稍后重试'))
  }, 300)
}

/**
 * 更新元信息字段并触发防抖校验。
 *
 * @param field 字段名
 * @param event 输入事件
 */
function onInput(field: 'group' | 'artifact' | 'packageName', event: Event): void {
  const value = (event.target as HTMLInputElement).value
  wizard.updateMeta(field, value).then(debouncedValidate)
}

/** 手动修改包名时标记 touched，停止自动推导。 */
function onPackageInput(event: Event): void {
  wizard.updateMeta('packageTouched', 'true')
  onInput('packageName', event)
}

onMounted(debouncedValidate)
onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>

<template>
  <div>
    <h2>项目元信息</h2>
    <div class="field">
      <label for="meta-group">Group</label>
      <input
        id="meta-group"
        :value="wizard.meta.group"
        type="text"
        placeholder="com.example"
        @input="onInput('group', $event)"
      />
      <p v-if="wizard.validationErrors.group" class="field-error">{{ wizard.validationErrors.group }}</p>
    </div>
    <div class="field">
      <label for="meta-artifact">Artifact</label>
      <input
        id="meta-artifact"
        :value="wizard.meta.artifact"
        type="text"
        placeholder="demo-app"
        @input="onInput('artifact', $event)"
      />
      <p v-if="wizard.validationErrors.artifact" class="field-error">{{ wizard.validationErrors.artifact }}</p>
    </div>
    <div class="field">
      <label for="meta-package">Package（自动推导，可修改）</label>
      <input
        id="meta-package"
        :value="wizard.meta.packageName"
        type="text"
        placeholder="com.example.demoapp"
        @input="onPackageInput"
      />
      <p v-if="wizard.validationErrors.packageName" class="field-error">
        {{ wizard.validationErrors.packageName }}
      </p>
    </div>
    <div class="field">
      <label>构建工具</label>
      <div class="radio-row">
        <label class="radio" for="build-tool-maven">
          <input id="build-tool-maven" type="radio" value="maven" v-model="wizard.meta.buildTool" />
          Maven
        </label>
        <label class="radio" for="build-tool-gradle">
          <input id="build-tool-gradle" type="radio" value="gradle" v-model="wizard.meta.buildTool" />
          Gradle
        </label>
      </div>
    </div>
  </div>
</template>

<style scoped>
h2 { font-size: 16px; margin-bottom: 12px; }
.radio-row { display: flex; gap: 16px; }
.radio { display: flex; align-items: center; gap: 6px; font-size: 14px; }
.field { margin-bottom: 14px; display: flex; flex-direction: column; gap: 4px; }
.field label { font-size: 13px; color: #57606a; }
.field input {
  padding: 8px 10px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 14px;
}
.field-error { color: #cf222e; font-size: 12px; }
</style>
