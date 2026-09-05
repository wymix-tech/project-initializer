import { describe, expect, it } from 'vitest'
import { buildStarterYaml, findUnknownPlaceholders, DOC_SEPARATOR } from '@/starter-yaml-builder'
import { emptyStarterConfigForm, type StarterConfigForm } from '@/types/starter-config'

/**
 * starter 配置 YAML 渲染器单测：覆盖空联动、全量联动、多依赖、占位符白名单等边界，
 * 确保产出与后端 YamlStarterParser 契约一致。
 */
describe('buildStarterYaml', () => {
  it('官方 starter 无联动渲染 linkages: {}', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'web',
      category: 'web',
      maintainer: 'official',
      description: 'Spring MVC + 内嵌 Tomcat',
      dependencies: [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('id: web')
    expect(yml).toContain('category: web')
    expect(yml).toContain('maintainer: official')
    expect(yml).toContain('description: Spring MVC + 内嵌 Tomcat')
    expect(yml).toContain('- groupId: org.springframework.boot')
    expect(yml).toContain('  artifactId: spring-boot-starter-web')
    // 官方 starter 无 version（走 Boot BOM）
    expect(yml).not.toContain('version:')
    expect(yml).toContain('linkages: {}')
  })

  it('描述为空时缺省取 id', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo-starter',
      description: '',
      dependencies: [{ rowId: 'r1', groupId: 'com.example', artifactId: 'demo-starter', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('description: demo-starter')
  })

  it('内部 starter 多依赖且显式 version', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'xxx-redis-spring-boot-starter',
      category: 'middleware',
      maintainer: 'internal',
      description: '内部 Redis 封装',
      dependencies: [
        { rowId: 'r1', groupId: 'com.company', artifactId: 'xxx-redis-spring-boot-starter', version: '1.2.0' },
        { rowId: 'r2', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-data-redis', version: '' },
      ],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('- groupId: com.company')
    expect(yml).toContain('  version: "1.2.0"')
    expect(yml).toContain('- groupId: org.springframework.boot')
    // 第二条无 version
    const lines = yml.split('\n')
    const secondDepIdx = lines.findIndex((l) => l.includes('- groupId: org.springframework.boot'))
    expect(lines[secondDepIdx + 1]).toContain('artifactId: spring-boot-starter-data-redis')
    // 下一条不应紧跟 version
    expect(lines[secondDepIdx + 2]).not.toContain('version')
  })

  it('全量联动渲染 config-package / templates / fragments / abstract-classes', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'xxx-redis-spring-boot-starter',
      category: 'middleware',
      maintainer: 'internal',
      description: '内部 Redis 封装',
      dependencies: [{ rowId: 'r1', groupId: 'com.company', artifactId: 'xxx-redis-spring-boot-starter', version: '1.2.0' }],
      configPackage: '{basePackage}.config',
      templates: [{ rowId: 'r1', source: 'starters/xxx-redis/RedisConfig.java.ftl', target: 'src/main/java/{packagePath}/config/RedisConfig.java' }],
      ymlFragments: [{ rowId: 'r1', source: 'starters/xxx-redis/application-redis.yml', mergeStrategy: 'append' }],
      abstractClasses: [{ rowId: 'r1', fqcn: 'com.company.redis.RedisSupport', alias: 'redisSupport' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('config-package: "{basePackage}.config"')
    expect(yml).toContain('templates:')
    expect(yml).toContain('- source: starters/xxx-redis/RedisConfig.java.ftl')
    expect(yml).toContain('  target: "src/main/java/{packagePath}/config/RedisConfig.java"')
    expect(yml).toContain('application-yml-fragments:')
    expect(yml).toContain('- source: starters/xxx-redis/application-redis.yml')
    expect(yml).toContain('  merge-strategy: append')
    expect(yml).toContain('abstract-classes:')
    expect(yml).toContain('- fqcn: com.company.redis.RedisSupport')
    expect(yml).toContain('  alias: redisSupport')
    // 非空 linkages 不应出现 {}
    expect(yml).not.toMatch(/linkages: \{\}/)
  })

  it('override 合并策略正确渲染', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'com.example', artifactId: 'demo', version: '' }],
      ymlFragments: [{ rowId: 'r1', source: 'a.yml', mergeStrategy: 'override' }],
    }
    expect(buildStarterYaml(form)).toContain('merge-strategy: override')
  })

  it('含空格的描述不加引号（普通多词标量，与样例一致）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'web',
      description: 'a "quoted" desc',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('description: a "quoted" desc')
  })

  it('纯数字版本号加引号防 YAML 解析为数字', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'com.example', artifactId: 'demo', version: '2024' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('version: "2024"')
  })

  it('产出可追加为多文档草稿（含 DOC_SEPARATOR）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'web',
      dependencies: [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }],
    }
    const draft = 'id: old\n'
    const merged = `${draft.trimEnd()}${DOC_SEPARATOR}${buildStarterYaml(form)}`
    expect(merged).toContain('---')
    expect(merged.indexOf('id: old')).toBeLessThan(merged.indexOf('---'))
    expect(merged.indexOf('---')).toBeLessThan(merged.indexOf('id: web'))
  })
})

