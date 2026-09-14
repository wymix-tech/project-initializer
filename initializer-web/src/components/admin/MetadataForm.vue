<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminStructuredApi } from '@/api/client'
import type { BootVersionDto, JavaVersionDto, MetadataView } from '@/types/admin'
import { useAdminSession } from '@/composables/useAdminSession'

/**
 * 元数据表单（T6.2）：Java 版本清单 + Boot 版本清单（含兼容矩阵、starter 白名单、
 * 框架父 POM）的表单化维护，整体提交 `PUT /api/admin/metadata`。
 *
 * <p>契约要点：
 * <ul>
 *   <li><b>整体提交</b>：保存即提交当前完整结构（后端按「清 kind → 整体写」落库），
 *       因此删除行无需单独接口，删完保存即生效。</li>
 *   <li><b>草稿宽松</b>：此处不做跨模块引用校验（如 `supportedJavaVersions` 指向不存在的 Java、
 *       starter 白名单指向不存在条目）；这些在**发布期**才收敛为 4002 明细，支持边补边存。</li>
 *   <li><b>starter 白名单</b>：`bootVersions[].starters` 仅声明「该版本可引用哪些 starter」，
 *       留空即该版本对全部 starter 清单条目可选。</li>
 * </ul>
 */
const props = defineProps<{ session: ReturnType<typeof useAdminSession> }>()

const loading = ref(false)
const saving = ref(false)
const version = ref<number | null>(null)
const javaVersions = ref<JavaVersionDto[]>([])
const bootVersions = ref<BootVersionDto[]>([])
const summary = ref('')

/** 新建一条 Java 版本行（默认空 id，由用户填写）。 */
function addJava(): void {
  javaVersions.value.push({
    id: '',
    label: '',
    deprecated: false,
    recommendedBootVersion: null,
    sort: javaVersions.value.length,
  })
}

/** 删除指定下标的 Java 版本行。 */
function removeJava(index: number): void {
  javaVersions.value.splice(index, 1)
}

/** 新建一条 Boot 版本行。 */
function addBoot(): void {
  bootVersions.value.push({
    id: '',
    label: '',
    official: true,
    supportedJavaVersions: [],
    starters: [],
    frameworkParent: null,
    sort: bootVersions.value.length,
  })
}

/** 删除指定下标的 Boot 版本行。 */
function removeBoot(index: number): void {
  bootVersions.value.splice(index, 1)
}

/**
 * 为 Boot 行切换「框架父 POM」声明：勾选时补默认空三字段，取消时整体置 null。
 *
 * @param boot Boot 版本行（就地修改）
 * @param on   是否声明
 */
function toggleFrameworkParent(boot: BootVersionDto, on: boolean): void {
  boot.frameworkParent = on ? { groupId: '', artifactId: '', version: '' } : null
}

/**
 * 逗号 / 空白分隔文本 ↔ 字符串数组。
 *
 * @param text 输入文本
 * @returns 去空去重的 ID 清单
 */
function splitIds(text: string): string[] {
  return [...new Set(text.split(/[\s,，]+/).map((s) => s.trim()).filter((s) => s !== ''))]
}

/** 数组 → 逗号分隔文本（表单回显用）。 */
function joinIds(ids: string[]): string {
  return ids.join(', ')
}

/** 读取元数据草稿（结构化 GET 只回结构，不回版本）。 */
async function load(): Promise<void> {
  loading.value = true
  const view: MetadataView | null = await props.session.run(
    () => adminStructuredApi.getMetadata(props.session.token.value),
    undefined,
    '元数据草稿读取失败',
  )
  if (view) {
    javaVersions.value = view.javaVersions
    bootVersions.value = view.bootVersions
  }
  await loadVersion()
  loading.value = false
}

/**
 * 读取草稿乐观锁版本。
 *
 * 结构化 GET 不返回版本（版本是 kind 级 `config_draft_meta.version`），
 * 故额外走一次轻量草稿查询取版本；两套契约共享同一 version 口径，可在
 * 结构化与 YAML 端点间混用 `If-Match`。失败时保持 null（按首次保存语义）。
 */
async function loadVersion(): Promise<void> {
  const draft = await props.session.run(
    () => adminStructuredApi.readDraftVersion(props.session.token.value, 'metadata'),
    undefined,
    '草稿版本读取失败',
  )
  if (draft !== null) version.value = draft
}

