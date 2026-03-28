const STATUS_COLORS = {
  requires_payment_method: { bg: '#f3f4f6', text: '#6b7280', border: '#d1d5db' },
  requires_confirmation: { bg: '#eff6ff', text: '#3b82f6', border: '#bfdbfe' },
  authorized: { bg: '#f0fdf4', text: '#16a34a', border: '#bbf7d0' },
  captured: { bg: '#052e16', text: '#86efac', border: '#15803d' },
  failed: { bg: '#fef2f2', text: '#dc2626', border: '#fecaca' },
  pending: { bg: '#fefce8', text: '#ca8a04', border: '#fde68a' },
  success: { bg: '#f0fdf4', text: '#16a34a', border: '#bbf7d0' },
  failure: { bg: '#fef2f2', text: '#dc2626', border: '#fecaca' },
  timeout: { bg: '#fff7ed', text: '#ea580c', border: '#fed7aa' },
};

export default function StatusBadge({ status }) {
  const colors = STATUS_COLORS[status] || STATUS_COLORS['pending'];
  return (
    <span style={{
      padding: '2px 10px',
      borderRadius: '9999px',
      fontSize: '12px',
      fontWeight: 600,
      border: `1px solid ${colors.border}`,
      background: colors.bg,
      color: colors.text,
      textTransform: 'uppercase',
      letterSpacing: '0.05em',
    }}>
      {status?.replace(/_/g, ' ')}
    </span>
  );
}
