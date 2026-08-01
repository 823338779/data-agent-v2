import { useEffect, useMemo, useState } from 'react';
import { ArtifactPreview, createArtifactClient } from '../../api/artifactClient';

export function MetricResultCard({ artifactId }: { artifactId: string }) {
  const client = useMemo(() => createArtifactClient(), []);
  const [preview, setPreview] = useState<ArtifactPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState<'csv' | 'json' | null>(null);

  useEffect(() => {
    let active = true;
    setError(null);
    void client.preview(artifactId).then(
      (value) => active && setPreview(value),
      () => active && setError('当前身份无法读取该结果，或结果已过期。'),
    );
    return () => {
      active = false;
    };
  }, [artifactId, client]);

  async function download(format: 'csv' | 'json') {
    setDownloading(format);
    setError(null);
    try {
      const link = await client.createDownload(artifactId, format);
      window.location.assign(link.url);
    } catch {
      setError('下载链接创建失败，请确认当前指标身份仍有访问权限。');
    } finally {
      setDownloading(null);
    }
  }

  if (error && !preview)
    return (
      <div className="metric-result-card error" role="alert">
        {error}
      </div>
    );
  if (!preview)
    return (
      <div className="metric-result-card" role="status">
        正在加载受治理结果…
      </div>
    );

  return (
    <section className="metric-result-card" id={`artifact-${artifactId}`}>
      <header>
        <div>
          <strong>指标查询结果</strong>
          <small>
            {preview.artifact.rowCount} 行 · Query {preview.artifact.queryId}
          </small>
        </div>
        <div className="artifact-actions">
          <button
            type="button"
            disabled={downloading !== null}
            onClick={() => void download('csv')}
          >
            CSV
          </button>
          <button
            type="button"
            disabled={downloading !== null}
            onClick={() => void download('json')}
          >
            JSON
          </button>
        </div>
      </header>
      <div className="metric-result-table-wrap">
        <table>
          <thead>
            <tr>
              {preview.columns.map((column) => (
                <th key={column.name}>{column.displayName ?? column.name}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {preview.rows.map((row, rowIndex) => (
              <tr key={rowIndex}>
                {row.map((value, columnIndex) => (
                  <td key={columnIndex}>{value == null ? '—' : String(value)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {(preview.artifact.sourceTruncated || preview.previewTruncated) && (
        <p className="artifact-warning">
          {preview.artifact.sourceTruncated ? '平台结果已截断。' : ''}
          {preview.previewTruncated ? ' 页面仅展示前 200 行。' : ''}
        </p>
      )}
      <details>
        <summary>口径与证据</summary>
        <dl>
          <dt>Catalog</dt>
          <dd>{preview.evidence.catalogVersion ?? '—'}</dd>
          <dt>定义版本</dt>
          <dd>{JSON.stringify(preview.evidence.definitionVersions)}</dd>
          <dt>关系路径</dt>
          <dd>{preview.evidence.relationshipIds.join(', ') || '—'}</dd>
          <dt>查询范围</dt>
          <dd>{JSON.stringify(preview.evidence.appliedScope)}</dd>
          <dt>新鲜度</dt>
          <dd>{preview.evidence.freshnessWarnings.join('；') || '无警告'}</dd>
        </dl>
      </details>
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