describe('findUnknownPlaceholders', () => {
  it('白名单占位符不报错', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{basePackage}.config',
      templates: [{ rowId: 'r1', source: 's.ftl', target: '{packagePath}/x.java' }],
    }
    expect(findUnknownPlaceholders(form)).toEqual([])
  })

  it('越界占位符被识别', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{basePackage}.config',
      templates: [{ rowId: 'r1', source: 's.ftl', target: '{unknwnPath}/x.java' }],
    }
    const unknown = findUnknownPlaceholders(form)
    expect(unknown).toContain('{unknwnPath}')
  })

  it('多字段越界占位符去重', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{foo}',
      templates: [{ rowId: 'r1', source: '{foo}', target: '{foo}' }],
    }
    const unknown = findUnknownPlaceholders(form)
    expect(unknown.filter((u) => u === '{foo}').length).toBe(1)
  })

  it('无联动表单无占位符', () => {
    const form = emptyStarterConfigForm()
    form.id = 'web'
    expect(findUnknownPlaceholders(form)).toEqual([])
  })
})

describe('buildStarterYaml 标量引号策略', () => {
  it('纯整数 id 加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: '123',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('id: "123"')
  })

  it('负数 id 加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: '-42',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('id: "-42"')
  })

  it('布尔值 id 加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'true',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('id: "true"')
  })

  it('null 值 id 加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'null',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('id: "null"')
  })

  it('大写布尔值加引号 True/False/Null', () => {
    for (const v of ['True', 'False', 'Null', 'TRUE', 'FALSE', 'NULL']) {
      const form: StarterConfigForm = {
        ...emptyStarterConfigForm(),
        id: v,
        dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      }
      expect(buildStarterYaml(form)).toContain(`id: "${v}"`)
    }
  })

  it('危险前缀字符开头的值加引号', () => {
    const prefixes = ['!', '&', '*', '[', ']', '>', '|', "'", '%', '@', '#', ',', '-', '`']
    for (const prefix of prefixes) {
      const form: StarterConfigForm = {
        ...emptyStarterConfigForm(),
        id: 'demo',
        description: `${prefix}dangerous`,
        dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      }
      // 双引号前缀会被转义为 "
      const expected = prefix === '"' ? 'description: "\"dangerous"' : `description: "${prefix}dangerous"`
      expect(buildStarterYaml(form)).toContain(expected)
    }
  })

  it('双引号前缀的值加引号且转义内部双引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      description: '"quoted"',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('description: "\\"quoted\\""')
  })

  it('含花括号的值加引号（占位符场景）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      configPackage: '{basePackage}.config',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('config-package: "{basePackage}.config"')
  })

  it('含行内冒号+空格的值加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      description: 'key: value with colon',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('description: "key: value with colon"')
  })

  it('序列指示符开头的值加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      description: '- list item',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('description: "- list item"')
  })

  it('注释字符开头的值加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      description: '# comment-like',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('description: "# comment-like"')
  })

  it('含 # + 空格的值加引号（避免注释歧义）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      description: 'value with # in middle',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    // value.includes('# ') 为 true → 加引号
    expect(buildStarterYaml(form)).toContain('description: "value with # in middle"')
  })

  it('含 # 但无空格的值不加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      description: 'hash#word',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('description: hash#word')
  })

  it('普通含空格、点号、中划线的值不加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo-starter',
      description: 'A normal description with spaces',
      dependencies: [{ rowId: 'r1', groupId: 'com.example', artifactId: 'demo-artifact', version: '' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('id: demo-starter')
    expect(yml).toContain('description: A normal description with spaces')
    expect(yml).toContain('- groupId: com.example')
    expect(yml).toContain('  artifactId: demo-artifact')
  })

  it('空字符串值加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      description: '',
      dependencies: [{ rowId: 'r1', groupId: '', artifactId: '', version: '' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('description: demo') // 空描述 fallback 到 id
    expect(yml).toContain('- groupId: ""')
    expect(yml).toContain('  artifactId: ""')
  })

  it('version 含反斜杠和双引号时正确转义', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '1.0"\\beta' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('version: "1.0\\"\\\\beta"')
  })

  it('空 version 不渲染 version 行', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).not.toContain('version:')
  })

  it('仅含空格的 version 不渲染 version 行', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '   ' }],
    }
    expect(buildStarterYaml(form)).not.toContain('version:')
  })
})

