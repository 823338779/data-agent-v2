package com.datagent.agentv2.server.development;

import com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 停写窗口内显式执行旧开发绑定迁移；默认dry-run并回滚所有数据库变更。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "data-agent.development.migration",
        name = "enabled",
        havingValue = "true")
public class DevelopmentMigrationRunner implements ApplicationRunner {
    private static final String REPORT = "developmentMigration";

    /** 含projectId和repositoryMappings的JSON文件，不接收凭据。 */
    @Value("${data-agent.development.migration.input}")
    private Path input;

    /** 默认只验证并报告，不提交迁移。 */
    @Value("${data-agent.development.migration.dry-run:true}")
    private boolean dryRun;

    /** 所有写入保持在同一可回滚事务。 */
    private final TransactionTemplate transactions;

    /** 产品归属的历史转换用例。 */
    private final DevelopmentContextPersistenceService records;

    /** 仅解析显式输入及序列化无凭据报告。 */
    private final ObjectMapper json;

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        // 文件读取在事务外；迁移实现不执行Git或dbt I/O。
        var request = json.readValue(Files.readString(input), Request.class);
        var result =
                transactions.execute(
                        status -> {
                            var reports =
                                    records.migrateLegacy(
                                            request.projectId(), request.repositoryMappings());
                            if (dryRun) status.setRollbackOnly();
                            return reports;
                        });
        System.out.println(json.writeValueAsString(Map.of(REPORT, new Result(dryRun, result))));
    }

    public record Request(
            /** 已显式配置repository与两个环境的Agent项目。 */
            String projectId,
            /** 旧repositoryId到新规范仓库地址的明确映射。 */
            Map<String, String> repositoryMappings) {}

    public record Result(
            /** true表示本次事务已回滚。 */
            boolean dryRun,
            /** 逐工作区诊断，不包含本地正文和凭据。 */
            List<DevelopmentContextPersistenceService.MigrationReport> items) {}
}
