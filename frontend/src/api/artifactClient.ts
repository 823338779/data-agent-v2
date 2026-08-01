export interface ArtifactMetadata {
  id: string;
  fileName: string;
  mediaType: string;
  queryId: string;
  rowCount: number;
  columnCount: number;
  sourceTruncated: boolean;
  createdAt: string;
}

export interface SafeEvidenceSummary {
  queryId: string;
  catalogVersion: string | null;
  definitionVersions: Record<string, number>;
  relationshipIds: string[];
  appliedScope: Record<string, unknown>;
  freshnessWarnings: string[];
}

export interface ArtifactPreview {
  artifact: ArtifactMetadata;
  columns: Array<{
    name: string;
    displayName?: string | null;
    logicalType?: string | null;
    unit?: string | null;
  }>;
  rows: unknown[][];
  previewTruncated: boolean;
  evidence: SafeEvidenceSummary;
}

export interface ArtifactClient {
  metadata(id: string): Promise<ArtifactMetadata>;
  preview(id: string, limit?: number): Promise<ArtifactPreview>;
  createDownload(id: string, format: 'csv' | 'json'): Promise<{ url: string; expiresAt: string }>;
}

async function getJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init });
  if (!response.ok) throw new Error(`Artifact request failed: ${response.status}`);
  return response.json() as Promise<T>;
}

export function createArtifactClient(): ArtifactClient {
  return {
    metadata: (id) => getJson(`/api/v1/artifacts/${id}`),
    preview: (id, limit = 200) =>
      getJson(`/api/v1/artifacts/${id}/preview?limit=${Math.min(200, Math.max(1, limit))}`),
    createDownload: (id, format) =>
      getJson(`/api/v1/artifacts/${id}:create-download?format=${format}`, { method: 'POST' }),
  };
}
