package com.wymix.initializer.core.generator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BuildScriptRenderer} 实现的模板/产物路径映射测试。
 */
class BuildScriptRendererTest {

    @Test
    void mavenRendererShouldMapPomTemplates() {
        MavenRenderer renderer = new MavenRenderer();
        assertThat(renderer.buildScriptTemplate()).isEqualTo("pom.xml.ftl");
        assertThat(renderer.buildScriptPath()).isEqualTo("pom.xml");
        assertThat(renderer.settingsTemplate()).isNull();
        assertThat(renderer.settingsPath()).isNull();
    }

    @Test
    void gradleRendererShouldMapGradleTemplates() {
        GradleRenderer renderer = new GradleRenderer();
        assertThat(renderer.buildScriptTemplate()).isEqualTo("build.gradle.ftl");
        assertThat(renderer.buildScriptPath()).isEqualTo("build.gradle");
        assertThat(renderer.settingsTemplate()).isEqualTo("settings.gradle.ftl");
        assertThat(renderer.settingsPath()).isEqualTo("settings.gradle");
    }
}
