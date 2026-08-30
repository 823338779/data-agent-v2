package com.datagent.agentv2;

import java.time.ZoneId;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.datagent.agentv2")
public class AgentV2Application {

    private static final ZoneId SERVER_TIME_ZONE = ZoneId.of("Asia/Shanghai");

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(SERVER_TIME_ZONE));
        SpringApplication.run(AgentV2Application.class, args);
    }
}
