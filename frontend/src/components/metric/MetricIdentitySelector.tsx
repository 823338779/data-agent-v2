import {
  isSelectableMetricIdentity,
  MetricIdentitySummary,
  metricIdentityStatusLabel,
} from '../../api/metricIdentityClient';

interface MetricIdentitySelectorProps {
  id: string;
  identities: MetricIdentitySummary[];
  value?: string | null;
  label: string;
  allowNone?: boolean;
  disabled?: boolean;
  compact?: boolean;
  onChange: (identityId: string | null) => void;
}

export function MetricIdentitySelector({
  id,
  identities,
  value,
  label,
  allowNone = false,
  disabled = false,
  compact = false,
  onChange,
}: MetricIdentitySelectorProps) {
  const selectable = identities.filter(isSelectableMetricIdentity);
  const current = value ? identities.find((identity) => identity.id === value) : undefined;
  const currentIsUnavailable = current != null && !isSelectableMetricIdentity(current);

  return (
    <label className={`metric-identity-selector ${compact ? 'compact' : ''}`} htmlFor={id}>
      <span>{label}</span>
      <select
        id={id}
        value={value ?? ''}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value || null)}
      >
        {allowNone ? (
          <option value="">暂不选择</option>
        ) : (
          <option value="" disabled>
            请选择指标身份
          </option>
        )}
        {currentIsUnavailable && (
          <option value={current.id} disabled>
            {current.displayName}（{metricIdentityStatusLabel(current.status)}）
          </option>
        )}
        {selectable.map((identity) => (
          <option value={identity.id} key={identity.id}>
            {identity.displayName} · {identity.platformUsername}
          </option>
        ))}
      </select>
      {!compact && selectable.length === 0 && <small>暂无可用身份，可先在身份管理中添加。</small>}
    </label>
  );
}
