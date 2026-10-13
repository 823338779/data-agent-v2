package com.datagent.agentv2.dbt.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.JsonNode;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 唯一传输契约；原生资源属性和结果值保留JSON，时间原样传递给引擎解释。 */
public final class DbtContracts {
    private DbtContracts() {}

    private static final String IDENTIFIER = "[a-z][a-z0-9_]{0,62}";
    private static final Set<String> ADAPTERS = Set.of("starrocks", "postgres", "duckdb");

    /** 拒绝未知配置字段，防止调用方误以为旧属性仍能影响执行。 */
    public interface StrictRequest {
        @JsonAnySetter
        default void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("请求包含未知字段");
        }
    }

    /** 非敏感连接目标；实例和凭据授权由执行服务核验。 */
    public record ConnectionTarget(
            /** dbt 适配器名称。 */
            String type,
            /** 运行基础设施登记的物理实例身份。 */
            String instanceId,
            /** 网络数据库主机；文件适配器可为空。 */
            String host,
            /** 网络端口；文件适配器可为空。 */
            Integer port,
            /** 文件适配器的受控绝对路径。 */
            String path)
            implements StrictRequest {
        public ConnectionTarget {
            if (type == null
                    || !ADAPTERS.contains(type)
                    || instanceId == null
                    || instanceId.isBlank()
                    || instanceId.length() > 128
                    || host != null && host.length() > 253
                    || port != null && (port < 1 || port > 65535)
                    || path != null && path.length() > 2048)
                throw new IllegalArgumentException("执行连接目标无效");
        }
    }

    /** 动作受理时冻结，重试、恢复和查询均使用相同执行目标。 */
    public record ExecutionSnapshot(
            /** 外部环境稳定身份，与源码分支无关。 */
            UUID environmentId,
            /** 外部环境配置版本，至少为 1。 */
            long configVersion,
            /** 完整非敏感连接目标。 */
            ConnectionTarget connection,
            /** 执行基础设施允许的凭据引用，不保存凭据值。 */
            String credentialRef,
            /** 目标 catalog 或数据库。 */
            String catalog,
            /** 物理写入 schema。 */
            String schema,
            /** dbt 并行线程数，范围 1 至 32。 */
            int threads,
            /** 查询使用的 IANA 业务时区。 */
            String businessTimezone)
            implements StrictRequest {
        public ExecutionSnapshot {
            // 与服务的快照约束一致；配置错误必须在 Git 外部副作用之前暴露。
            if (environmentId == null
                    || configVersion < 1
                    || connection == null
                    || credentialRef == null
                    || credentialRef.isBlank()
                    || credentialRef.length() > 128
                    || catalog == null
                    || !catalog.matches(IDENTIFIER)
                    || schema == null
                    || !schema.matches(IDENTIFIER)
                    || threads < 1
                    || threads > 32
                    || businessTimezone == null) throw new IllegalArgumentException("执行快照不完整或无效");
            ZoneId.of(businessTimezone);
        }
    }

    public enum DeploymentPolicy {
        NONE,
        ON_SUCCESS
    }

    public enum BuildStatus {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        OUTCOME_UNKNOWN
    }

    public enum DeploymentStatus {
        WAITING_FOR_BUILD,
        PENDING,
        CHECKING,
        DEPLOYED,
        STALE,
        FAILED,
        CANCELLED
    }

    public enum QueryMode {
        QUERY,
        EXPLAIN,
        PREVIEW,
        DIMENSION_VALUES
    }

    /** Origin 的协议字段。 */
    public record Origin(
            /** 会话追溯标签。 */
            String sessionId,
            /** 持久操作追溯标签。 */
            String operationId) {}

    /** BuildRequest 的协议字段。 */
    public record BuildRequest(
            /** 受控规范仓库地址。 */
            String repository,
            /** 来源Git短分支名。 */
            String branchName,
            /** 实际已推送完整SHA。 */
            String commitSha,
            /** 受理时固定的完整执行配置。 */
            ExecutionSnapshot execution,
            /** 构建成功后的部署策略。 */
            DeploymentPolicy deploymentPolicy,
            /** 持久受理键，重试不可修改。 */
            String idempotencyKey,
            /** 无业务语义的追溯标签。 */
            Origin origin)
            implements StrictRequest {
        public BuildRequest {
            if (execution == null) throw new IllegalArgumentException("execution 不能为空");
        }
    }

    /** ErrorView 的协议字段。 */
    public record ErrorView(
            /** 稳定错误码。 */
            String code,
            /** 服务脱敏诊断。 */
            String message,
            /** 是否允许同请求重试。 */
            boolean retryable,
            /** 失败阶段。 */
            String phase,
            /** 已受理构建，可为空。 */
            UUID buildId) {}

    /** BuildView 的协议字段。 */
    public record BuildView(
            /** 构建身份。 */
            UUID buildId,
            /** 规范仓库地址。 */
            String repository,
            /** 来源分支。 */
            String branchName,
            /** 请求指定SHA。 */
            String requestedCommitSha,
            /** 首次解析后固定SHA。 */
            String commitSha,
            /** 受理时固定的完整执行配置。 */
            ExecutionSnapshot execution,
            /** 配置版本。 */
            String configVersion,
            /** 工具链版本。 */
            String toolchainVersion,
            /** 构建状态，不表示部署成功。 */
            BuildStatus buildStatus,
            /** 执行阶段。 */
            String phase,
            /** 受理时间。 */
            String createdAt,
            /** 事实更新时间。 */
            String updatedAt,
            /** 终态时间。 */
            String finishedAt,
            /** 已请求取消。 */
            boolean cancelRequested,
            /** 封存目录可读。 */
            boolean catalogAvailable,
            /** 可受理新查询。 */
            boolean queryAvailable,
            /** 不可查询原因。 */
            String queryUnavailableReason,
            /** 稳定错误。 */
            ErrorView error,
            /** 初始部署意图。 */
            DeploymentAttemptView initialDeployment) {}

    /** DeploymentKey 的协议字段。 */
    public record DeploymentRequest(
            /** 已成功构建。 */
            UUID buildId,
            /** 部署目标短分支名。 */
            String branchName,
            /** 受理前目标版本。 */
            long expectedTargetVersion,
            /** 稳定部署受理键。 */
            String idempotencyKey) {}

    public record DeploymentKey(
            /** 规范仓库。 */
            String repository,
            /** 执行环境。 */
            UUID environmentId,
            /** 目标短分支名。 */
            String branchName) {}

    /** DeploymentAttemptView 的协议字段。 */
    public record DeploymentAttemptView(
            /** 规范仓库。 */
            String repository,
            /** 执行环境。 */
            UUID environmentId,
            /** 目标短分支名。 */
            String branchName,
            /** 受理顺序。 */
            long generation,
            /** 意图请求的构建。 */
            UUID buildId,
            /** 独立部署状态。 */
            DeploymentStatus deploymentStatus,
            /** 未部署原因。 */
            String reason,
            /** 意图受理时间。 */
            String createdAt,
            /** 实际切换时间。 */
            String deployedAt) {}

    /** DeploymentTargetView 的协议字段。 */
    public record DeploymentTargetView(
            /** 规范仓库。 */
            String repository,
            /** 执行环境。 */
            UUID environmentId,
            /** 目标分支。 */
            String branchName,
            /** 目标乐观锁版本。 */
            long version,
            /** 最新意图序号。 */
            long desiredGeneration,
            /** 当前有效构建，可为空。 */
            UUID activeBuildId,
            /** 有效源码SHA。 */
            String activeCommitSha,
            /** 最新意图。 */
            DeploymentAttemptView latestAttempt,
            /** 最近观察远端head。 */
            String observedHeadSha,
            /** 远端观察时间。 */
            String headObservedAt,
            /** 远端观察状态，不替代部署结果。 */
            String sourceState) {}

    /** DeploymentFilter 的协议字段。 */
    public record DeploymentFilter(
            /** 必填规范仓库。 */
            String repository,
            /** 必填环境身份。 */
            UUID environmentId,
            /** 可选分支筛选。 */
            String branchName,
            /** 后续游标。 */
            String cursor,
            /** 页大小1至200。 */
            int limit) {}

    /** DeploymentPage 的协议字段。 */
    public record DeploymentPage(
            /** 有界部署历史。 */
            List<DeploymentAttemptView> items,
            /** 后续游标。 */
            String nextCursor) {}

    /** CatalogFilter 的协议字段。 */
    public record CatalogFilter(
            /** 搜索文本。 */
            String q,
            /** 资源类型筛选。 */
            String kind,
            /** 后续游标。 */
            String cursor,
            /** 页大小1至200。 */
            int limit) {}

    /** CatalogPage 的协议字段。 */
    public record CatalogPage(
            /** 封存资源摘要。 */
            List<CatalogResource> items,
            /** 后续游标。 */
            String nextCursor,
            /** 所属构建。 */
            UUID buildId) {}

    /** ResourceSourceView 的协议字段。 */
    public record ResourceSourceView(
            /** 所属构建。 */
            UUID buildId,
            /** 资源身份。 */
            String resourceId,
            /** 工程相对路径。 */
            String path,
            /** 封存源码正文。 */
            String content) {}

    /** ResourceLineageView 的协议字段。 */
    public record ResourceLineageView(
            /** 所属构建。 */
            UUID buildId,
            /** 资源身份。 */
            String resourceId,
            /** 同一构建上下游关系。 */
            List<CatalogRelation> dependencies) {}

    /** CatalogRelation 的协议字段。 */
    public record CatalogRelation(
            /** 上游资源。 */
            String upstreamResourceId,
            /** 下游资源。 */
            String downstreamResourceId,
            /** 关系种类。 */
            String kind) {}

    /** ResourceNativeView 的协议字段。 */
    public record ResourceNativeView(
            /** 所属构建。 */
            UUID buildId,
            /** 资源身份。 */
            String resourceId,
            /** 原生引擎定义。 */
            JsonNode nativeDetails) {}

    /** AvailableDimensionsRequest 的协议字段。 */
    public record AvailableDimensionsRequest(
            /** 指标集合。 */
            List<String> metricResourceIds) {}

    /** 同步查询选项的协议字段，不创建后台任务。 */
    public record AvailableDimensionsView(
            /** 固定构建。 */
            UUID buildId,
            /** 规范化指标集合。 */
            List<String> metricResourceIds,
            /** 当前构建与指标集合的原生合法选项。 */
            List<JsonNode> dimensions) {}

    /** SelectedDimension 的协议字段。 */
    public record SelectedDimension(
            /** 固定构建和指标集的选项。 */
            String optionId,
            /** 可选时间粒度。 */
            String grain) {}

    /** SelectedFilter 的协议字段。 */
    public record SelectedFilter(
            /** 过滤选项。 */
            String optionId,
            /** 允许的操作。 */
            String operator,
            /** 保留JSON类型和精度的值。 */
            JsonNode value) {}

    /** SelectedOrder 的协议字段。 */
    public record SelectedOrder(
            /** 已选择字段。 */
            String fieldId,
            /** ASC或DESC。 */
            String direction) {}

    /** QueryRequest 的协议字段。 */
    public record QueryRequest(
            /** 引擎查询模式。 */
            QueryMode mode,
            /** 指标集合。 */
            List<String> metricResourceIds,
            /** 有序分组。 */
            List<SelectedDimension> groupBy,
            /** 过滤条件。 */
            List<SelectedFilter> filters,
            /** 起点，原样交服务按业务时区解释。 */
            String startTime,
            /** 终点，保留偏移或无偏移语义。 */
            String endTime,
            /** 有序排序。 */
            List<SelectedOrder> orderBy,
            /** 最多返回1至10000行。 */
            int limit,
            /** PREVIEW数据集。 */
            String datasetResourceId,
            /** DIMENSION_VALUES选项。 */
            String dimensionOptionId) {}

    /** CatalogResource 的协议字段。 */
    public record CatalogResource(
            /** 资源身份。 */
            String resourceId,
            /** 原生身份。 */
            String nativeId,
            /** 原生名称。 */
            String name,
            /** 展示名称。 */
            String displayName,
            /** 口径描述。 */
            String description,
            /** 工程内源码位置。 */
            String sourcePath,
            /** 原生能力。 */
            List<String> capabilities,
            /** 原生定义。 */
            JsonNode nativeDetails,
            /** 资源种类。 */
            String kind,
            /** 按资源种类解释的原生属性。 */
            JsonNode attributes) {}

    /** ResourceView 的协议字段。 */
    public record ResourceView(
            /** 资源身份。 */
            String resourceId,
            /** 原生身份。 */
            String nativeId,
            /** 原生名称。 */
            String name,
            /** 展示名称。 */
            String displayName,
            /** 口径描述。 */
            String description,
            /** 工程内源码位置。 */
            String sourcePath,
            /** 原生能力。 */
            List<String> capabilities,
            /** 原生定义。 */
            JsonNode nativeDetails,
            /** 所属构建。 */
            UUID buildId,
            /** 资源种类。 */
            String kind,
            /** 原生属性。 */
            JsonNode attributes) {}

    /** QueryResult 的协议字段。 */
    public record QueryResult(
            /** 固定构建。 */
            UUID buildId,
            /** 查询模式。 */
            QueryMode mode,
            /** 固定源码。 */
            String commitSha,
            /** 构建业务时区。 */
            String businessTimezone,
            /** 引擎归一化时间，不重新解释。 */
            JsonNode normalizedTimeRange,
            /** 引擎粒度边界策略。 */
            String boundaryPolicy,
            /** EXPLAIN完整编译SQL。 */
            String sql,
            /** 列精度元数据。 */
            List<JsonNode> columns,
            /** 完整行，保留decimal字符串及null。 */
            List<List<JsonNode>> rows,
            /** 引擎结果是否截断。 */
            boolean resultTruncated) {}
}