/** 框架父 POM version 的输入提示（含 `${revision}` 字样，须用绑定避免模板表达式歧义）。 */
const FRAMEWORK_VERSION_PLACEHOLDER = '支持 ${revision} 等 Maven 属性占位'

/** 保存元数据草稿（整体提交 + 乐观锁）。 */
async function save(): Promise<void> {
  saving.value = true
  const result = await props.session.run(
    () =>
      adminStructuredApi.putMetadata(
        props.session.token.value,
        { javaVersions: javaVersions.value, bootVersions: bootVersions.value },
        version.value,
      ),
    '元数据草稿已保存',
    '元数据草稿保存失败',
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
        <h3>元数据</h3>
        <p class="hint">
          定义可选的 Java / Spring Boot 版本与兼容矩阵；starter 白名单只声明「该 Boot 版本可引用哪些 starter」，
          留空即全部可选。发布期才会校验跨模块引用是否悬空。
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

    <h4>Java 版本（{{ javaVersions.length }}）</h4>
    <table class="grid">
      <thead>
        <tr>
          <th>ID</th>
          <th>展示名</th>
          <th>已废弃</th>
          <th>推荐 Boot 版本</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(jv, i) in javaVersions" :key="`jv-${i}`">
          <td><input v-model="jv.id" type="text" placeholder="如 17" /></td>
          <td><input v-model="jv.label" type="text" placeholder="如 Java 17" /></td>
          <td class="center"><input v-model="jv.deprecated" type="checkbox" /></td>
          <td>
            <input
              :value="jv.recommendedBootVersion ?? ''"
              type="text"
              placeholder="如 3.2.5（可空）"
              @input="jv.recommendedBootVersion = ($event.target as HTMLInputElement).value || null"
            />
          </td>
          <td class="center">
            <button type="button" class="btn-icon" title="删除此行" @click="removeJava(i)">✕</button>
          </td>
        </tr>
        <tr v-if="javaVersions.length === 0">
          <td colspan="5" class="empty">暂无 Java 版本</td>
        </tr>
      </tbody>
    </table>
    <button type="button" class="btn btn-sm" @click="addJava">+ 新增 Java 版本</button>

    <h4>Boot 版本（{{ bootVersions.length }}）</h4>
    <div v-for="(bv, i) in bootVersions" :key="`bv-${i}`" class="boot-card">
      <div class="boot-row">
        <label class="field">
          <span>ID</span>
          <input v-model="bv.id" type="text" placeholder="如 3.2.5" />
        </label>
        <label class="field">
          <span>展示名</span>
          <input v-model="bv.label" type="text" placeholder="如 Spring Boot 3.2.5" />
        </label>
        <label class="field field-inline">
          <input v-model="bv.official" type="checkbox" />
          <span>官方发布版本</span>
        </label>
        <button type="button" class="btn-icon" title="删除此版本" @click="removeBoot(i)">✕</button>
      </div>

      <label class="field">
        <span>兼容 Java 版本（逗号分隔）</span>
        <input
          :value="joinIds(bv.supportedJavaVersions)"
          type="text"
          placeholder="如 17, 21"
          @input="bv.supportedJavaVersions = splitIds(($event.target as HTMLInputElement).value)"
        />
      </label>

      <label class="field">
        <span>可选 starter 白名单（逗号分隔，留空 = 全部可选）</span>
        <input
          :value="joinIds(bv.starters)"
          type="text"
          placeholder="如 web, actuator"
          @input="bv.starters = splitIds(($event.target as HTMLInputElement).value)"
        />
      </label>

      <label class="field field-inline">
        <input
          type="checkbox"
          :checked="bv.frameworkParent !== null"
          @change="toggleFrameworkParent(bv, ($event.target as HTMLInputElement).checked)"
        />
        <span>挂接框架父 POM（不勾选 = 回退官方 spring-boot-starter-parent）</span>
      </label>
      <div v-if="bv.frameworkParent" class="boot-row">
        <label class="field">
          <span>groupId</span>
          <input v-model="bv.frameworkParent.groupId" type="text" />
        </label>
        <label class="field">
          <span>artifactId</span>
          <input v-model="bv.frameworkParent.artifactId" type="text" />
        </label>
        <label class="field">
          <span>version</span>
          <input v-model="bv.frameworkParent.version" type="text" :placeholder="FRAMEWORK_VERSION_PLACEHOLDER" />
        </label>
      </div>
    </div>
    <button type="button" class="btn btn-sm" @click="addBoot">+ 新增 Boot 版本</button>
  </section>
</template>

<style scoped>
@import './admin-form.css';
</style>
