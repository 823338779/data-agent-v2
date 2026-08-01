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

    public OllamaModelProperties() {}

    public OllamaModelProperties(String model, Duration timeout, int maxOutputTokens) {
        this.model = model;
        this.timeout = timeout;
        this.maxOutputTokens = maxOutputTokens;
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
}