describe('renderLinkages 部分联动组合', () => {
  it('仅 config-package 联动', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{basePackage}.config',
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('config-package: "{basePackage}.config"')
    expect(yml).not.toContain('templates:')
    expect(yml).not.toContain('application-yml-fragments:')
    expect(yml).not.toContain('abstract-classes:')
  })

  it('仅 templates 联动', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      templates: [{ rowId: 'r1', source: 's.ftl', target: 't.java' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('templates:')
    expect(yml).toContain('- source: s.ftl')
    expect(yml).toContain('  target: t.java')
    expect(yml).not.toContain('config-package:')
    expect(yml).not.toContain('application-yml-fragments:')
  })

  it('仅 ymlFragments 联动', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      ymlFragments: [{ rowId: 'r1', source: 'frag.yml', mergeStrategy: 'append' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('application-yml-fragments:')
    expect(yml).toContain('- source: frag.yml')
    expect(yml).toContain('  merge-strategy: append')
    expect(yml).not.toContain('templates:')
    expect(yml).not.toContain('config-package:')
  })

  it('仅 abstractClasses 联动', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      abstractClasses: [{ rowId: 'r1', fqcn: 'com.example.Abs', alias: 'abs' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('abstract-classes:')
    expect(yml).toContain('- fqcn: com.example.Abs')
    expect(yml).toContain('  alias: abs')
    expect(yml).not.toContain('templates:')
    expect(yml).not.toContain('config-package:')
    expect(yml).not.toContain('application-yml-fragments:')
  })

  it('config-package + templates 联动（不含 fragments/abstract）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{basePackage}.cfg',
      templates: [{ rowId: 'r1', source: 's.ftl', target: 't.java' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('config-package:')
    expect(yml).toContain('templates:')
    expect(yml).not.toContain('application-yml-fragments:')
    expect(yml).not.toContain('abstract-classes:')
  })

  it('templates + abstractClasses 联动（不含 config/fragments）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      templates: [{ rowId: 'r1', source: 's.ftl', target: 't.java' }],
      abstractClasses: [{ rowId: 'r1', fqcn: 'com.x.A', alias: 'a' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('templates:')
    expect(yml).toContain('abstract-classes:')
    expect(yml).not.toContain('config-package:')
    expect(yml).not.toContain('application-yml-fragments:')
  })

  it('多模板多抽象类正确渲染所有行', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      templates: [
        { rowId: 'r1', source: 's1.ftl', target: 't1.java' },
        { rowId: 'r2', source: 's2.ftl', target: 't2.java' },
      ],
      abstractClasses: [
        { rowId: 'r1', fqcn: 'com.x.A', alias: 'a' },
        { rowId: 'r2', fqcn: 'com.y.B', alias: 'b' },
      ],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('- source: s1.ftl')
    expect(yml).toContain('  target: t1.java')
    expect(yml).toContain('- source: s2.ftl')
    expect(yml).toContain('  target: t2.java')
    expect(yml).toContain('- fqcn: com.x.A')
    expect(yml).toContain('  alias: a')
    expect(yml).toContain('- fqcn: com.y.B')
    expect(yml).toContain('  alias: b')
  })

  it('多 yml 片段渲染所有行', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      ymlFragments: [
        { rowId: 'r1', source: 'f1.yml', mergeStrategy: 'append' },
        { rowId: 'r2', source: 'f2.yml', mergeStrategy: 'override' },
      ],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('- source: f1.yml')
    expect(yml).toContain('  merge-strategy: append')
    expect(yml).toContain('- source: f2.yml')
    expect(yml).toContain('  merge-strategy: override')
  })
})

describe('findUnknownPlaceholders 深度覆盖', () => {
  it('扫描 ymlFragment source 中的越界占位符', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      ymlFragments: [{ rowId: 'r1', source: '{badPlaceholder}.yml', mergeStrategy: 'append' }],
    }
    expect(findUnknownPlaceholders(form)).toContain('{badPlaceholder}')
  })

  it('扫描 abstractClass fqcn 和 alias 中的越界占位符', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      abstractClasses: [
        { rowId: 'r1', fqcn: '{badFqcn}.Abs', alias: '{badAlias}' },
      ],
    }
    const unknown = findUnknownPlaceholders(form)
    expect(unknown).toContain('{badFqcn}')
    expect(unknown).toContain('{badAlias}')
  })

  it('扫描 template source 中的越界占位符', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      templates: [{ rowId: 'r1', source: '{badSource}.ftl', target: 't.java' }],
    }
    expect(findUnknownPlaceholders(form)).toContain('{badSource}')
  })

  it('所有白名单占位符均合法', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{basePackage}.{packagePath}',
      templates: [{ rowId: 'r1', source: 's.ftl', target: '{packagePath}/{artifact}.java' }],
      ymlFragments: [{ rowId: 'r1', source: '{group}.yml', mergeStrategy: 'append' }],
      abstractClasses: [{ rowId: 'r1', fqcn: '{basePackage}.{bootVersion}.Abs', alias: '{javaVersion}' }],
    }
    expect(findUnknownPlaceholders(form)).toEqual([])
  })

  it('多字段同一越界占位符去重', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{dup}',
      templates: [{ rowId: 'r1', source: '{dup}', target: '{dup}' }],
      ymlFragments: [{ rowId: 'r1', source: '{dup}', mergeStrategy: 'append' }],
      abstractClasses: [{ rowId: 'r1', fqcn: '{dup}', alias: '{dup}' }],
    }
    const unknown = findUnknownPlaceholders(form)
    expect(unknown.filter((u) => u === '{dup}')).toHaveLength(1)
  })

  it('空字符串字段不扫描', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '',
    }
    expect(findUnknownPlaceholders(form)).toEqual([])
  })
})

