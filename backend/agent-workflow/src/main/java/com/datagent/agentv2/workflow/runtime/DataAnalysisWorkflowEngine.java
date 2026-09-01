package com.datagent.agentv2.workflow.runtime;

import com.datagent.agentv2.datasource.config.StarRocksDataSourceProperties;
import com.datagent.agentv2.datasource.query.QueryAccessScope;
import com.datagent.agentv2.datasource.query.QueryFailureCategory;
import com.datagent.agentv2.datasource.query.QueryFailureException;
import com.datagent.agentv2.datasource.query.SqlPolicyException;
import com.datagent.agentv2.datasource.query.StarRocksQueryGateway;
import com.datagent.agentv2.datasource.query.StarRocksSqlPolicyValidator;
import com.datagent.agentv2.datasource.query.TabularQueryResult;
import com.datagent.agentv2.datasource.query.ValidatedSql;
import com.datagent.agentv2.datasource.semantic.SemanticResourceSearchService;
import com.datagent.agentv2.datasource.semantic.SemanticResourceSet;
import com.datagent.agentv2.workflow.config.DataAnalysisWorkflowProperties;
import com.datagent.agentv2.workflow.model.AnalysisResult;
import com.datagent.agentv2.workflow.model.GeneratedSql;
import com.datagent.agentv2.workflow.model.QueryPlan;
import com.datagent.agentv2.workflow.model.QueryPlanPrompt;
import com.datagent.agentv2.workflow.model.QueryTask;
import com.datagent.agentv2.workflow.model.QueryTaskResult;
import com.datagent.agentv2.workflow.model.ResultAnalysisPrompt;
import com.datagent.agentv2.workflow.model.SqlGenerationPrompt;
import com.datagent.agentv2.workflow.observability.DataAnalysisWorkflowMetrics;
import com.datagent.agentv2.workflow.port.WorkflowExecutionControlPort;
import com.datagent.agentv2.workflow.port.WorkflowFinalAnswerPort;
import com.datagent.agentv2.workflow.port.WorkflowModelPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** 固定节点、固定边和有界重试的数据分析有限状态机。 */
@RequiredArgsConstructor
public final class DataAnalysisWorkflowEngine {

    private final WorkflowModelPort model;
    private final SemanticResourceSearchService semanticSearch;
    private final StarRocksSqlPolicyValidator sqlPolicy;
    private final StarRocksQueryGateway queries;
    private final QueryPlanValidator planValidator;
    private final ResultVerifier resultVerifier;
    private final WorkflowRunRegistry registry;
    private final WorkflowFinalAnswerPort finalAnswers;
    private final WorkflowExecutionControlPort executionControl;
    private final DataAnalysisWorkflowProperties properties;
    private final StarRocksDataSourceProperties datasourceProperties;
    private final DataAnalysisWorkflowMetrics metrics;
    private final Clock clock;

    public void execute(WorkflowRunRequest request) {
        assertRequest(request);
        Instant workflowDeadline =
                min(clock.instant().plus(properties.hardTimeout()), request.executionDeadlineAt());
        executionControl.assertRunning(request, workflowDeadline);
        WorkflowSnapshot opened = registry.open(request);
        WorkflowRunContext context =
                new WorkflowRunContext(opened.workflowId(), request, workflowDeadline);
        try {
            event(
                    context,
                    WorkflowEventType.WORKFLOW_STARTED,
                    WorkflowStep.DISCOVER_SEMANTIC_RESOURCES,
                    null,
                    WorkflowEventDetail.message("数据分析 Workflow 已启动"));
            discover(context);
            QueryPlanValidator.ValidatedQueryPlan validatedPlan = buildAndValidatePlan(context);
            executeTasks(context, validatedPlan);
            verify(context);
            analyze(context);
            finalizeResponse(context);
            registry.finish(
                    context.workflowId(),
                    WorkflowStatus.SUCCEEDED,
                    WorkflowEventDetail.message("数据分析完成"));
            metrics.workflowFinished("succeeded");
        } catch (WorkflowCancelledException failure) {
            finishFailure(context, WorkflowStatus.CANCELLED, failure);
            throw failure;
        } catch (WorkflowDeadlineExceededException failure) {
            finishFailure(context, WorkflowStatus.TIMED_OUT, failure);
            throw failure;
        } catch (WorkflowException failure) {
            finishFailure(context, WorkflowStatus.FAILED, failure);
            throw failure;
        } catch (RuntimeException failure) {
            WorkflowException wrapped =
                    new WorkflowException(
                            WorkflowFailureCode.WORKFLOW_INTERNAL_ERROR,
                            "数据分析 Workflow 内部失败",
                            failure);
            finishFailure(context, WorkflowStatus.FAILED, wrapped);
            throw wrapped;
        } finally {
            registry.close(context.workflowId());
        }
    }

