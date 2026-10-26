package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.conflict.ConflictRule;
import com.wymix.initializer.core.metadata.BootVersion;
import com.wymix.initializer.core.metadata.JavaVersion;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CatalogValidatorTest {
    private final CatalogValidator validator = new CatalogValidator();

    private MetadataCatalog metadata(StarterCatalog starters) {
        return MetadataCatalog.of(List.of(new JavaVersion("17", "Java 17", false, "3.2.5")),
                List.of(new BootVersion("3.2.5", "Boot", true, List.of("17"), List.of()))).merge(starters);
    }

    private StarterCatalogEntry entry(String id, String version, LinkageSpec links, List<List<String>> requires) {
        return new StarterCatalogEntry(id, "tool", Maintainer.INTERNAL, id,
                List.of(new DependencyCoord("com.example", "shared", version)), links, false, requires, List.of(), false);
    }

    @Test
    void rejectsMissingCoreTemplateAndUnknownRuntimeParameter() {
        StarterCatalog starters = StarterCatalog.of(List.of());
        Map<String, String> templates = new LinkedHashMap<>(ClasspathTemplates.load());
        templates.remove("pom.xml.ftl");
        assertThatThrownBy(() -> validator.validate(metadata(starters), starters, templates, ConflictCatalog.empty()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("pom.xml.ftl").hasMessageContaining("发布预检");
        templates.putAll(ClasspathTemplates.load());
        templates.put("README.md.ftl", "${unknownParameter}");
        assertThatThrownBy(() -> validator.validate(metadata(starters), starters, templates, ConflictCatalog.empty()))
                .hasMessageContaining("unknownParameter").hasMessageContaining("README.md.ftl");
    }

    @Test
    void rejectsMissingLinkageAndYamlConflictsDuringActualRendering() {
        LinkageSpec links = new LinkageSpec(null, List.of(), List.of(
                new YmlFragmentLinkage("one.yml", YmlFragmentLinkage.MergeStrategy.APPEND),
                new YmlFragmentLinkage("two.yml", YmlFragmentLinkage.MergeStrategy.APPEND)), List.of());
        StarterCatalog starters = StarterCatalog.of(List.of(entry("linked", "1", links, List.of())));
        Map<String, String> templates = new LinkedHashMap<>(ClasspathTemplates.load());
        assertThatThrownBy(() -> validator.validate(metadata(starters), starters, templates, ConflictCatalog.empty()))
                .hasMessageContaining("linked").hasMessageContaining("one.yml");
        templates.put("one.yml", "demo:\n  value: first\n");
        templates.put("two.yml", "demo:\n  value: second\n");
        assertThatThrownBy(() -> validator.validate(metadata(starters), starters, templates, ConflictCatalog.empty()))
                .hasMessageContaining("linked").hasMessageContaining("demo.value");
    }

    @Test
    void dependencyVersionConflictIsStableUnderReorderedSelection() {
        LinkageSpec noLinks = new LinkageSpec(null, List.of(), List.of(), List.of());
        StarterCatalog starters = StarterCatalog.of(List.of(entry("a", "1.0", noLinks, List.of()),
                entry("b", "2.0", noLinks, List.of())));
        LinkageRenderer renderer = new LinkageRenderer(starters, new TemplateRenderer(Map.of()), Map.of());
        Throwable first = catchThrowable(() -> renderer.resolveDependencies("3.2.5", List.of("a", "b")));
        Throwable second = catchThrowable(() -> renderer.resolveDependencies("3.2.5", List.of("b", "a")));
        assertThat(first).isInstanceOf(BusinessException.class).hasMessageContaining("com.example:shared")
                .hasMessageContaining("a=1.0").hasMessageContaining("b=2.0");
        assertThat(second.getMessage()).isEqualTo(first.getMessage());
    }

    @Test
    void preflightBacktracksAcrossOrDependencies() {
        LinkageSpec noLinks = new LinkageSpec(null, List.of(), List.of(), List.of());
        StarterCatalog starters = StarterCatalog.of(List.of(entry("a", "1", noLinks, List.of(List.of("b", "c"))),
                entry("b", "1", noLinks, List.of()), entry("c", "1", noLinks, List.of())));
        ConflictCatalog conflicts = new ConflictCatalog(List.of(
                new ConflictRule("a-b", ConflictRule.Type.PAIR, List.of("a", "b"), "a/b conflict")));
        assertThatCode(() -> validator.validate(metadata(starters), starters, ClasspathTemplates.load(), conflicts))
                .doesNotThrowAnyException();
    }
}
