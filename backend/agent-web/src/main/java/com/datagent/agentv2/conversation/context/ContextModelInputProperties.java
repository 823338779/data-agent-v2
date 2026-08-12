package com.datagent.agentv2.conversation.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 非 Ollama provider 使用的统一模型输入预算和固定协议开销。 */
@Component
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

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getSafetyReserveTokens() {
        return safetyReserveTokens;
    }

    public void setSafetyReserveTokens(int safetyReserveTokens) {
        this.safetyReserveTokens = safetyReserveTokens;
    }

    public int getFixedProtocolTokens() {
        return fixedProtocolTokens;
    }

    public void setFixedProtocolTokens(int fixedProtocolTokens) {
        this.fixedProtocolTokens = fixedProtocolTokens;
    }
}
