package com.datagent.agentv2.server.development;

import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.development.application.DevelopmentRecoveryRunner;
import com.datagent.agentv2.development.tool.*;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 只装配持久动作恢复周期及工具白名单，模型结束或SSE断连不取消动作。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public class DevelopmentRuntimeConfiguration {
    private static final String SCHEDULER = "developmentScheduler",
            THREAD_PREFIX = "development-recovery-";

    /** 恢复业务由development模块承担。 */
    private final DevelopmentRecoveryRunner recovery;

    @Bean
    ThreadPoolTaskScheduler developmentScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(THREAD_PREFIX);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Scheduled(
            fixedDelayString = "${data-agent.development.recovery-delay-ms:1000}",
            scheduler = SCHEDULER)
    public void recover() {
        try {
            recovery.tick();
        } catch (RuntimeException failure) {
            log.warn("开发操作恢复扫描暂未完成，后续周期继续");
        }
    }

    @Bean
    static BeanPostProcessor developmentToolAllowance(Environment environment) {
        var tools =
                new ArrayList<>(
                        List.of(
                                DevelopmentFilesListTool.TOOL_ID,
                                DevelopmentFilesSearchTool.TOOL_ID,
                                DevelopmentFileReadTool.TOOL_ID,
                                DevelopmentFilesApplyTool.TOOL_ID,
                                DevelopmentDiffTool.TOOL_ID,
                                DevelopmentSaveTool.TOOL_ID));
        if (environment.getProperty("data-agent.dbt.enabled", Boolean.class, false))
            tools.addAll(
                    List.of(
                            DevelopmentPreviewTool.TOOL_ID,
                            DevelopmentDeliverTool.TOOL_ID,
                            DevelopmentStatusTool.TOOL_ID));
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof ToolPolicyProperties policy)) return bean;
                if (policy.initial().stream().anyMatch(tools::contains))
                    throw new IllegalStateException("开发工具必须通过Skill激活");
                var maximum = new ArrayList<>(policy.maximum());
                for (String tool : tools) if (!maximum.contains(tool)) maximum.add(tool);
                return new ToolPolicyProperties(maximum, policy.initial());
            }
        };
    }
}
