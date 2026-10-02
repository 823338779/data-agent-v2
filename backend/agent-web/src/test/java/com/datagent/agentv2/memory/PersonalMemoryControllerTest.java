package com.datagent.agentv2.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.controller.PersonalMemoryController;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class PersonalMemoryControllerTest {

    private static final String BASE = "/api/user/memories";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 10, 30);

    private MemoryService memory;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        memory = mock(MemoryService.class);
        var application = new PersonalMemoryApplicationService(memory);
        mvc =
                MockMvcBuilders.standaloneSetup(new PersonalMemoryController(application))
                        .setControllerAdvice(new MemoryFailureAdvice())
                        .build();
    }

    @Test
    void listReturnsFixedGroupsCompleteQuotasAndIndependentCursors() throws Exception {
        when(memory.snapshot()).thenReturn(snapshot());

        mvc.perform(get(BASE).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.groups.person_profile.entries[0].memoryId").value("profile-1"))
                .andExpect(jsonPath("$.groups.person_profile.nextCursor").value("profile-1"))
                .andExpect(
                        jsonPath("$.groups.collaboration_preference.entries[0].memoryId")
                                .value("preference-1"))
                .andExpect(
                        jsonPath("$.groups.collaboration_preference.nextCursor")
                                .value("preference-1"))
                .andExpect(jsonPath("$.quotas.person_profile.used").value(8))
                .andExpect(jsonPath("$.quotas.person_profile.limit").value(800))
                .andExpect(jsonPath("$.quotas.collaboration_preference.used").value(12))
                .andExpect(jsonPath("$.revision").value(7));
    }

    @Test
    void listAcceptsStableLowercaseKindAndLeavesOtherGroupEmpty() throws Exception {
        when(memory.snapshot()).thenReturn(snapshot());

        mvc.perform(get(BASE).param("kind", "person_profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.person_profile.entries.length()").value(2))
                .andExpect(jsonPath("$.groups.collaboration_preference.entries.length()").value(0));
    }

    @Test
    void createReturnsCreatedOutcome() throws Exception {
        when(memory.execute(any(), any())).thenReturn(outcome(MemoryStatus.CREATED, true));

        mvc.perform(
                        post(BASE)
                                .header("Idempotency-Key", "create-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"kind":"person_profile","content":"用户主要使用中文"}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.memoryId").value("memory-1"));
    }

    @Test
    void duplicateCreateKeepsAlreadyExistsInsteadOfClaimingRestore() throws Exception {
        when(memory.execute(any(), any())).thenReturn(outcome(MemoryStatus.ALREADY_EXISTS, false));

        mvc.perform(
                        post(BASE)
                                .header("Idempotency-Key", "restore-duplicate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"kind":"collaboration_preference","content":"先讨论设计","restore":true}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void createRequiresIdempotencyHeader() throws Exception {
        mvc.perform(
                        post(BASE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"kind":"person_profile","content":"用户主要使用中文"}
                                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(memory);
    }

    @Test
    void obsoleteKindAliasIsRejected() throws Exception {
        mvc.perform(
                        post(BASE)
                                .header("Idempotency-Key", "alias-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"kind\":\"work_context\",\"content\":\"偏好证据优先\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(memory);
    }

    @Test
    void deleteHasNoBodyAndRequiresPositiveEntryVersion() throws Exception {
        when(memory.execute(any(), any())).thenReturn(outcome(MemoryStatus.REMOVED, false));

        mvc.perform(
                        delete(BASE + "/memory-1")
                                .header("Idempotency-Key", "remove-1")
                                .param("expectedVersion", "1"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(
                        delete(BASE + "/memory-1")
                                .header("Idempotency-Key", "remove-2")
                                .param("expectedVersion", "0"))
                .andExpect(status().isBadRequest());

        var command = ArgumentCaptor.forClass(MemoryCommand.class);
        org.mockito.Mockito.verify(memory)
                .execute(any(MemoryWriteContext.class), command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue())
                .isEqualTo(
                        new MemoryCommand.Remove(
                                "memory-1", 1, MemoryWriteReason.USER_REQUESTED, List.of()));
    }

    @Test
    void entryUpdateRejectsVersionZeroBeforeDomainConstruction() throws Exception {
        mvc.perform(
                        put(BASE + "/memory-1")
                                .header("Idempotency-Key", "replace-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"新内容\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(memory);
    }

    @Test
    void reusedKeyWithDifferentPayloadReturnsConflict() throws Exception {
        when(memory.execute(any(), any()))
                .thenReturn(outcome(MemoryStatus.CREATED, true))
                .thenReturn(outcome(MemoryStatus.CONFLICT, false));

        mvc.perform(
                        post(BASE)
                                .header("Idempotency-Key", "same-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"kind\":\"person_profile\",\"content\":\"中文\"}"))
                .andExpect(status().isCreated());
        mvc.perform(
                        post(BASE)
                                .header("Idempotency-Key", "same-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"kind\":\"person_profile\",\"content\":\"英文\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @ParameterizedTest
    @CsvSource({"CONFLICT,409", "QUOTA_EXCEEDED,422", "SUPPRESSED,409"})
    void writeFailuresUseTheirHttpContract(MemoryStatus failure, int expectedStatus)
            throws Exception {
        when(memory.execute(any(), any())).thenReturn(outcome(failure, false));

        mvc.perform(
                        put(BASE + "/memory-1")
                                .header("Idempotency-Key", "failure-" + failure)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"新内容\",\"expectedVersion\":1}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(failure.name()));
    }

    @Test
    void settingsSupportsPartialUpdateAndKeepsEntryVersionSeparate() throws Exception {
        when(memory.snapshot()).thenReturn(snapshot());
        when(memory.updateSettings(any(), any()))
                .thenReturn(new MemoryState(8, 3, true, true, false, 2, null));

        mvc.perform(get(BASE + "/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingsVersion").value(2))
                .andExpect(jsonPath("$.autoLearningEnabled").value(true));
        mvc.perform(
                        put(BASE + "/settings")
                                .header("Idempotency-Key", "settings-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":2,\"autoLearningEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingsVersion").value(3))
                .andExpect(jsonPath("$.readEnabled").value(true))
                .andExpect(jsonPath("$.explicitWriteEnabled").value(true))
                .andExpect(jsonPath("$.autoLearningEnabled").value(false));

        var change = ArgumentCaptor.forClass(MemorySettingsChange.class);
        org.mockito.Mockito.verify(memory)
                .updateSettings(org.mockito.ArgumentMatchers.eq("settings-1"), change.capture());
        org.assertj.core.api.Assertions.assertThat(change.getValue())
                .isEqualTo(new MemorySettingsChange(2, null, null, false));
    }

    private static MemoryOutcome outcome(MemoryStatus status, boolean created) {
        return new MemoryOutcome(
                status, "memory-1", 1L, 7, created, status != MemoryStatus.REMOVED, quotas());
    }

    private static MemorySnapshot snapshot() {
        return new MemorySnapshot(
                new MemoryState(7, 2, true, true, true, 1, NOW.minusDays(1)),
                List.of(
                        memory("profile-2", MemoryKind.PERSON_PROFILE, "后续条目"),
                        memory("profile-1", MemoryKind.PERSON_PROFILE, "中文偏好"),
                        memory("preference-2", MemoryKind.COLLABORATION_PREFERENCE, "先列证据再判断"),
                        memory("preference-1", MemoryKind.COLLABORATION_PREFERENCE, "先讨论设计")),
                quotas());
    }

    private static MemoryEntry memory(String id, MemoryKind kind, String content) {
        return new MemoryEntry(
                id,
                kind,
                content,
                "hash",
                1,
                content.length(),
                MemorySource.USER_UI,
                true,
                1,
                NOW,
                NOW,
                NOW,
                null);
    }

    private static Map<MemoryKind, MemoryQuota> quotas() {
        return Map.of(
                MemoryKind.PERSON_PROFILE,
                new MemoryQuota(8, 800),
                MemoryKind.COLLABORATION_PREFERENCE,
                new MemoryQuota(12, 1500));
    }

    /** Web 模块只验证路由传播的业务状态；服务端测试覆盖生产 ProblemDetail 结构。 */
    @RestControllerAdvice
    static class MemoryFailureAdvice {
        @ExceptionHandler(MemoryException.class)
        ResponseEntity<Map<String, String>> handle(MemoryException failure) {
            HttpStatus status =
                    switch (failure.code()) {
                        case INVALID_REQUEST, UNSAFE_CONTENT -> HttpStatus.BAD_REQUEST;
                        case NOT_FOUND -> HttpStatus.NOT_FOUND;
                        case QUOTA_EXCEEDED -> HttpStatus.UNPROCESSABLE_CONTENT;
                        case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                        default -> HttpStatus.CONFLICT;
                    };
            return ResponseEntity.status(status).body(Map.of("code", failure.code().name()));
        }
    }
}
