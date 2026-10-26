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
  <section class="config-section">
    <h2 class="section-title">项目元信息</h2>
    <div class="field">
      <label for="meta-group">Group</label>
      <div class="field-control">
        <input
          id="meta-group"
          :aria-invalid="!!wizard.validationErrors.group"
          :aria-describedby="wizard.validationErrors.group ? 'meta-group-error' : undefined"
          :value="wizard.meta.group"
          type="text"
          placeholder="com.example"
          @input="onInput('group', $event)"
        />
        <p v-if="wizard.validationErrors.group" id="meta-group-error" class="field-error">{{ wizard.validationErrors.group }}</p>
      </div>
    </div>
    <div class="field">
      <label for="meta-artifact">Artifact</label>
      <div class="field-control">
        <input
          id="meta-artifact"
          :aria-invalid="!!wizard.validationErrors.artifact"
          :aria-describedby="wizard.validationErrors.artifact ? 'meta-artifact-error' : undefined"
          :value="wizard.meta.artifact"
          type="text"
          placeholder="demo-app"
          @input="onInput('artifact', $event)"
        />
        <p v-if="wizard.validationErrors.artifact" id="meta-artifact-error" class="field-error">{{ wizard.validationErrors.artifact }}</p>
      </div>
    </div>
    <div class="field">
      <label for="meta-package" title="由 Group + Artifact 自动推导，可手动修改">Package</label>
      <div class="field-control">
        <input
          id="meta-package"
          :aria-invalid="!!wizard.validationErrors.packageName"
          :aria-describedby="wizard.validationErrors.packageName ? 'meta-package-error' : undefined"
          :value="wizard.meta.packageName"
          type="text"
          placeholder="com.example.demoapp"
          @input="onPackageInput"
        />
        <p v-if="wizard.validationErrors.packageName" id="meta-package-error" class="field-error">
          {{ wizard.validationErrors.packageName }}
        </p>
        <p v-else class="field-hint">由 Group + Artifact 自动推导，可修改</p>
      </div>
    </div>
    <div class="field">
      <label>构建工具</label>
      <div class="field-control">
        <div class="radio-row">
          <label class="radio" for="build-tool-maven">
            <input id="build-tool-maven" type="radio" name="build-tool" value="maven" v-model="wizard.meta.buildTool" />
            Maven
          </label>
          <label class="radio" for="build-tool-gradle">
            <input id="build-tool-gradle" type="radio" name="build-tool" value="gradle" v-model="wizard.meta.buildTool" :disabled="!wizard.supportedBuildTools.includes('gradle')" />
            Gradle<span v-if="!wizard.supportedBuildTools.includes('gradle')">（该框架暂未支持）</span>
          </label>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.section-title { font-size: 13px; font-weight: 600; margin-bottom: 18px; }
.field { display: grid; grid-template-columns: 80px minmax(0, 1fr); align-items: start; gap: 10px; margin-bottom: 18px; }
.field:last-child { margin-bottom: 0; }
.field > label { font-size: 13px; color: #52675b; line-height: 42px; }
.field-control { display: flex; flex-direction: column; min-width: 0; }
.field input[type='text'] { width: 100%; min-width: 0; border: 1px solid var(--line); border-radius: 8px; padding: 11px 12px; font-size: 13px; background: #fcfdfc; color: #21342e; }
.field input[type='text']:focus { border-color: var(--accent); background: white; }
.field input[type='text']::placeholder { color: #8a9b90; }
.radio-row { display: flex; flex-wrap: wrap; gap: 8px 18px; padding: 10px 0; }
.radio { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 13px; cursor: pointer; }
.radio:has(input:disabled) { color: var(--muted); cursor: not-allowed; }
.field-error { color: #b32937; font-size: 12px; margin-top: 6px; line-height: 1.6; }
.field-hint { color: var(--muted); font-size: 11px; margin-top: 7px; line-height: 1.6; }
@media (max-width: 480px) { .field { grid-template-columns: 1fr; gap: 5px; } .field > label { line-height: 1.8; } }
</style>
