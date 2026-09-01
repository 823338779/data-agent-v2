import { WorkflowSnapshot, WorkflowStep } from '../domain/agent';

interface WorkflowRunPanelProps {
  workflow: WorkflowSnapshot;
}

const STEP_LABELS: Record<WorkflowStep, string> = {
  discover_semantic_resources: '检索业务语义',
  build_query_plan: '生成查询计划',
  validate_query_plan: '校验查询计划',
  generate_sql: '生成 SQL',
  validate_sql: '校验 SQL 安全性',
  explain_sql: 'StarRocks 预检',
  execute_query: '查询数据',
  verify_results: '验证查询结果',
  analyze_results: '分析结果',
  finalize_response: '生成最终回答',
};

/** 展示仅存在于当前页面生命周期内的数据分析 Workflow。 */
export function WorkflowRunPanel({ workflow }: WorkflowRunPanelProps) {
  return (
    <section className="workflow-run-panel" aria-label="数据分析过程">
      <header>
        <strong>数据分析过程</strong>
        <span className={`workflow-status ${workflow.status}`}>{workflow.status}</span>
      </header>
      <p className="workflow-current-step" role="status" aria-live="polite">
        {STEP_LABELS[workflow.currentStep]}
      </p>
      {workflow.tasks.length > 0 && (
        <ol>
          {workflow.tasks.map((task) => (
            <li key={task.task.taskId}>
              <div className="workflow-task-heading">
                <span>
                  {task.task.index}/{task.task.total} · {task.task.purpose}
                </span>
                {task.attempt && <small>第 {task.attempt} 次尝试</small>}
              </div>
              {task.message && <p>{task.message}</p>}
              {task.returnedRows !== null && (
                <p>
                  返回 {task.returnedRows} 行{task.truncated ? '（已按上限截断）' : ''}
                </p>
              )}
              {task.sql && (
                <div className="workflow-sql">
                  <span>已验证 SQL</span>
                  <pre>
                    <code>{task.sql}</code>
                  </pre>
                </div>
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
