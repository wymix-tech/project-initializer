import { describe, expect, it } from 'vitest'
import { ADMIN_CONFIG_TEMPLATES, sampleOf, fieldsOf, notesOf } from '@/config/admin-config-templates'
import type { AdminConfigKind } from '@/types/api'

/**
 * 字段释义契约测试（T6 角色收敛后）：确保每个配置模块都配齐
 * 「占位示例 + 字段释义 + 注意事项」，新增 kind 时漏补会在此处暴露。
 *
 * T6 起管理端为表单化维护，sample 仅作字段形态说明（不再写入草稿），
 * 故不再断言「示例可直接发布」；只保证形态与释义完整、可被表单页用作补充说明。
 */
const KINDS: AdminConfigKind[] = ['metadata', 'starter', 'conflict-rule', 'template']

describe('管理端配置示例模板', () => {
  it('覆盖全部配置种类', () => {
    expect(Object.keys(ADMIN_CONFIG_TEMPLATES).sort()).toEqual([...KINDS].sort())
  })

  it.each(KINDS)('%s 示例非空且带字段注释', (kind) => {
    const sample = sampleOf(kind)
    expect(sample.trim()).not.toBe('')
    // 示例须带中文注释，否则失去「参考配置」意义
    expect(sample).toContain('#')
  })

  it.each(KINDS)('%s 具备字段释义与定位说明', (kind) => {
    const template = ADMIN_CONFIG_TEMPLATES[kind]
    expect(template.fields.length).toBeGreaterThan(0)
    expect(template.summary.trim()).not.toBe('')
    for (const field of template.fields) {
      expect(field.path.trim()).not.toBe('')
      expect(field.purpose.trim()).not.toBe('')
    }
  })

  it.each(KINDS)('%s 字段释义均为必填或可选二态', (kind) => {
    for (const field of ADMIN_CONFIG_TEMPLATES[kind].fields) {
      expect(typeof field.required).toBe('boolean')
    }
  })

  it('未登记种类返回空串（防御：新增后端 kind 未同步前端时降级为空）', () => {
    expect(sampleOf('unknown-kind' as AdminConfigKind)).toBe('')
  })

  it('fieldsOf / notesOf 暴露与常量一致的数据，未登记种类降级为空', () => {
    expect(fieldsOf('starter')).toBe(ADMIN_CONFIG_TEMPLATES.starter.fields)
    expect(notesOf('metadata')).toBe(ADMIN_CONFIG_TEMPLATES.metadata.notes)
    expect(fieldsOf('unknown-kind' as AdminConfigKind)).toEqual([])
    expect(notesOf('unknown-kind' as AdminConfigKind)).toEqual([])
  })

  it('示例中的占位符值均加引号，避免被 YAML 解析为流式映射', () => {
    const starterSample = sampleOf('starter')
    const placeholderLines = starterSample
      .split('\n')
      .filter((line) => /\{[a-zA-Z]+\}/.test(line) && line.includes(':'))
    expect(placeholderLines.length).toBeGreaterThan(0)
    for (const line of placeholderLines) {
      // 冒号右侧的值若含 {xxx} 必须以引号开头
      const value = line.slice(line.indexOf(':') + 1).trim()
      if (value.includes('{')) {
        expect(value.startsWith('"') || value.startsWith("'")).toBe(true)
      }
    }
  })
})
