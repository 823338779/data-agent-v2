package com.datagent.agentv2.development;

import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.Yaml;

/** 直接提供当前应用打包的OpenAPI路由，跨产品验收无需维护平行契约清单。 */
@RestController
public final class DevelopmentContractController {
    private static final String PATH = "/api/v1/development/contract",
            RESOURCE = "/contracts/openapi.yaml",
            PATHS = "paths",
            PROTOCOL = "build-deployment-v3";

    /** 随构建固定的只读路由定义。 */
    private final ContractView contract;

    public DevelopmentContractController() throws IOException {
        try (var input = getClass().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("当前构建缺少OpenAPI契约");
            Map<String, Object> source = new Yaml().load(input);
            contract = new ContractView(PROTOCOL, source.get(PATHS));
        }
    }

    @GetMapping(PATH)
    public ContractView get() {
        return contract;
    }

    public record ContractView(
            /** 跨产品协议版本。 */
            String protocolVersion,
            /** 同源OpenAPI路径。 */
            Object paths) {}
}
