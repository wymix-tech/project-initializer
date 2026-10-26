# ${artifact}

由 [project-initializer](https://cnb.cool/wymix.top/project-initializer) 生成的 Spring Boot 项目骨架。

- Group: `${group}`
- Artifact: `${artifact}`
- Base Package: `${basePackage}`
- Java: `${javaVersion}`
- Spring Boot: `${bootVersion}`
- Starters: <#list starters as s>`${s}`<#if s_has_next>, </#if></#list>

## 快速启动

```bash
<#if (buildTool!"maven") == "gradle">
gradle bootRun
<#else>
mvn spring-boot:run
</#if>
```
