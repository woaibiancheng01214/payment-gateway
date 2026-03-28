import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import StatusBadge from './StatusBadge';

const TERMINAL_STATUSES = ['captured', 'failed'];
const POLL_INTERVAL_MS = 1500;

export default function PaymentIntentDetail({ intentId, onStatusChange }) {
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState(null);
  const pollingRef = useRef(null);

  const fetchDetail = async () => {
    try {
      const data = await api.getPaymentIntent(intentId);
      setDetail(data);
      onStatusChange?.(data.status);
      if (TERMINAL_STATUSES.includes(data.status)) {
        clearInterval(pollingRef.current);
      }
    } catch (e) {
      setError(e.message);
    }
  };

  useEffect(() => {
    if (!intentId) return;
    fetchDetail();
    pollingRef.current = setInterval(fetchDetail, POLL_INTERVAL_MS);
    return () => clearInterval(pollingRef.current);
  }, [intentId]);

  if (!intentId) return null;
  if (error) return <p style={{ color: '#dc2626' }}>Error: {error}</p>;
  if (!detail) return <p style={{ color: '#6b7280' }}>Loading...</p>;

  return (
    <div className="card">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>Payment Intent</h3>
        <StatusBadge status={detail.status} />
      </div>

      <div className="detail-grid">
        <span className="label">ID</span>
        <span className="mono">{detail.id}</span>
        <span className="label">Amount</span>
        <span>{detail.amount} {detail.currency}</span>
        <span className="label">Created</span>
        <span>{new Date(detail.createdAt).toLocaleTimeString()}</span>
        <span className="label">Updated</span>
        <span>{new Date(detail.updatedAt).toLocaleTimeString()}</span>
      </div>

      {detail.attempts?.length > 0 && (
        <div style={{ marginTop: 20 }}>
          <h4 style={{ fontSize: 13, fontWeight: 600, color: '#6b7280', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.05em' }}>Payment Attempts</h4>
          {detail.attempts.map(attempt => (
            <div key={attempt.id} className="attempt-card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <span style={{ fontWeight: 500, fontSize: 13 }}>{attempt.paymentMethod}</span>
                <StatusBadge status={attempt.status} />
              </div>
              <div className="detail-grid small">
                <span className="label">Attempt ID</span>
                <span className="mono small">{attempt.id}</span>
              </div>

              {attempt.internalAttempts?.length > 0 && (
                <div style={{ marginTop: 10 }}>
                  <div style={{ fontSize: 11, color: '#9ca3af', fontWeight: 600, marginBottom: 6, textTransform: 'uppercase' }}>Internal Attempts (Gateway)</div>
                  {attempt.internalAttempts.map(ia => (
                    <div key={ia.id} className="internal-attempt">
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <span style={{ fontSize: 12 }}>
                          [{ia.type.toUpperCase()}] {ia.provider}
                          {ia.retryCount > 0 && <span style={{ marginLeft: 6, color: '#ea580c', fontSize: 11 }}>retry #{ia.retryCount}</span>}
                        </span>
                        <StatusBadge status={ia.status} />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {!TERMINAL_STATUSES.includes(detail.status) && (
        <p style={{ fontSize: 11, color: '#9ca3af', marginTop: 12, textAlign: 'right' }}>
          ● Polling every {POLL_INTERVAL_MS / 1000}s...
        </p>
      )}
    </div>
  );
}