    private void discover(WorkflowRunContext context) {
        stepStarted(
                context,
                WorkflowStep.DISCOVER_SEMANTIC_RESOURCES,
                null,
                WorkflowEventDetail.empty());
        SemanticResourceSet resources =
                semanticSearch.search(
                        context.request().question(), properties.maxSemanticResources());
        if (resources.datasets().isEmpty()
                && resources.metrics().isEmpty()
                && resources.fields().isEmpty()) {
            throw new WorkflowException(
                    WorkflowFailureCode.SEMANTIC_RESOURCE_NOT_FOUND, "未找到匹配的业务语义资源");
        }
        context.useSemanticResources(resources);
        List<String> names =
                java.util.stream.Stream.of(
                                resources.datasets().stream().map(dataset -> dataset.name()),
                                resources.metrics().stream().map(metric -> metric.name()),
                                resources.fields().stream().map(field -> field.field().name()))
                        .flatMap(stream -> stream)
                        .filter(name -> name != null && !name.isBlank())
                        .distinct()
                        .toList();
        stepSucceeded(
                context,
                WorkflowStep.DISCOVER_SEMANTIC_RESOURCES,
                null,
                new WorkflowEventDetail(
                        names, null, null, null, null, null, null, "语义资源检索完成", null));
    }

    private QueryPlanValidator.ValidatedQueryPlan buildAndValidatePlan(WorkflowRunContext context) {
        WorkflowValidationException lastFailure = null;
        for (int attempt = 1; attempt <= properties.maxQueryPlanAttempts(); attempt++) {
            stepStarted(
                    context,
                    WorkflowStep.BUILD_QUERY_PLAN,
                    null,
                    WorkflowEventDetail.attempt(
                            attempt, properties.maxQueryPlanAttempts(), "正在生成查询计划"));
            QueryPlan plan =
                    model.buildQueryPlan(
                            new QueryPlanPrompt(
                                    context.request().question(),
                                    context.semanticResources(),
                                    properties.maxQueryTasks()));
            stepSucceeded(
                    context,
                    WorkflowStep.BUILD_QUERY_PLAN,
                    null,
                    WorkflowEventDetail.attempt(
                            attempt, properties.maxQueryPlanAttempts(), "查询计划已生成"));
            stepStarted(
                    context, WorkflowStep.VALIDATE_QUERY_PLAN, null, WorkflowEventDetail.empty());
            try {
                QueryPlanValidator.ValidatedQueryPlan validated =
                        planValidator.validate(
                                plan,
                                context.semanticResources(),
                                properties,
                                datasourceProperties.maxRows());
                context.useQueryPlan(validated.plan());
                stepSucceeded(
                        context,
                        WorkflowStep.VALIDATE_QUERY_PLAN,
                        null,
                        WorkflowEventDetail.message("查询计划校验通过"));
                return validated;
            } catch (WorkflowValidationException failure) {
                lastFailure = failure;
                if (attempt < properties.maxQueryPlanAttempts()) {
                    metrics.retry(WorkflowStep.BUILD_QUERY_PLAN, "validation");
                    event(
                            context,
                            WorkflowEventType.STEP_RETRYING,
                            WorkflowStep.BUILD_QUERY_PLAN,
                            null,
                            WorkflowEventDetail.attempt(
                                    attempt + 1,
                                    properties.maxQueryPlanAttempts(),
                                    "查询计划不合法，正在重新生成"));
                }
            }
        }
        throw new WorkflowException(
                WorkflowFailureCode.QUERY_PLAN_ATTEMPTS_EXHAUSTED, "查询计划在允许次数内仍未通过校验", lastFailure);
    }

