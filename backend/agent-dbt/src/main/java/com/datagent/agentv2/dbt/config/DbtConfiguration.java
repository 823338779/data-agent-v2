package com.datagent.agentv2.dbt.config;

import com.datagent.agentv2.dbt.application.*;
import com.datagent.agentv2.dbt.client.*;
import com.datagent.agentv2.dbt.tool.*;
import com.datagent.agentv2.git.application.GitWorkspaceService;
import com.datagent.agentv2.git.port.GitSessionResolver;
import com.datagent.agentv2.persistence.service.dbt.DbtValidationPersistenceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** dbt 能力装配：分析不需要 Git；维护同时要求两个开关。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "data-agent.dbt", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DbtProperties.class)
public class DbtConfiguration {
    @Bean(destroyMethod = "close")
    HttpDbtServiceClient dbtServiceClient(DbtProperties p, ObjectMapper json) {
        return new HttpDbtServiceClient(p, json);
    }

    @Bean
    DbtAnalysisTool dbtProjectGet(DbtServiceClient c, ObjectMapper j) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.PROJECT, c, j);
    }

    @Bean
    DbtAnalysisTool dbtCatalogSearch(DbtServiceClient c, ObjectMapper j) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.CATALOG, c, j);
    }

    @Bean
    DbtAnalysisTool dbtResourceGet(DbtServiceClient c, ObjectMapper j) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.RESOURCE, c, j);
    }

    @Bean
    DbtAnalysisTool dbtQueryOptions(DbtServiceClient c, ObjectMapper j) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.OPTIONS, c, j);
    }

    @Bean
    DbtAnalysisTool dbtQuerySubmit(DbtServiceClient c, ObjectMapper j) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.SUBMIT, c, j);
    }

    @Bean
    DbtAnalysisTool dbtQueryGet(DbtServiceClient c, ObjectMapper j) {
        return new DbtAnalysisTool(DbtAnalysisTool.Kind.GET, c, j);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("${data-agent.dbt.enabled:false} and ${data-agent.git.enabled:false}")
    static class Maintenance {
        @Bean
        DbtValidationService dbtValidationService(
                GitWorkspaceService w,
                DbtValidationPersistenceService r,
                DbtServiceClient c,
                DbtProperties p,
                ObjectMapper j) {
            return new DbtValidationService(w, r, c, p, j);
        }

        @Bean
        DbtCandidateValidation dbtCandidateValidation(
                DbtValidationPersistenceService r, DbtProperties p, ObjectMapper j) {
            return new DbtCandidateValidation(r, p, j);
        }

        @Bean
        DbtValidationTool dbtValidationSubmit(
                DbtValidationService s, GitSessionResolver r, ObjectMapper j) {
            return new DbtValidationTool(true, s, r, j);
        }

        @Bean
        DbtValidationTool dbtValidationGet(
                DbtValidationService s, GitSessionResolver r, ObjectMapper j) {
            return new DbtValidationTool(false, s, r, j);
        }
    }
}
