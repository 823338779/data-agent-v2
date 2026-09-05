package com.datagent.agentv2.artifact;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.artifact.application.ArtifactDownload;
import com.datagent.agentv2.artifact.application.ArtifactQueryService;
import com.datagent.agentv2.artifact.storage.ArtifactReadHandle;
import com.datagent.agentv2.artifact.storage.ArtifactStorage;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArtifactControllerTest {

    @Test
    void streamsValidatedArtifactWithDownloadHeaders() throws Exception {
        byte[] bytes = {1, 2, 3};
        String digest = "a".repeat(64);
        ArtifactStorage storage = mock(ArtifactStorage.class);
        when(storage.openReady("ready/artifact-1/output.docx", bytes.length, digest))
                .thenAnswer(
                        ignored ->
                                new ArtifactReadHandle(
                                        new ByteArrayInputStream(bytes), bytes.length, digest));
        ArtifactQueryService artifacts = mock(ArtifactQueryService.class);
        when(artifacts.findDownload("session-1", "artifact-1"))
                .thenReturn(
                        Optional.of(
                                new ArtifactDownload(
                                        "报告.docx",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        bytes.length,
                                        digest,
                                        "ready/artifact-1/output.docx",
                                        storage)));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new ArtifactController(artifacts)).build();

        mvc.perform(
                        get(
                                "/api/v1/agent-sessions/session-1/artifacts/artifact-1/content"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Length", "3"))
                .andExpect(header().string("ETag", '"' + digest + '"'))
                .andExpect(
                        header()
                                .string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.containsString(
                                                "filename*=UTF-8''%E6%8A%A5%E5%91%8A.docx")));
    }

    @Test
    void hidesMissingOrCrossSessionArtifact() throws Exception {
        ArtifactQueryService artifacts = mock(ArtifactQueryService.class);
        when(artifacts.findDownload("session-2", "artifact-1"))
                .thenReturn(Optional.empty());
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new ArtifactController(artifacts)).build();

        mvc.perform(
                        get(
                                "/api/v1/agent-sessions/session-2/artifacts/artifact-1/content"))
                .andExpect(status().isNotFound());
    }
}
