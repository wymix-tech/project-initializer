<script setup lang="ts">
import { ref } from 'vue'
import { adminArchiveApi } from '@/api/client'
import type { ImportView, ImportPreview } from '@/types/admin'
import { useAdminSession } from '@/composables/useAdminSession'

/**
 * 导入导出面板（T6.2）：数据库 ↔ ZIP 归档双向通道。
 *
 * <p>归档目录与原外部清单目录一致（`metadata.yml` + `starters/*.yml` +
 * `conflict-rules.yml` + `templates/**`），因此「导出 → 手工编辑 → 再导入」是自然工作流，
 * 也覆盖历史 YAML 数据的迁移路径。
 *
 * <p><b>导入只写草稿</b>：不发布、不切换快照；失败整体回滚不污染草稿，
 * 明细直接展示（路径穿越 4008 / 校验失败 4009 / 乐观锁冲突 4003）。
 * 导入完成后仍需到发布台核对并发布。
 */
const props = defineProps<{ session: ReturnType<typeof useAdminSession>; operator: string }>()

const file = ref<File | null>(null)
const preview = ref<ImportPreview | null>(null)
const exportedName = ref('')
const importResult = ref<ImportView | null>(null)
const busy = ref(false)

const emit = defineEmits<{ (e: 'imported'): void }>()

/**
 * 记录用户选中的归档文件。
 *
 * @param event input[type=file] 的 change 事件
 */
function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  preview.value = null
  file.value = input.files && input.files.length > 0 ? input.files[0] : null
}

/** 导出当前草稿为 ZIP 并触发下载。 */
async function doExport(): Promise<void> {
  busy.value = true
  try {
    const name = await adminArchiveApi.exportArchive(props.session.token.value, props.operator)
    exportedName.value = name
    props.session.showText('info', `已导出 ${name}`)
  } catch (e) {
    props.session.showText('error', e instanceof Error ? e.message : '导出失败')
  } finally {
    busy.value = false
  }
}

async function doPreview(): Promise<void> {
  if (!file.value) { return }
  busy.value = true
  preview.value = null
  importResult.value = null
  try {
    preview.value = await props.session.run(
      () => adminArchiveApi.previewImport(props.session.token.value, file.value as File),
      undefined, '预览失败',
    )
  } finally {
    busy.value = false
  }
}

/** 上传 ZIP 归档导入到草稿（整体替换，失败整体回滚）。 */
async function doImport(): Promise<void> {
  if (preview.value === null) return
  busy.value = true
  importResult.value = null
  const result = await props.session.run(
    () => adminArchiveApi.applyImport(props.session.token.value, preview.value!.previewId, props.operator),
    undefined,
    '导入失败',
  )
  if (result) {
    preview.value = null
    importResult.value = result
    props.session.showText('info', `导入完成：${result.summary}`)
    emit('imported')
  }
  busy.value = false
}
</script>

<template>
  <section class="card">
    <header class="card-head">
      <div>
        <h3>导入导出</h3>
        <p class="hint">
          导出当前草稿为 ZIP（目录布局与原 catalog 一致，「导出 → 编辑 → 再导入」幂等）；
          导入只写草稿、不发布，失败整体回滚不污染草稿。
        </p>
      </div>
    </header>

    <div class="block">
      <h4>导出</h4>
      <p class="hint">归档只包含手工通道条目（source = MANUAL）；同步通道条目可随时由制品库重建，不属人工资产。</p>
      <button type="button" class="btn btn-primary" :disabled="busy" @click="doExport">下载 ZIP 归档</button>
      <span v-if="exportedName" class="file-name">{{ exportedName }}</span>
    </div>

    <div class="block">
      <h4>导入</h4>
      <p class="hint">
        上传 ZIP 归档 → 解析校验（路径穿越 / 条目数 / 单文件与总量 / UTF-8）→ 查看增删改差异 → 确认后整体替换草稿。
        导入后请到「发布台」核对并发布。
      </p>
      <div class="import-row">
        <input type="file" accept=".zip,application/zip" @change="onFileChange" />
        <button type="button" class="btn" :disabled="busy || file === null" @click="doPreview">预览差异</button>
        <button type="button" class="btn btn-primary" :disabled="busy || preview === null" @click="doImport">
          {{ busy ? '导入中…' : '确认导入到草稿' }}
        </button>
      </div>
    </div>

    <div v-if="preview" class="result">
      <p>将整体替换手工草稿，共 {{ preview.changes.length }} 项变化；草稿变化或预览过期时需重新预览。</p>
      <details v-for="change in preview.changes" :key="change.path">
        <summary>{{ change.action }} · {{ change.path }}</summary>
        <p>导入前</p><pre>{{ change.before ?? '（无）' }}</pre>
        <p>导入后</p><pre>{{ change.after ?? '（删除）' }}</pre>
      </details>
    </div>

    <div v-if="importResult" class="result">
      <p><strong>写入结构行：</strong>{{ importResult.rows }}</p>
      <p><strong>规模摘要：</strong>{{ importResult.summary }}</p>
      <p>
        <strong>各模块版本：</strong>
        <span v-for="(v, k) in importResult.versions" :key="k" class="ver-item">{{ k }} = {{ v }}</span>
      </p>
    </div>
  </section>
</template>

<style scoped>
@import './admin-form.css';

.block { border-top: 1px solid #eaeef2; padding-top: 12px; margin-top: 12px; }
.import-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-top: 8px; }
.file-name { margin-left: 10px; color: #1a7f37; font-size: 13px; }
.result { margin-top: 12px; border: 1px solid #aceebb; background: #dafbe1; border-radius: 6px; padding: 10px 12px; font-size: 13px; }
.result pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 300px; overflow: auto; }
.result p { margin: 0 0 4px; }
.ver-item { display: inline-block; background: #fff; border-radius: 4px; padding: 1px 6px; margin-right: 6px; font-size: 12px; }
</style>
