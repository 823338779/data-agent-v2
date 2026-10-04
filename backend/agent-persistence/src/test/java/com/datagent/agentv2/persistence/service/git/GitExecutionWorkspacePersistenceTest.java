package com.datagent.agentv2.persistence.service.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.persistence.AgentPersistenceTestApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 验证真实数据库中的多会话关联与不可变执行绑定。 */
@SpringBootTest(classes = AgentPersistenceTestApplication.class)
@Import({
    GitWorkspacePersistenceService.class,
    GitSessionWorkspaceService.class,
    GitExecutionWorkspaceService.class
})
class GitExecutionWorkspacePersistenceTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    private static final String SESSION = "s1";
    private static final String OTHER_SESSION = "s2";
    private static final String A = "wa";
    private static final String B = "wb";
    private static final String EXECUTION = "execution-a";
    private static final String OTHER_EXECUTION = "execution-b";
    private static final String SEED =
            """
            insert into git_workspace(workspace_id,conversation_session_id,repository_id,status,project_id,branch_id,git_ref)
            values ('wa','s1','repo','READY','p','a','refs/heads/a'),
                   ('wb','s1','repo','READY','p','b','refs/heads/b')
            """;
    private static final String CLEAR = "truncate git_workspace cascade";
    private static final String BREAK =
            "update git_workspace set legacy_read_only=true where workspace_id='wa'";

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GitSessionWorkspaceService sessions;
    @Autowired private GitExecutionWorkspaceService executions;

    @BeforeEach
    void seed() {
        jdbc.execute(CLEAR);
        jdbc.execute(SEED);
        sessions.attach(SESSION, A);
        sessions.attach(SESSION, B);
        sessions.attach(OTHER_SESSION, A);
    }

    @Test
    void selectionDoesNotRetargetAcceptedExecution() {
        var first = executions.bind(EXECUTION, SESSION, A);
        executions.bind(OTHER_EXECUTION, SESSION, B);
        assertThat(executions.bind(EXECUTION, SESSION, A).getWorkspaceId()).isEqualTo(A);
        assertThat(executions.findByExecutionId(EXECUTION).getBranchId())
                .isEqualTo(first.getBranchId());
        assertThatThrownBy(() -> executions.bind(EXECUTION, SESSION, B))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void multipleSessionsCanBindSameWorkspace() {
        executions.bind(EXECUTION, SESSION, A);
        assertThat(executions.bind(OTHER_EXECUTION, OTHER_SESSION, A).getWorkspaceId())
                .isEqualTo(A);
        assertThat(sessions.listForSession(SESSION)).hasSize(2);
    }

    @Test
    void missingAssociationAndLegacyWorkspaceCannotBeAccepted() {
        assertThatThrownBy(() -> executions.bind(EXECUTION, OTHER_SESSION, B))
                .isInstanceOf(IllegalArgumentException.class);
        jdbc.execute(BREAK);
        assertThatThrownBy(() -> executions.bind(EXECUTION, SESSION, A))
                .isInstanceOf(IllegalStateException.class);
    }
}
