package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.application.DbtCandidateValidation;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.persistence.service.dbt.DbtValidationPersistenceService;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DbtCandidateValidationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"analytics/", "analytics/.", "analytics//models"})
    void nonCanonicalProjectDirectoryCannotDisableCandidateProtection(String directory) {
        assertThatThrownBy(
                        () ->
                                new DbtProperties(
                                        true,
                                        URI.create("http://localhost:8000"),
                                        "sample",
                                        directory,
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unvalidatedYamlAndMixedSqlCannotPrepareCandidate() {
        var gate =
                new DbtCandidateValidation(
                        mock(DbtValidationPersistenceService.class),
                        new DbtProperties(
                                true,
                                URI.create("http://localhost:8000"),
                                "sample",
                                ".",
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(20)),
                        new JsonMapper());
        var yaml =
                new GitDraftSnapshot(
                        "s",
                        "w",
                        "a",
                        "b",
                        1,
                        List.of(
                                new GitDraftSnapshot.File(
                                        "A", "models/a.yml", null, "version: 2\n")));
        assertThatThrownBy(() -> gate.validate("s", yaml)).isInstanceOf(GitException.class);
        var sql =
                new GitDraftSnapshot(
                        "s",
                        "w",
                        "a",
                        "b",
                        1,
                        List.of(new GitDraftSnapshot.File("A", "models/a.sql", null, "select 1")));
        assertThatThrownBy(() -> gate.validate("s", sql)).isInstanceOf(GitException.class);
    }
}
