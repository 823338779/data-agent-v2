import { PendingInteraction } from '../../domain/agentPrototype';

interface Props {
  interaction: PendingInteraction;
  busy: boolean;
  error: string | null;
  onResolve: (optionId: string) => void;
}

export function PendingInteractionCard({ interaction, busy, error, onResolve }: Props) {
  return (
    <section className="pending-interaction-card" aria-label="Agent 需要你的选择">
      <strong>{interaction.prompt}</strong>
      <div className="pending-interaction-options">
        {interaction.options.map((option) => (
          <button
            type="button"
            disabled={busy}
            key={option.id}
            onClick={() => onResolve(option.id)}
          >
            <span>{option.label}</span>
            {option.description && <small>{option.description}</small>}
          </button>
        ))}
      </div>
      {interaction.expiresAt && <small>该选择有时效限制，请及时确认。</small>}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