    private void executeTasks(
            WorkflowRunContext context, QueryPlanValidator.ValidatedQueryPlan validatedPlan) {
        List<QueryTask> tasks = validatedPlan.plan().tasks();
        for (int index = 0; index < tasks.size(); index++) {
            QueryTask task = tasks.get(index);
            WorkflowTaskRef taskRef =
                    new WorkflowTaskRef(task.taskId(), task.purpose(), index + 1, tasks.size());
            QueryAccessScope scope = validatedPlan.accessScopes().get(task.taskId());
            context.addTaskResult(
                    new QueryTaskResult(task, executeTask(context, task, taskRef, scope)));
        }
    }

    private TabularQueryResult executeTask(
            WorkflowRunContext context,
            QueryTask task,
            WorkflowTaskRef taskRef,
            QueryAccessScope scope) {
        String previousSafeError = null;
        for (int attempt = 1; attempt <= properties.maxSqlAttemptsPerTask(); attempt++) {
            stepStarted(
                    context,
                    WorkflowStep.GENERATE_SQL,
                    taskRef,
                    WorkflowEventDetail.attempt(
                            attempt, properties.maxSqlAttemptsPerTask(), "正在生成 SQL"));
            GeneratedSql generated =
                    model.generateSql(
                            new SqlGenerationPrompt(
                                    task,
                                    context.semanticResources(),
                                    scope.maxRows(),
                                    previousSafeError));
            validateGeneratedSql(task, generated);
            stepSucceeded(
                    context,
                    WorkflowStep.GENERATE_SQL,
                    taskRef,
                    WorkflowEventDetail.attempt(
                            attempt, properties.maxSqlAttemptsPerTask(), "SQL 草案已生成"));
            ValidatedSql validated;
            stepStarted(context, WorkflowStep.VALIDATE_SQL, taskRef, WorkflowEventDetail.empty());
            try {
                validated = sqlPolicy.validate(generated.sql(), scope);
                stepSucceeded(
                        context,
                        WorkflowStep.VALIDATE_SQL,
                        taskRef,
                        WorkflowEventDetail.message("SQL 本地安全校验通过"));
            } catch (SqlPolicyException failure) {
                previousSafeError = failure.getMessage();
                if (attempt < properties.maxSqlAttemptsPerTask()) {
                    metrics.retry(WorkflowStep.GENERATE_SQL, "policy");
                    event(
                            context,
                            WorkflowEventType.STEP_RETRYING,
                            WorkflowStep.GENERATE_SQL,
                            taskRef,
                            WorkflowEventDetail.attempt(
                                    attempt + 1,
                                    properties.maxSqlAttemptsPerTask(),
                                    previousSafeError));
                    continue;
                }
                throw new WorkflowException(
                        WorkflowFailureCode.SQL_ATTEMPTS_EXHAUSTED, "SQL 安全校验重试耗尽");
            }

            stepStarted(context, WorkflowStep.EXPLAIN_SQL, taskRef, WorkflowEventDetail.empty());
            long explainStarted = System.nanoTime();
            try {
                queries.explain(validated);
                // 完整 SQL 只在本地策略和 StarRocks EXPLAIN 均成功后进入公开事件。
                stepSucceeded(
                        context,
                        WorkflowStep.EXPLAIN_SQL,
                        taskRef,
                        WorkflowEventDetail.sql(
                                validated.value(),
                                attempt,
                                properties.maxSqlAttemptsPerTask(),
                                elapsedMillis(explainStarted)));
            } catch (QueryFailureException failure) {
                if (failure.category() == QueryFailureCategory.SYNTAX_OR_SCHEMA
                        && attempt < properties.maxSqlAttemptsPerTask()) {
                    previousSafeError = "SQL 在 StarRocks 预检中存在语法或字段问题";
                    metrics.retry(WorkflowStep.GENERATE_SQL, "explain");
                    event(
                            context,
                            WorkflowEventType.STEP_RETRYING,
                            WorkflowStep.GENERATE_SQL,
                            taskRef,
                            WorkflowEventDetail.attempt(
                                    attempt + 1,
                                    properties.maxSqlAttemptsPerTask(),
                                    previousSafeError));
                    continue;
                }
                throw mapQueryFailure(failure, WorkflowFailureCode.SQL_EXPLAIN_FAILED);
            }

            try {
                return executeValidated(context, taskRef, validated);
            } catch (RegenerateSqlException failure) {
                previousSafeError = failure.getMessage();
                if (attempt >= properties.maxSqlAttemptsPerTask()) {
                    throw new WorkflowException(
                            WorkflowFailureCode.SQL_ATTEMPTS_EXHAUSTED, "SQL 执行期校验重试耗尽");
                }
                metrics.retry(WorkflowStep.GENERATE_SQL, "schema_drift");
                event(
                        context,
                        WorkflowEventType.STEP_RETRYING,
                        WorkflowStep.GENERATE_SQL,
                        taskRef,
                        WorkflowEventDetail.attempt(
                                attempt + 1,
                                properties.maxSqlAttemptsPerTask(),
                                previousSafeError));
            }
        }
        throw new WorkflowException(WorkflowFailureCode.SQL_ATTEMPTS_EXHAUSTED, "SQL 生成次数耗尽");
    }

