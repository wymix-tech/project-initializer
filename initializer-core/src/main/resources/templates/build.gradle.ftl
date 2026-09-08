plugins {
    id 'java'
    id 'org.springframework.boot' version '${bootVersion}'
    id 'io.spring.dependency-management' version '1.1.4'
}

group = '${group}'
version = '0.0.1-SNAPSHOT'

java {
    sourceCompatibility = JavaVersion.toVersion('${javaVersion}')
}

repositories {
    mavenCentral()
}

dependencies {
<#-- starter 依赖由 starter 清单/元数据坐标驱动，新增 starter 无需改模板 -->
<#list starterDependencies as dep>
<#if dep.version?? && dep.version != ''>
    implementation '${dep.groupId}:${dep.artifactId}:${dep.version}'
<#else>
    implementation '${dep.groupId}:${dep.artifactId}'
</#if>
</#list>
}

tasks.named('test') {
    useJUnitPlatform()
}
