package com.datagent.agentv2.git;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.git.port.GitBranchRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MetricBranchControllerTest {
    @Test
    void readsFixedBranchReleaseForPreviewWithoutTriggeringBuild() throws Exception {
        var registry = mock(GitBranchRegistry.class);
        var json = new ObjectMapper();
        when(registry.releases("b1")).thenReturn(json.readTree("[{\"releaseId\":\"r1\"}]"));
        when(registry.release("b1", "r1"))
                .thenReturn(json.createObjectNode().put("releaseId", "r1").put("state", "FAILED"));
        var mvc =
                MockMvcBuilders.standaloneSetup(new MetricBranchController(registry, json)).build();
        mvc.perform(get("/api/v1/metric-branches/b1/releases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].releaseId").value("r1"));
        mvc.perform(get("/api/v1/metric-branches/b1/releases/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"));
        verify(registry).release("b1", "r1");
        verify(registry, never()).create(any());
    }

    @Test
    void proxiesControlledBranchRequestsAndRejectsDeploymentOverrides() throws Exception {
        var registry = mock(GitBranchRegistry.class);
        var json = new ObjectMapper();
        when(registry.create(any())).thenReturn(json.createObjectNode().put("branchId", "b1"));
        var mvc =
                MockMvcBuilders.standaloneSetup(new MetricBranchController(registry, json)).build();
        String body =
                "{\"name\":\"dev/test\",\"sourceBranchId\":\"b0\",\"sourceCommitSha\":\""
                        + "a".repeat(40)
                        + "\",\"idempotencyKey\":\"key\"}";
        mvc.perform(post("/api/v1/metric-branches").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value("b1"));
        var request = ArgumentCaptor.forClass(JsonNode.class);
        verify(registry).create(request.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "dev/test", request.getValue().path("name").asText());
        mvc.perform(
                        post("/api/v1/metric-branches")
                                .contentType("application/json")
                                .content(
                                        body.substring(0, body.length() - 1)
                                                + ",\"schema\":\"production\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/metric-branches/b1").param("expectedVersion", "2"))
                .andExpect(status().isOk());
        verify(registry).delete("b1", 2);
    }
}
