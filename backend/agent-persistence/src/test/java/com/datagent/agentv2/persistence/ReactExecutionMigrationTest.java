package com.datagent.agentv2.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class ReactExecutionMigrationTest {

    private static final String TENANT_ID = "tenant_react";
    private static final String USER_ID = "user_react";
    private static final String SESSION_ID = "session_react";
    private static final String USER_MESSAGE_ID = "message_user";
    private static final String RUN_ID = "run_react";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @BeforeAll
    static void migrateAndCreateRun() throws SQLException {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // 构造完整租户、用户、会话和 Run 边界，供复合外键及终态约束测试复用。
        execute(
                """
                insert into tenant(tenant_id, name) values ('tenant_react', 'React');
                insert into user_account(user_id, tenant_id, username, password_hash)
                    values ('user_react', 'tenant_react', 'react', 'hash');
                insert into agent_session(session_id, tenant_id, title, session_type, created_by)
                    values ('session_react', 'tenant_react', 'React', 'INTERACTIVE', 'user_react');
                insert into agent_message(
                    message_id, tenant_id, session_id, role, content, sequence, author_user_id)
                    values ('message_user', 'tenant_react', 'session_react', 'user', 'hello', 1,
                            'user_react');
                insert into agent_run(
                    run_id, tenant_id, session_id, triggering_message_id, status, phase,
                    execution_attempt, owner_user_id, request_id, queued_at)
                    values ('run_react', 'tenant_react', 'session_react', 'message_user', 'queued',
                            'QUEUED', 0, 'user_react', 'request_react', current_timestamp);
                """);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void messageSupportsVersionedToolCallsAndVisibilityChecks() throws SQLException {
        execute(
                """
                insert into agent_message(
                    message_id, tenant_id, session_id, run_id, role, content, sequence,
                    tool_calls, visibility)
                values (
                    'message_assistant', 'tenant_react', 'session_react', 'run_react',
                    'assistant', '', 10,
                    '{"version":1,"calls":[{"id":"provider-1","name":"test_tool","arguments":{"x":1}}]}',
                    'internal');
                """);

        assertThat(
                        row(
                                """
                                select role, visibility, jsonb_typeof(tool_calls),
                                       tool_calls #>> '{calls,0,id}'
                                  from agent_message where message_id = 'message_assistant'
                                """))
                .containsExactly("assistant", "internal", "object", "provider-1");
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        update agent_message set visibility = 'private'
                                         where message_id = 'message_assistant'
                                        """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void modelTurnChecksStateAndKeepsOnlyReasoningAsLargeText() throws SQLException {
        assertThat(columnNames("agent_model_turn"))
                .contains("reasoning")
                .doesNotContain("prompt", "answer", "tool_calls", "tool_result");
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                insert into agent_model_turn(
                    model_turn_id, tenant_id, run_id, model_turn_sequence, execution_attempt,
                    input_up_to_message_sequence, status)
                values ('turn_invalid', 'tenant_react', 'run_react', 1, 1, 1, 'unknown')
                                        """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void toolExecutionUsesTurnScopedProviderIdAndRequiresOneTerminalObservation()
            throws SQLException {
        execute(turnInsert(1));
        execute(turnInsert(2));
        execute(assistantAndPreparedExecution("assistant_turn_1", "execution_1", 1, 2));
        execute(assistantAndPreparedExecution("assistant_turn_2", "execution_2", 2, 3));

        // provider tool_call_id 可在不同 Model Turn 复用，但同一 Turn 内必须唯一。
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        insert into agent_tool_execution(
                                            tenant_id, tool_execution_id, run_id,
                                            model_turn_sequence, assistant_message_id, tool_call_id,
                                            tool_name, status)
                                        values ('tenant_react', 'execution_duplicate', 'run_react', 1,
                                                'assistant_turn_1', 'provider-shared', 'test_tool',
                                                'prepared')
                                        """))
                .isInstanceOf(SQLException.class);

        execute(
                """
                insert into agent_message(
                    message_id, tenant_id, session_id, run_id, role, content, sequence,
                    tool_execution_id, tool_call_id, tool_name, visibility)
                values ('observation_1', 'tenant_react', 'session_react', 'run_react', 'tool',
                        '{"status":"succeeded","content":{"value":1}}', 4, 'execution_1',
                        'provider-shared', 'test_tool', 'internal');
                update agent_tool_execution
                   set status = 'succeeded', observation_message_id = 'observation_1',
                       completed_at = current_timestamp
                 where tenant_id = 'tenant_react' and tool_execution_id = 'execution_1';
                """);
        assertThat(
                        row(
                                "select status, observation_message_id from agent_tool_execution where tool_execution_id = 'execution_1'"))
                .containsExactly("succeeded", "observation_1");
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        update agent_tool_execution
                                           set status = 'failed', observation_message_id = null
                                         where tool_execution_id = 'execution_2'
                                        """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void compositeForeignKeysAndRecoveryIndexesExist() throws SQLException {
        assertThat(constraintDefinition("fk_agent_tool_execution_turn"))
                .contains("tenant_id", "run_id", "model_turn_sequence");
        assertThat(indexDefinition("ix_agent_model_turn_recovery"))
                .contains("tenant_id", "run_id", "status");
        assertThat(indexDefinition("ix_agent_tool_execution_recovery"))
                .contains("tenant_id", "run_id", "status");
        assertThat(indexDefinition("uq_agent_tool_execution_turn_call"))
                .contains("unique", "tenant_id", "run_id", "model_turn_sequence", "tool_call_id");
    }

    private static String turnInsert(long sequence) {
        return """
                insert into agent_model_turn(
                    model_turn_id, tenant_id, run_id, model_turn_sequence, execution_attempt,
                    input_up_to_message_sequence, status, completed_at)
                values ('turn_%d', 'tenant_react', 'run_react', %d, 1, 1, 'completed', current_timestamp)
                """
                .formatted(sequence, sequence);
    }

    private static String assistantAndPreparedExecution(
            String messageId, String executionId, long turnSequence, long messageSequence) {
        return """
                insert into agent_message(
                    message_id, tenant_id, session_id, run_id, role, content, sequence,
                    tool_calls, visibility)
                values ('%s', 'tenant_react', 'session_react', 'run_react', 'assistant', '', %d,
                        '{"version":1,"calls":[{"id":"provider-shared","name":"test_tool","arguments":{}}]}',
                        'internal');
                insert into agent_tool_execution(
                    tenant_id, tool_execution_id, run_id, model_turn_sequence,
                    assistant_message_id, tool_call_id, tool_name, status)
                values ('tenant_react', '%s', 'run_react', %d, '%s', 'provider-shared',
                        'test_tool', 'prepared');
                """
                .formatted(messageId, messageSequence, executionId, turnSequence, messageId);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> row(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            List<String> values = new ArrayList<>();
            for (int index = 1; index <= resultSet.getMetaData().getColumnCount(); index++) {
                values.add(resultSet.getString(index));
            }
            return values;
        }
    }

    private static List<String> columnNames(String table) throws SQLException {
        return query(
                """
                select lower(column_name) from information_schema.columns
                 where table_schema = 'public' and table_name = ? order by ordinal_position
                """,
                table);
    }

    private static String indexDefinition(String index) throws SQLException {
        return query(
                        """
                        select lower(indexdef) from pg_indexes
                         where schemaname = 'public' and indexname = ?
                        """,
                        index)
                .getFirst();
    }

    private static String constraintDefinition(String constraint) throws SQLException {
        return query(
                        """
                        select lower(pg_get_constraintdef(c.oid)) from pg_constraint c
                         where c.conname = ?
                        """,
                        constraint)
                .getFirst();
    }

    private static List<String> query(String sql, String argument) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return values;
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
