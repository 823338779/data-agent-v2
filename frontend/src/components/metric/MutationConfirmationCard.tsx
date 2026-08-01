import { ShieldCheck } from 'lucide-react';
import { ApprovalRequest } from '../../domain/agentPrototype';

interface Props {
  approval: ApprovalRequest;
  busy: boolean;
  onDecision: (decision: 'approved' | 'rejected') => void;
}

export function MutationConfirmationCard({ approval, busy, onDecision }: Props) {
  return (
    <section className="composer-approval" aria-label="会话内确认框">
      <div>
        <ShieldCheck size={17} />
        <span>
          <strong>{approval.title}</strong>
          <small>{approval.description}</small>
          <small>批准只执行已冻结的工具参数；任何参数变化都需要重新确认。</small>
        </span>
      </div>
      <div className="approval-actions">
        <button type="button" disabled={busy} onClick={() => onDecision('rejected')}>
          拒绝
        </button>
        <button type="button" disabled={busy} onClick={() => onDecision('approved')}>
          批准并执行
        </button>
      </div>
    </section>
  );
}
