package com.datagent.agentv2.conversation.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "data-agent.model.ollama")
public class OllamaModelProperties {

    /** Ollama 请求使用的模型名称。 */
    private String model = "qwen3.5:9b";

    /** 单次 provider 流从连接到完整终态的最大耗时。 */
    private Duration timeout = Duration.ofMinutes(2);

    /** 传递给 Ollama num_predict 的最大输出 token 数。 */
    private int maxOutputTokens = 4096;

    /** 传递给 Ollama num_ctx 的完整上下文窗口大小。 */
    private int contextWindowTokens = 65536;

    /** 从上下文窗口中固定保留、不用于业务输入的安全 token 数。 */
    private int safetyReserveTokens = 4096;

    public OllamaModelProperties() {}

    public OllamaModelProperties(String model, Duration timeout, int maxOutputTokens) {
        this(model, timeout, maxOutputTokens, 65536, 4096);
    }

    public OllamaModelProperties(
            String model,
            Duration timeout,
            int maxOutputTokens,
            int contextWindowTokens,
            int safetyReserveTokens) {
        this.model = model;
        this.timeout = timeout;
        this.maxOutputTokens = maxOutputTokens;
        this.contextWindowTokens = contextWindowTokens;
        this.safetyReserveTokens = safetyReserveTokens;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public int getSafetyReserveTokens() {
        return safetyReserveTokens;
    }

    public void setSafetyReserveTokens(int safetyReserveTokens) {
        this.safetyReserveTokens = safetyReserveTokens;
    }
}
