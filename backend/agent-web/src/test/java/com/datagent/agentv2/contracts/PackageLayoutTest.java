package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackageLayoutTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/datagent/agentv2");
    private static final String CONTROLLER_FILE_SUFFIX = "Controller.java";
    private static final String CONTROLLER_PACKAGE = "package com.datagent.agentv2.controller;";
    private static final String LEGACY_WEB_PACKAGE = "package com.datagent.agentv2.web.";
    private static final String LEGACY_APPLICATION_PACKAGE =
            "package com.datagent.agentv2.conversation.application;";
    private static final String LEGACY_CONSTANTS_FILE = "AgentExecutionConstants.java";

    @Test
    void controllersShareOnePackage() throws IOException {
        // Controller 作为 HTTP 入口统一收口，避免按业务散落后难以发现接口边界。
        List<Path> controllers =
                javaSources().stream()
                        .filter(
                                path ->
                                        path.getFileName()
                                                .toString()
                                                .endsWith(CONTROLLER_FILE_SUFFIX))
                        .toList();

        assertThat(controllers).isNotEmpty();
        for (Path controller : controllers) {
            assertThat(Files.readString(controller)).contains(CONTROLLER_PACKAGE);
        }
    }

    @Test
    void legacyCatchAllPackagesAndConstantBagAreRemoved() throws IOException {
        // 非 Controller 类必须按业务能力归档，同时移除聚合所有业务取值的字符串常量袋。
        List<Path> sources = javaSources();

        assertThat(sources)
                .noneMatch(path -> path.getFileName().toString().equals(LEGACY_CONSTANTS_FILE));
        for (Path source : sources) {
            String content = Files.readString(source);
            assertThat(content).doesNotContain(LEGACY_WEB_PACKAGE, LEGACY_APPLICATION_PACKAGE);
        }
    }

    private static List<Path> javaSources() throws IOException {
        try (var paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
