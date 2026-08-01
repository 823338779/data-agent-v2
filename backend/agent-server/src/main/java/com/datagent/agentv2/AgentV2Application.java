package com.datagent.agentv2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan("com.datagent.agentv2.conversation.runtime")
@EnableScheduling
public class AgentV2Application {

    public static void main(String[] args) {
        SpringApplication.run(AgentV2Application.class, args);
    }
}
