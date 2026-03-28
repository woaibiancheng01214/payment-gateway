import { useState } from 'react';
import { api } from '../api/client';

const PAYMENT_METHODS = ['card_4242', 'card_5555', 'card_3782', 'card_6011'];

export default function ConfirmForm({ intentId, currentStatus, onConfirmed }) {
  const [paymentMethod, setPaymentMethod] = useState('card_4242');
  const [useIdempotency, setUseIdempotency] = useState(true);
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const canConfirm = currentStatus === 'requires_confirmation';

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const key = useIdempotency ? idempotencyKey : null;
      const result = await api.confirmPaymentIntent(intentId, paymentMethod, key);
      onConfirmed(result);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card">
      <h2 className="section-title">2. Confirm Payment Intent</h2>
      {!intentId ? (
        <p className="hint">Create a payment intent first.</p>
      ) : (
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Intent ID</label>
            <input type="text" value={intentId} readOnly className="readonly" />
          </div>
          <div className="form-group">
            <label>Payment Method</label>
            <select value={paymentMethod} onChange={e => setPaymentMethod(e.target.value)}>
              {PAYMENT_METHODS.map(m => <option key={m}>{m}</option>)}
            </select>
          </div>
          <div className="form-group checkbox-group">
            <label>
              <input
                type="checkbox"
                checked={useIdempotency}
                onChange={e => setUseIdempotency(e.target.checked)}
              />
              Use Idempotency Key
            </label>
            {useIdempotency && (
              <span className="mono small idempotency-key">{idempotencyKey}</span>
            )}
          </div>
          {error && <p className="error">{error}</p>}
          <button
            type="submit"
            disabled={loading || !canConfirm}
            className={`btn ${canConfirm ? 'btn-primary' : 'btn-disabled'}`}
          >
            {loading ? 'Confirming...' : !canConfirm ? `Status: ${currentStatus}` : 'Confirm'}
          </button>
        </form>
      )}
    </div>
  );
}
