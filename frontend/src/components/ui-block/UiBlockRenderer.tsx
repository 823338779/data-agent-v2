import type { ComponentType } from 'react';
import type { MessageUiBlock } from '../../domain/agent';
import { GitChangeCardBlock } from './GitChangeCardBlock';
import { DevelopmentOperationCard } from './DevelopmentOperationCard';

interface UiBlockRendererProps {
  block: MessageUiBlock | undefined;
}

interface RegisteredBlockProps {
  block: MessageUiBlock;
}

const renderers: Record<string, ComponentType<RegisteredBlockProps>> = {
  'git-change-card@1': GitChangeCardRenderer,
  'development-operation@1': DevelopmentOperationRenderer,
};

/** type@schemaVersion 白名单注册表；未知或无效数据只显示安全回退。 */
export function UiBlockRenderer({ block }: UiBlockRendererProps) {
  if (!block) return <div className="ui-block-fallback">内容卡片不可用</div>;
  const Renderer = renderers[`${block.type}@${block.schemaVersion}`];
  if (!Renderer) return <div className="ui-block-fallback">暂不支持此内容卡片</div>;
  return <Renderer block={block} />;
}

/** 操作身份只能来自受控消息投影；模型不能提供 URL、目标或可执行动作。 */
function DevelopmentOperationRenderer({ block }: RegisteredBlockProps) {
  const payload = block.payload;
  if (
    typeof payload !== 'object' ||
    payload === null ||
    Array.isArray(payload) ||
    Object.keys(payload).length !== 2 ||
    !('sessionId' in payload) ||
    !('operationId' in payload) ||
    typeof payload.sessionId !== 'string' ||
    !payload.sessionId.trim() ||
    typeof payload.operationId !== 'string' ||
    !payload.operationId.trim()
  )
    return <div className="ui-block-fallback">开发操作卡片数据无效</div>;
  return (
    <DevelopmentOperationCard
      key={`${payload.sessionId}/${payload.operationId}`}
      sessionId={payload.sessionId}
      operationId={payload.operationId}
    />
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
