package com.datagent.agentv2.git;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;

class GitChangeControllerTest {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackages = "com.datagent.agentv2.git",
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.ANNOTATION,
                            classes = RestController.class))
    static class Scan {}

    final GitChangeService changes = mock(GitChangeService.class);
    final GitSubmissionService submissions = mock(GitSubmissionService.class);
    final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(Scan.class)
                    .withPropertyValues("data-agent.git.enabled=true")
                    .withBean(GitChangeService.class, () -> changes)
                    .withBean(GitSubmissionService.class, () -> submissions);
    final String base = "/api/v1/agent-sessions/s1/git-changes/c1";

    GitChangeView view() {
        return new GitChangeView(
                "c1",
                "w1",
                "main",
                "base",
                "digest",
                "调整指标",
                "fix metrics",
                3,
                1,
                GitChangeStatus.SUBMITTING,
                2,
                null,
                null);
    }

    @Test
    void unrelatedMaterialLookupFailuresAreNotHidden() {
        when(changes.detail("s1", "c1")).thenReturn(view());
        when(changes.files("s1", "c1")).thenThrow(new GitException("GIT_CHANGE_NOT_FOUND", "不存在"));
        runner.run(
                ctx -> {
                    var controller = ctx.getBean(GitChangeController.class);
                    assertThatThrownBy(() -> controller.detail("s1", "c1"))
                            .isInstanceOfSatisfying(
                                    GitException.class,
                                    failure ->
                                            assertThat(failure.code())
                                                    .isEqualTo("GIT_CHANGE_NOT_FOUND"));
                });
    }

    @Test
    void confirmReturnsAcceptedAndFileSummaryWithoutDiff() {
        when(submissions.confirm("s1", "c1", 1)).thenReturn(view());
        when(changes.files("s1", "c1"))
                .thenReturn(
                        List.of(new GitSnapshotStore.SnapshotFile("0", "M", "metrics.yml", null)));
        runner.run(
                ctx -> {
                    var mvc =
                            MockMvcBuilders.standaloneSetup(
                                            ctx.getBeansWithAnnotation(RestController.class)
                                                    .values()
                                                    .toArray())
                                    .build();
                    mvc.perform(
                                    post(base + "/confirm")
                                            .contentType("application/json")
                                            .content("{\"version\":1}"))
                            .andExpect(status().isAccepted())
                            .andExpect(jsonPath("$.changeId").value("c1"))
                            .andExpect(jsonPath("$.reviewAvailable").value(true))
                            .andExpect(jsonPath("$.files[0].path").value("metrics.yml"))
                            .andExpect(jsonPath("$.files[0].diff").doesNotExist());
                });
    }

    @Test
    void fileDiffUsesRegisteredIdAndRequestVersionIsRequired() {
        when(changes.diff("s1", "c1", "0")).thenReturn("-old\n+new");
        runner.run(
                ctx -> {
                    var mvc =
                            MockMvcBuilders.standaloneSetup(
                                            ctx.getBeansWithAnnotation(RestController.class)
                                                    .values()
                                                    .toArray())
                                    .build();
                    mvc.perform(get(base + "/diff").param("fileId", "0"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.diff").value("-old\n+new"));
                    mvc.perform(
                                    post(base + "/confirm")
                                            .contentType("application/json")
                                            .content("{}"))
                            .andExpect(status().isBadRequest());
                    verifyNoInteractions(submissions);
                });
    }
}
