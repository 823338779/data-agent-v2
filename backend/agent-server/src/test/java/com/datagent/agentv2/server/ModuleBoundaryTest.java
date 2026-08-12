package com.datagent.agentv2.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

class ModuleBoundaryTest {

    private static final String LSM_CLASS_PATTERN =
            "classpath*:com/datagent/agentv2/context/lsm/**/*.class";
    private static final String PERSISTENCE_CLASS_PATTERN =
            "classpath*:com/datagent/agentv2/persistence/**/*.class";
    private static final String LSM_PACKAGE_REFERENCE = "com/datagent/agentv2/context/lsm/";
    private static final List<String> LSM_FORBIDDEN_REFERENCES =
            List.of(
                    "com/datagent/agentv2/conversation/react/",
                    "com/datagent/agentv2/conversation/model/",
                    "com/datagent/agentv2/server/");

    @Test
    void onlyServerDeclaresASpringBootApplication() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootApplication.class));

        assertThat(scanner.findCandidateComponents("com.datagent.agentv2"))
                .extracting(candidate -> candidate.getBeanClassName())
                .containsExactly("com.datagent.agentv2.AgentV2Application");
    }

    @Test
    void applicationComponentsOutsidePersistenceDoNotDependOnMappers() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> violations = new ArrayList<>();

        for (var candidate : scanner.findCandidateComponents("com.datagent.agentv2")) {
            String className = candidate.getBeanClassName();
            if (className == null || className.contains(".persistence.")) {
                continue;
            }
            Class<?> type = Class.forName(className);
            Arrays.stream(type.getDeclaredFields())
                    .map(Field::getType)
                    .filter(BaseMapper.class::isAssignableFrom)
                    .map(fieldType -> type.getName() + " field " + fieldType.getName())
                    .forEach(violations::add);
            Arrays.stream(type.getDeclaredConstructors())
                    .map(Constructor::getParameterTypes)
                    .flatMap(Arrays::stream)
                    .filter(BaseMapper.class::isAssignableFrom)
                    .map(
                            parameterType ->
                                    type.getName() + " constructor " + parameterType.getName())
                    .forEach(violations::add);
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void lsmPluginDoesNotReferenceWebOrServerImplementations() throws Exception {
        var resources = new PathMatchingResourcePatternResolver().getResources(LSM_CLASS_PATTERN);
        List<String> violations = new ArrayList<>();

        // 类文件常量池包含其静态依赖；直接检查可覆盖组件与非组件插件类。
        for (var resource : resources) {
            String classBytes;
            try (InputStream input = resource.getInputStream()) {
                classBytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            for (String forbiddenReference : LSM_FORBIDDEN_REFERENCES) {
                if (classBytes.contains(forbiddenReference)) {
                    violations.add(resource.getFilename() + " -> " + forbiddenReference);
                }
            }
        }

        assertThat(resources).isNotEmpty();
        assertThat(violations).isEmpty();
    }

    @Test
    void sharedPersistenceDoesNotReferenceLsmPluginImplementations() throws Exception {
        var resources =
                new PathMatchingResourcePatternResolver().getResources(PERSISTENCE_CLASS_PATTERN);
        List<String> violations = new ArrayList<>();

        // 通用持久化模块不得反向依赖可移除插件，避免 raw 部署仍需加载 LSM 类。
        for (var resource : resources) {
            String classBytes;
            try (InputStream input = resource.getInputStream()) {
                classBytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            if (classBytes.contains(LSM_PACKAGE_REFERENCE)) {
                violations.add(resource.getFilename());
            }
        }

        assertThat(resources).isNotEmpty();
        assertThat(violations).isEmpty();
    }
}
