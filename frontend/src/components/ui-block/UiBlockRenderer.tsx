import type { ComponentType } from 'react';
import type { MessageArtifact, MessageUiBlock } from '../../domain/agent';
import { ArtifactCardBlock } from './ArtifactCardBlock';
import { GitChangeCardBlock } from './GitChangeCardBlock';

interface UiBlockRendererProps {
  block: MessageUiBlock | undefined;
  artifacts: MessageArtifact[];
}

interface RegisteredBlockProps {
  block: MessageUiBlock;
  artifacts: MessageArtifact[];
}

const renderers: Record<string, ComponentType<RegisteredBlockProps>> = {
  'artifact-card@1': ArtifactCardRenderer,
  'git-change-card@1': GitChangeCardRenderer,
};

/** type@schemaVersion 白名单注册表；未知或无效数据只显示安全回退。 */
export function UiBlockRenderer({ block, artifacts }: UiBlockRendererProps) {
  if (!block) return <div className="ui-block-fallback">内容卡片不可用</div>;
  const Renderer = renderers[`${block.type}@${block.schemaVersion}`];
  if (!Renderer) return <div className="ui-block-fallback">暂不支持此内容卡片</div>;
  return <Renderer block={block} artifacts={artifacts} />;
}

function ArtifactCardRenderer({ block, artifacts }: RegisteredBlockProps) {
  const payload = block.payload;
  if (
    typeof payload !== 'object' ||
    payload === null ||
    Array.isArray(payload) ||
    Object.keys(payload).length !== 1 ||
    !('artifactId' in payload) ||
    typeof payload.artifactId !== 'string'
  ) {
    return <div className="ui-block-fallback">文件卡片数据无效</div>;
  }
  const artifact = artifacts.find((item) => item.artifactId === payload.artifactId);
  return artifact ? (
    <ArtifactCardBlock artifact={artifact} />
  ) : (
    <div className="ui-block-fallback">文件不可用</div>
  );
}

function GitChangeCardRenderer({ block }: RegisteredBlockProps) {
  const payload = block.payload;
  if (
    typeof payload !== 'object' ||
    payload === null ||
    Array.isArray(payload) ||
    Object.keys(payload).length !== 2 ||
    !('changeId' in payload) ||
    !('sessionId' in payload) ||
    typeof payload.changeId !== 'string' ||
    !payload.changeId.trim() ||
    typeof payload.sessionId !== 'string' ||
    !payload.sessionId.trim()
  ) {
    return <div className="ui-block-fallback">Git 卡片数据无效</div>;
  }
  return (
    <GitChangeCardBlock
      key={`${payload.sessionId}/${payload.changeId}`}
      sessionId={payload.sessionId}
      changeId={payload.changeId}
    />
  );
}
