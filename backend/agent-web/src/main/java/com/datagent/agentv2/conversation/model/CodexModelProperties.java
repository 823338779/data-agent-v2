package com.datagent.agentv2.conversation.model;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 通过本机 Codex 登录会话调用 OpenAI 模型时使用的运行参数。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "data-agent.model.codex")
public class CodexModelProperties {

    /** 本机 Codex CLI 的可执行文件名或绝对路径。 */
    private String executable = "codex";

    /** 可选模型覆盖；留空时使用当前 Codex 登录配置的默认模型。 */
    private String model = "";

    /** 可选推理强度覆盖；留空时使用当前 Codex 登录配置。 */
    private String effort = "";

    /** 单次 Codex App Server 调用从启动到终态的最大耗时。 */
    private Duration timeout = Duration.ofMinutes(5);

    /** 隔离的只读工作目录；留空时为每次调用创建临时空目录。 */
    private String workingDirectory = "";
}
