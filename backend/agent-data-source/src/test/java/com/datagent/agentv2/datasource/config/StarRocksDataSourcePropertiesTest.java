package com.datagent.agentv2.datasource.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

class StarRocksDataSourcePropertiesTest {

    @Test
    void rejectsMissingJdbcUrlAndNonPositiveLimits() {
        assertThatThrownBy(
                        () ->
                                StarRocksDataSourceProperties.checked(
                                        "",
                                        "user",
                                        "secret",
                                        "default_catalog",
                                        "analytics",
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(30),
                                        0,
                                        DataSize.ofMegabytes(4),
                                        new ClassPathResource("semantic/starrocks.yaml")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
