package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.dbt.model.*;
import com.datagent.agentv2.dbt.model.DbtContracts.DraftChange;
import com.datagent.agentv2.git.model.*;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DbtBranchDigestTest {
    final DbtProperties properties =
            new DbtProperties(
                    true,
                    URI.create("http://localhost"),
                    "metrics",
                    ".",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2));

    @Test
    void exactPythonFixtureIncludesUnicodeCrLfEmptyAndDeletion() throws Exception {
        var json = new JsonMapper();
        var fixture =
                json.readTree(getClass().getResourceAsStream("/dbt-contract/changes-v2.json"));
        var changes =
                json.readerForListOf(DraftChange.class)
                        .<List<DraftChange>>readValue(fixture.path("changes"));
        assertThat(DbtChangesDigest.digestV2(changes)).isEqualTo(fixture.path("digest").asString());
        assertThat(DbtChangesDigest.digestV2(changes.reversed()))
                .isEqualTo(fixture.path("digest").asString());
        assertThat(DbtChangesDigest.digest(changes))
                .isNotEqualTo(DbtChangesDigest.digestV2(changes));
    }

    @Test
    void branchScopeAllowsSqlYamlAndTestsAndRejectsTraversalOrCaseCollisions() {
        var files =
                List.of(
                        new GitDraftSnapshot.File("A", "models/a.sql", null, "select 1\r\n"),
                        new GitDraftSnapshot.File("A", "models/schema.yml", null, "version: 2\n"),
                        new GitDraftSnapshot.File(
                                "A", "tests/check.sql", null, "select 1 where false\n"));
        var snapshot =
                new GitDraftSnapshot(
                        "session",
                        "workspace",
                        "a".repeat(40),
                        "b".repeat(40),
                        2,
                        files,
                        "metrics",
                        "branch",
                        "refs/heads/dev");
        assertThat(DbtDraftSnapshot.composeFromGit(snapshot, properties).changes()).hasSize(3);
        for (String forbidden :
                List.of(
                        "models/../profiles.yml",
                        "macros/x.sql",
                        "dbt_project.yml",
                        "models/.git/a.sql")) {
            var bad =
                    new GitDraftSnapshot(
                            "session",
                            "workspace",
                            "a".repeat(40),
                            "b".repeat(40),
                            2,
                            List.of(new GitDraftSnapshot.File("A", forbidden, null, "select 1")),
                            "metrics",
                            "branch",
                            "refs/heads/dev");
            assertThatThrownBy(() -> DbtDraftSnapshot.composeFromGit(bad, properties))
                    .isInstanceOf(GitException.class);
        }
        var collision =
                new GitDraftSnapshot(
                        "session",
                        "workspace",
                        "a".repeat(40),
                        "b".repeat(40),
                        2,
                        List.of(
                                files.getFirst(),
                                new GitDraftSnapshot.File("A", "models/A.sql", null, "select 2")),
                        "metrics",
                        "branch",
                        "refs/heads/dev");
        assertThatThrownBy(() -> DbtDraftSnapshot.composeFromGit(collision, properties))
                .isInstanceOf(GitException.class);
    }
}
