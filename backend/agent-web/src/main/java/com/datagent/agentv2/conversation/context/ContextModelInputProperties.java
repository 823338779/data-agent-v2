package com.datagent.agentv2.conversation.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 非 Ollama provider 使用的统一模型输入预算和固定协议开销。 */
@Component
@Getter
@Setter
@ConfigurationProperties("data-agent.context.model-input")
public class ContextModelInputProperties {

    /** 模型完整上下文窗口。 */
    private int contextWindowTokens = 65_536;

    /** 为本次模型输出预留的最大 token。 */
    private int maxOutputTokens = 4_096;

    /** 不允许业务输入占用的安全余量。 */
    private int safetyReserveTokens = 4_096;

    /** provider adapter 固定包装和协议边界的估算 token。 */
    private int fixedProtocolTokens = 256;

}
