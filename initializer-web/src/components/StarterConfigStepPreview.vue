<script setup lang="ts">
import { useStarterConfigWizardStore } from '@/stores/starterConfigWizard'

/**
 * 第 4 步：预览与发布。
 *
 * 实时展示表单生成的 starter yml（纯函数渲染），确认无误后写入草稿（乐观锁）
 * 或保存并发布（复用后端 publish：全量校验 + 快照热切换）。
 */
const wizard = useStarterConfigWizardStore()

/** 复制预览到剪贴板（失败静默，降级为手动选中复制）。 */
async function copyPreview(): Promise<void> {
  try {
    await navigator.clipboard.writeText(wizard.previewYaml)
    wizard.showText('info', '已复制 YAML 到剪贴板')
  } catch {
    wizard.showText('error', '复制失败，请手动选中复制')
  }
}
</script>

<template>
  <div>
    <h3>④ 预览与发布</h3>
    <p class="scw-hint">
      下方即向导根据表单生成的 starter yml。点「写入草稿」追加到 starter 草稿（不立即生效）；
      点「保存并发布」触发后端全量校验 + 快照热切换。
    </p>

    <div class="preview-head">
      <span class="preview-title">生成的 starter yml</span>
      <button type="button" class="btn-sm" @click="copyPreview">复制</button>
    </div>
    <pre class="preview-yaml">{{ wizard.previewYaml }}</pre>

    <div class="summary">
      <div class="summary-item">
        <span class="summary-key">starter id</span>
        <code>{{ wizard.form.id }}</code>
      </div>
      <div class="summary-item">
        <span class="summary-key">依赖数</span>
        <code>{{ wizard.form.dependencies.length }}</code>
      </div>
      <div class="summary-item">
        <span class="summary-key">联动</span>
        <code>{{ wizard.hasLinkages ? '有' : '无' }}</code>
      </div>
    </div>
  </div>
</template>

<style scoped>
h3 { font-size: 15px; margin-bottom: 8px; }
.scw-hint { color: #57606a; font-size: 13px; margin-bottom: 12px; }
.preview-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.preview-title { font-size: 13px; color: #24292f; font-weight: 600; }
.preview-yaml {
  background: #f6f8fa; border: 1px solid #d0d7de; border-radius: 6px;
  padding: 12px; font-family: monospace; font-size: 13px; white-space: pre-wrap;
  word-break: break-all; max-height: 320px; overflow: auto;
}
.summary { display: flex; gap: 16px; margin-top: 12px; flex-wrap: wrap; }
.summary-item { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.summary-key { color: #57606a; }
.summary-item code { background: #f6f8fa; padding: 0 6px; border-radius: 4px; }
.btn-sm { padding: 4px 10px; border-radius: 6px; border: 1px solid #d0d7de; background: #f6f8fa; cursor: pointer; font-size: 12px; }
</style>