    private TabularQueryResult executeValidated(
            WorkflowRunContext context, WorkflowTaskRef taskRef, ValidatedSql validated) {
        int transientAttempt = 0;
        while (true) {
            stepStarted(
                    context,
                    WorkflowStep.EXECUTE_QUERY,
                    taskRef,
                    WorkflowEventDetail.attempt(
                            transientAttempt + 1,
                            properties.maxTransientQueryRetries() + 1,
                            "正在查询数据"));
            try {
                TabularQueryResult result = queries.execute(validated);
                stepSucceeded(
                        context,
                        WorkflowStep.EXECUTE_QUERY,
                        taskRef,
                        WorkflowEventDetail.query(
                                result.returnedRowCount(),
                                result.truncated(),
                                result.elapsedMillis()));
                return result;
            } catch (QueryFailureException failure) {
                if (failure.category() == QueryFailureCategory.TRANSIENT_CONNECTION
                        && transientAttempt < properties.maxTransientQueryRetries()) {
                    transientAttempt++;
                    metrics.retry(WorkflowStep.EXECUTE_QUERY, "transient_connection");
                    event(
                            context,
                            WorkflowEventType.STEP_RETRYING,
                            WorkflowStep.EXECUTE_QUERY,
                            taskRef,
                            WorkflowEventDetail.attempt(
                                    transientAttempt + 1,
                                    properties.maxTransientQueryRetries() + 1,
                                    "数据源暂时不可用，使用相同 SQL 重试"));
                    continue;
                }
                if (failure.category() == QueryFailureCategory.SYNTAX_OR_SCHEMA) {
                    throw new RegenerateSqlException("SQL 执行时检测到 Schema 变化");
                }
                throw mapQueryFailure(failure, WorkflowFailureCode.QUERY_ENGINE_UNAVAILABLE);
            }
        }
    }

    private void verify(WorkflowRunContext context) {
        stepStarted(context, WorkflowStep.VERIFY_RESULTS, null, WorkflowEventDetail.empty());
        resultVerifier.verifyResults(context.queryPlan(), context.taskResults());
        stepSucceeded(
                context,
                WorkflowStep.VERIFY_RESULTS,
                null,
                WorkflowEventDetail.message("查询结果验证通过"));
    }

    private void analyze(WorkflowRunContext context) {
        stepStarted(context, WorkflowStep.ANALYZE_RESULTS, null, WorkflowEventDetail.empty());
        long bytes = estimateAnalysisBytes(context.taskResults());
        if (bytes > properties.maxAnalysisInputBytes().toBytes()) {
            throw new WorkflowException(
                    WorkflowFailureCode.ANALYSIS_INPUT_TOO_LARGE, "分析输入超过服务端上限");
        }
        AnalysisResult analysis =
                model.analyzeResults(
                        new ResultAnalysisPrompt(
                                context.queryPlan().analysisGoal(),
                                context.taskResults(),
                                Math.toIntExact(properties.maxAnalysisInputBytes().toBytes())));
        resultVerifier.verifyAnalysis(analysis, context.taskResults());
        context.useAnalysisResult(analysis);
        stepSucceeded(
                context, WorkflowStep.ANALYZE_RESULTS, null, WorkflowEventDetail.message("数据分析完成"));
    }

