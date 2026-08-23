package com.datagent.agentv2.contracts;

import java.nio.file.Files;
import java.nio.file.Path;

final class ContractFiles {
    private ContractFiles() {}

    static Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("contracts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("contracts directory not found");
        }
        return current.resolve("contracts");
    }

    static Path openApi() {
        return root().resolve("openapi.yaml");
    }

    static Path schema(String name) {
        return root().resolve("schemas").resolve(name);
    }
}
