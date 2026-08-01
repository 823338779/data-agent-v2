package com.datagent.agentv2.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

class ModuleBoundaryTest {

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
}
