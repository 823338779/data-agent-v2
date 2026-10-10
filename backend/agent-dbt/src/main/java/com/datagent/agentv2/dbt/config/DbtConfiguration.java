package com.datagent.agentv2.dbt.config;

import com.datagent.agentv2.dbt.client.*;
import com.datagent.agentv2.dbt.port.*;
import com.datagent.agentv2.dbt.tool.DbtAnalysisTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** dbt分析仅依赖可信构建选择和HTTP，不依赖Git或开发业务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "data-agent.dbt", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DbtProperties.class)
public class DbtConfiguration {
    @Bean(destroyMethod = "close")
    HttpDbtServiceClient dbtServiceClient(DbtProperties p, ObjectMapper j) {
        return new HttpDbtServiceClient(p, j);
    }

    @Bean
    DbtAnalysisTool dbtBuildGet(
            DbtServiceClient c, ObjectMapper j, BuildContextResolver b, AnalysisSessionResolver s) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.BUILD, c, j, b, s);
    }

    @Bean
    DbtAnalysisTool dbtCatalogSearch(
            DbtServiceClient c, ObjectMapper j, BuildContextResolver b, AnalysisSessionResolver s) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.CATALOG, c, j, b, s);
    }

    @Bean
    DbtAnalysisTool dbtResourceGet(
            DbtServiceClient c, ObjectMapper j, BuildContextResolver b, AnalysisSessionResolver s) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.RESOURCE, c, j, b, s);
    }

    @Bean
    DbtAnalysisTool dbtQueryOptions(
            DbtServiceClient c, ObjectMapper j, BuildContextResolver b, AnalysisSessionResolver s) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.OPTIONS, c, j, b, s);
    }

    @Bean
    DbtAnalysisTool dbtQuerySubmit(
            DbtServiceClient c, ObjectMapper j, BuildContextResolver b, AnalysisSessionResolver s) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.SUBMIT, c, j, b, s);
    }

    @Bean
    DbtAnalysisTool dbtQueryGet(
            DbtServiceClient c, ObjectMapper j, BuildContextResolver b, AnalysisSessionResolver s) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.GET, c, j, b, s);
    }
}