    private void finalizeResponse(WorkflowRunContext context) {
        stepStarted(context, WorkflowStep.FINALIZE_RESPONSE, null, WorkflowEventDetail.empty());
        executionControl.assertRunning(context.request(), context.hardDeadlineAt());
        finalAnswers.complete(context.request(), context.analysisResult());
        // 最终回答事务返回后才允许发布 Workflow 成功，避免界面先显示成功而回答未落库。
        stepSucceeded(
                context,
                WorkflowStep.FINALIZE_RESPONSE,
                null,
                WorkflowEventDetail.message("最终回答已生成"));
    }

    private void validateGeneratedSql(QueryTask task, GeneratedSql generated) {
        if (generated == null
                || !task.taskId().equals(generated.taskId())
                || generated.sql() == null
                || generated.sql().isBlank()) {
            throw new WorkflowException(
                    WorkflowFailureCode.SQL_GENERATION_INVALID, "模型未返回合法 SQL 文档");
        }
        if (generated.sql().getBytes(StandardCharsets.UTF_8).length
                > properties.maxGeneratedSqlBytes().toBytes()) {
            throw new WorkflowException(
                    WorkflowFailureCode.SQL_GENERATION_INVALID, "模型生成 SQL 超过服务端大小上限");
        }
    }

    private void stepStarted(
            WorkflowRunContext context,
            WorkflowStep step,
            WorkflowTaskRef task,
            WorkflowEventDetail detail) {
        context.moveTo(step);
        event(context, WorkflowEventType.STEP_STARTED, step, task, detail);
    }

    private void stepSucceeded(
            WorkflowRunContext context,
            WorkflowStep step,
            WorkflowTaskRef task,
            WorkflowEventDetail detail) {
        event(context, WorkflowEventType.STEP_SUCCEEDED, step, task, detail);
    }

    private void event(
            WorkflowRunContext context,
            WorkflowEventType type,
            WorkflowStep step,
            WorkflowTaskRef task,
            WorkflowEventDetail detail) {
        executionControl.assertRunning(context.request(), context.hardDeadlineAt());
        registry.transition(context.workflowId(), type, step, WorkflowStatus.RUNNING, task, detail);
    }

    private void finishFailure(
            WorkflowRunContext context, WorkflowStatus status, WorkflowException failure) {
        metrics.failure(failure.code());
        metrics.workflowFinished(status.value());
        try {
            registry.transition(
                    context.workflowId(),
                    WorkflowEventType.STEP_FAILED,
                    context.currentStep(),
                    WorkflowStatus.RUNNING,
                    null,
                    WorkflowEventDetail.failure(failure.code(), failure.getMessage()));
        } catch (RuntimeException ignored) {
            // 终态收敛优先，事件发布失败不能触发第二次业务执行。
        }
        try {
            registry.finish(
                    context.workflowId(),
                    status,
                    WorkflowEventDetail.failure(failure.code(), failure.getMessage()));
        } catch (RuntimeException ignored) {
            registry.close(context.workflowId());
        }
    }

    private static WorkflowException mapQueryFailure(
            QueryFailureException failure, WorkflowFailureCode fallback) {
        WorkflowFailureCode code =
                switch (failure.category()) {
                    case TIMEOUT -> WorkflowFailureCode.QUERY_TIMEOUT;
                    case RESULT_TOO_LARGE -> WorkflowFailureCode.QUERY_RESULT_TOO_LARGE;
                    case TRANSIENT_CONNECTION -> WorkflowFailureCode.QUERY_ENGINE_UNAVAILABLE;
                    case SYNTAX_OR_SCHEMA, FATAL -> fallback;
                };
        return new WorkflowException(code, "StarRocks 查询未完成", failure);
    }

    private static void assertRequest(WorkflowRunRequest request) {
        if (request == null
                || request.question() == null
                || request.question().isBlank()
                || request.executionDeadlineAt() == null) {
            throw new IllegalArgumentException("WorkflowRunRequest 不完整");
        }
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static long estimateAnalysisBytes(List<QueryTaskResult> results) {
        long bytes = 0;
        for (QueryTaskResult result : results) {
            bytes += result.task().taskId().getBytes(StandardCharsets.UTF_8).length;
            for (List<Object> row : result.result().rows()) {
                for (Object value : row) {
                    bytes +=
                            value == null
                                    ? 4
                                    : value.toString().getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        return bytes;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static final class RegenerateSqlException extends RuntimeException {
        private RegenerateSqlException(String message) {
            super(message);
        }
    }
}
