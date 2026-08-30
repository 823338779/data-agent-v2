package com.datagent.agentv2;

import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.conversation.tool.web.WebSearchProperties;
import com.datagent.agentv2.conversation.tool.web.fetch.WebFetchProperties;
import java.time.ZoneId;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan("com.datagent.agentv2.conversation")
@EnableConfigurationProperties({
    ToolPolicyProperties.class,
    WebSearchProperties.class,
    WebFetchProperties.class
})
public class AgentV2Application {

    private static final ZoneId SERVER_TIME_ZONE = ZoneId.of("Asia/Shanghai");

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(SERVER_TIME_ZONE));
        SpringApplication.run(AgentV2Application.class, args);
    }
}
