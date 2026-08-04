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
import org.flywaydb.core.api.MigrationVersion;
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
    private static final String EXECUTION_ID = "execution_react";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @BeforeAll
    static void migrateAndCreateExecution() throws SQLException {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("12"))
                .load()
                .migrate();

        // 构造完整租户、用户、会话和 Agent 执行边界，供复合外键及终态约束测试复用。
        execute(
                """
                insert into tenant(tenant_id, name) values ('tenant_react', 'React');
                insert into user_account(user_id, tenant_id, username, password_hash)
                    values ('user_react', 'tenant_react', 'react', 'hash');
                insert into conversation_session(conversation_session_id, tenant_id, title, session_type, created_by_user_id)
                    values ('session_react', 'tenant_react', 'React', 'INTERACTIVE', 'user_react');
                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id, role, content, conversation_message_sequence, author_user_id)
                    values ('message_user', 'tenant_react', 'session_react', 'user', 'hello', 1,
                            'user_react');
                insert into agent_execution(
                    execution_id, tenant_id, conversation_session_id, triggering_conversation_message_id, status, phase,
                    execution_attempt_number, owner_user_id, request_id, queued_at)
                    values ('execution_react', 'tenant_react', 'session_react', 'message_user', 'queued',
                            'QUEUED', 0, 'user_react', 'request_react', current_timestamp);
                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id, execution_id,
                    role, content, conversation_message_sequence, visibility)
                    values ('assistant_existing', 'tenant_react', 'session_react',
                            'execution_react', 'assistant', 'existing', 9, 'user');
                update conversation_session set conversation_message_sequence = 9
                 where conversation_session_id = 'session_react';
                """);

        // 构造 V12 的三种历史形态：完整工具链、仅 internal assistant、仅 user 消息。
        execute(
                """
                insert into conversation_session(
                    conversation_session_id, tenant_id, title, session_type,
                    created_by_user_id, conversation_message_sequence)
                values ('session_migration_a', 'tenant_react', 'Migration A', 'INTERACTIVE',
                            'user_react', 4),
                       ('session_migration_b', 'tenant_react', 'Migration B', 'INTERACTIVE',
                            'user_react', 2),
                       ('session_migration_c', 'tenant_react', 'Migration C', 'INTERACTIVE',
                            'user_react', 1);

                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id,
                    role, content, conversation_message_sequence, author_user_id)
                values ('message_migration_a', 'tenant_react', 'session_migration_a',
                            'user', 'question a', 1, 'user_react'),
                       ('message_migration_b', 'tenant_react', 'session_migration_b',
                            'user', 'question b', 1, 'user_react'),
                       ('message_migration_c', 'tenant_react', 'session_migration_c',
                            'user', 'question c', 1, 'user_react');

                insert into agent_execution(
                    execution_id, tenant_id, conversation_session_id,
                    triggering_conversation_message_id, status, phase,
                    execution_attempt_number, owner_user_id, request_id, queued_at)
                values ('execution_migration_a', 'tenant_react', 'session_migration_a',
                            'message_migration_a', 'done', 'COMPLETED', 1,
                            'user_react', 'request_migration_a', current_timestamp),
                       ('execution_migration_b', 'tenant_react', 'session_migration_b',
                            'message_migration_b', 'waiting_for_user', 'ANSWERING', 1,
                            'user_react', 'request_migration_b', current_timestamp),
                       ('execution_migration_c', 'tenant_react', 'session_migration_c',
                            'message_migration_c', 'waiting_confirmation', 'ANSWERING', 1,
                            'user_react', 'request_migration_c', current_timestamp);

                update conversation_message
                   set execution_id = case conversation_message_id
                       when 'message_migration_a' then 'execution_migration_a'
                       when 'message_migration_b' then 'execution_migration_b'
                       else 'execution_migration_c'
                   end
                 where conversation_message_id in (
                     'message_migration_a', 'message_migration_b', 'message_migration_c');

                insert into model_invocation(
                    model_invocation_id, tenant_id, execution_id, model_invocation_sequence,
                    execution_attempt_number, input_up_to_conversation_message_sequence,
                    status, completed_at)
                values ('turn_migration_a', 'tenant_react', 'execution_migration_a', 1,
                        1, 1, 'completed', current_timestamp);

                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id, execution_id,
                    role, content, conversation_message_sequence, tool_calls, visibility)
                values ('assistant_migration_a', 'tenant_react', 'session_migration_a',
                            'execution_migration_a', 'assistant', '', 2,
                            '{"version":1,"calls":[{"id":"call_migration_a","name":"test_tool","arguments":{"value":1}}]}',
                            'internal'),
                       ('assistant_migration_b', 'tenant_react', 'session_migration_b',
                            'execution_migration_b', 'assistant', '', 2,
                            '{"version":1,"calls":[]}', 'internal');

                insert into tool_execution(
                    tenant_id, tool_execution_id, execution_id, model_invocation_sequence,
                    execution_attempt_number, assistant_conversation_message_id, tool_call_id,
                    tool_name, status, execution_count, started_at)
                values ('tenant_react', 'tool_migration_a', 'execution_migration_a', 1, 1,
                        'assistant_migration_a', 'call_migration_a', 'test_tool',
                        'executing', 1, current_timestamp - interval '1 second');

                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id, execution_id,
                    role, content, conversation_message_sequence, tool_execution_id,
                    tool_call_id, tool_name, visibility)
                values ('observation_migration_a', 'tenant_react', 'session_migration_a',
                        'execution_migration_a', 'tool',
                        '{"status":"succeeded","content":{"value":1}}', 3,
                        'tool_migration_a', 'call_migration_a', 'test_tool', 'internal');

                update tool_execution
                   set status = 'succeeded',
                       observation_conversation_message_id = 'observation_migration_a',
                       completed_at = current_timestamp
                 where tool_execution_id = 'tool_migration_a';

                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id, execution_id,
                    role, content, conversation_message_sequence, visibility)
                values ('answer_migration_a', 'tenant_react', 'session_migration_a',
                        'execution_migration_a', 'assistant', 'answer a', 4, 'user');
                """);

        // 历史夹具落库后再执行 V13，确保测试实际覆盖 expand/backfill 路径。
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void messageSupportsVersionedToolCallsAndVisibilityChecks() throws SQLException {
        execute(
                """
                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id, execution_id, role, content, conversation_message_sequence,
                    tool_calls, visibility)
                values (
                    'message_assistant', 'tenant_react', 'session_react', 'execution_react',
                    'assistant', '', 10,
                    '{"version":1,"calls":[{"id":"provider-1","name":"test_tool","arguments":{"x":1}}]}',
                    'internal');
                """);

        assertThat(
                        row(
                                """
                                select role, visibility, jsonb_typeof(tool_calls),
                                       tool_calls #>> '{calls,0,id}'
                                  from conversation_message where conversation_message_id = 'message_assistant'
                                """))
                .containsExactly("assistant", "internal", "object", "provider-1");
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        update conversation_message set visibility = 'private'
                                         where conversation_message_id = 'message_assistant'
                                        """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void modelInvocationChecksStateWithoutPersistingModelPayloads() throws SQLException {
        assertThat(columnNames("model_invocation"))
                .doesNotContain(
                        "reasoning",
                        "reasoning_expires_at",
                        "prompt",
                        "answer",
                        "tool_calls",
                        "tool_result");
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                insert into model_invocation(
                    model_invocation_id, tenant_id, execution_id, model_invocation_sequence, execution_attempt_number,
                    input_up_to_conversation_message_sequence, status)
                values ('turn_invalid', 'tenant_react', 'execution_react', 1, 1, 1, 'unknown')
                                        """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void toolExecutionUsesTurnScopedProviderIdAndRequiresOneTerminalObservation()
            throws SQLException {
        execute(modelInvocationInsert(1));
        execute(modelInvocationInsert(2));
        execute(assistantAndPreparedExecution("assistant_turn_1", "execution_1", 1, 2));
        execute(assistantAndPreparedExecution("assistant_turn_2", "execution_2", 2, 3));

        // provider tool_call_id 可在不同 Model Turn 复用，但同一 Turn 内必须唯一。
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        insert into tool_execution(
                                            tenant_id, tool_execution_id, execution_id,
                                            model_invocation_sequence, assistant_conversation_message_id, tool_call_id,
                                            tool_name, status)
                                        values ('tenant_react', 'execution_duplicate', 'execution_react', 1,
                                                'assistant_turn_1', 'provider-shared', 'test_tool',
                                                'prepared')
                                        """))
                .isInstanceOf(SQLException.class);

        execute(
                """
                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id, execution_id, role, content, conversation_message_sequence,
                    tool_execution_id, tool_call_id, tool_name, visibility)
                values ('observation_1', 'tenant_react', 'session_react', 'execution_react', 'tool',
                        '{"status":"succeeded","content":{"value":1}}', 4, 'execution_1',
                        'provider-shared', 'test_tool', 'internal');
                update tool_execution
                   set status = 'succeeded', observation_conversation_message_id = 'observation_1',
                       completed_at = current_timestamp
                 where tenant_id = 'tenant_react' and tool_execution_id = 'execution_1';
                """);
        assertThat(
                        row(
                                "select status, observation_conversation_message_id from tool_execution where tool_execution_id = 'execution_1'"))
                .containsExactly("succeeded", "observation_1");
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        update tool_execution
                                           set status = 'failed', observation_conversation_message_id = null
                                         where tool_execution_id = 'execution_2'
                                        """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void compositeForeignKeysAndRecoveryIndexesExist() throws SQLException {
        assertThat(constraintDefinition("fk_tool_execution_turn"))
                .contains("tenant_id", "execution_id", "model_invocation_sequence");
        assertThat(indexDefinition("ix_model_invocation_recovery"))
                .contains("tenant_id", "execution_id", "status");
        assertThat(indexDefinition("ix_tool_execution_recovery"))
                .contains("tenant_id", "execution_id", "status");
        assertThat(indexDefinition("uq_tool_execution_turn_call"))
                .contains(
                        "unique",
                        "tenant_id",
                        "execution_id",
                        "model_invocation_sequence",
                        "tool_call_id");
    }

    @Test
    void migrationChoosesOrCreatesExactlyOneAssistantCenterForEveryHistoricalExecution()
            throws SQLException {
        // 已完成执行绑定公开回答，并把工具节点聚合到同一条中心消息。
        assertThat(
                        row(
                                """
                                select conversation_message_id, content,
                                       jsonb_array_length(thinking_process),
                                       thinking_process #>> '{0,type}'
                                  from conversation_message
                                 where execution_id = 'execution_migration_a'
                                   and role = 'assistant' and visibility = 'user'
                                 order by conversation_message_sequence desc
                                 limit 1
                                """))
                .containsExactly("answer_migration_a", "answer a", "1", "TOOL");

        // 活动执行复用既有 internal assistant；没有 assistant 的执行创建唯一空占位行。
        assertThat(
                        row(
                                """
                                select conversation_message_id, content, visibility,
                                       jsonb_array_length(thinking_process)
                                  from conversation_message
                                 where execution_id = 'execution_migration_b'
                                   and role = 'assistant' and visibility = 'user'
                                """))
                .containsExactly("assistant_migration_b", "", "user", "0");
        assertThat(
                        row(
                                """
                                select count(*), min(content),
                                       min(jsonb_array_length(thinking_process))
                                  from conversation_message
                                 where execution_id = 'execution_migration_c'
                                   and role = 'assistant' and visibility = 'user'
                                """))
                .containsExactly("1", "", "0");
        assertThat(
                        row(
                                """
                                select count(*) filter (where thinking_process = '[]'::jsonb)
                                  from conversation_message
                                 where conversation_message_id in (
                                     'message_migration_a', 'message_migration_b',
                                     'message_migration_c')
                                """))
                .containsExactly("3");
    }

    private static String modelInvocationInsert(long sequence) {
        return """
                insert into model_invocation(
                    model_invocation_id, tenant_id, execution_id, model_invocation_sequence, execution_attempt_number,
                    input_up_to_conversation_message_sequence, status, completed_at)
                values ('turn_%d', 'tenant_react', 'execution_react', %d, 1, 1, 'completed', current_timestamp)
                """
                .formatted(sequence, sequence);
    }

    private static String assistantAndPreparedExecution(
            String conversationMessageId,
            String executionId,
            long turnSequence,
            long conversationMessageSequence) {
        return """
                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id, execution_id, role, content, conversation_message_sequence,
                    tool_calls, visibility)
                values ('%s', 'tenant_react', 'session_react', 'execution_react', 'assistant', '', %d,
                        '{"version":1,"calls":[{"id":"provider-shared","name":"test_tool","arguments":{}}]}',
                        'internal');
                insert into tool_execution(
                    tenant_id, tool_execution_id, execution_id, model_invocation_sequence,
                    assistant_conversation_message_id, tool_call_id, tool_name, status)
                values ('tenant_react', '%s', 'execution_react', %d, '%s', 'provider-shared',
                        'test_tool', 'prepared');
                """
                .formatted(
                        conversationMessageId,
                        conversationMessageSequence,
                        executionId,
                        turnSequence,
                        conversationMessageId);
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