describe('buildStarterYaml 精确输出格式', () => {
  it('输出以换行结尾', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'web',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml.endsWith('\n')).toBe(true)
  })

  it('输出包含 dependencies: 行', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'web',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('dependencies:')
  })

  it('输出行顺序正确（id → category → maintainer → description → dependencies → linkages）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'web',
      category: 'web',
      maintainer: 'official',
      description: 'desc',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{basePackage}.cfg',
    }
    const yml = buildStarterYaml(form)
    const idIdx = yml.indexOf('id: web')
    const catIdx = yml.indexOf('category: web')
    const maintIdx = yml.indexOf('maintainer: official')
    const descIdx = yml.indexOf('description: desc')
    const depIdx = yml.indexOf('dependencies:')
    const linkIdx = yml.indexOf('linkages:')
    expect(idIdx).toBeLessThan(catIdx)
    expect(catIdx).toBeLessThan(maintIdx)
    expect(maintIdx).toBeLessThan(descIdx)
    expect(descIdx).toBeLessThan(depIdx)
    expect(depIdx).toBeLessThan(linkIdx)
  })

  it('linkages 非空时以换行开头（不产生尾随空格）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{basePackage}.cfg',
    }
    const yml = buildStarterYaml(form)
    // linkages: 后应直接换行（非空联动）
    expect(yml).toMatch(/linkages:\n/)
    // 不应有尾随空格
    const lines = yml.split('\n')
    for (const line of lines) {
      expect(line === line.trimEnd() || line === '').toBe(true)
    }
  })

  it('linkages 为空时输出 linkages: {}（单行无换行）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('linkages: {}')
    // linkages 行后应只有换行结尾
    const linkLine = yml.split('\n').find((l) => l.includes('linkages'))
    expect(linkLine).toBe('linkages: {}')
  })
})

describe('needsQuote 边界（通过渲染间接验证）', () => {
  it('纯整数版本不加引号场景已覆盖（version 用 scalarString 一律加引号）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '42' }],
    }
    expect(buildStarterYaml(form)).toContain('version: "42"')
  })

  it('configPackage 含花括号但无占位符也加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{not-a-placeholder}.config',
    }
    expect(buildStarterYaml(form)).toContain('config-package: "{not-a-placeholder}.config"')
  })

  it('含行内冒号无空格的值不加引号', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      description: 'key:value',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('description: key:value')
  })
})
