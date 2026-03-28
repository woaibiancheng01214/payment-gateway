import { useState } from 'react';
import { api } from '../api/client';

export default function CreateForm({ onCreated }) {
  const [amount, setAmount] = useState('1000');
  const [currency, setCurrency] = useState('USD');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const result = await api.createPaymentIntent(amount, currency);
      onCreated(result);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card">
      <h2 className="section-title">1. Create Payment Intent</h2>
      <form onSubmit={handleSubmit}>
        <div className="form-row">
          <div className="form-group">
            <label>Amount (minor units)</label>
            <input
              type="number"
              value={amount}
              onChange={e => setAmount(e.target.value)}
              min="1"
              required
            />
          </div>
          <div className="form-group">
            <label>Currency</label>
            <select value={currency} onChange={e => setCurrency(e.target.value)}>
              <option>USD</option>
              <option>EUR</option>
              <option>GBP</option>
              <option>SGD</option>
            </select>
          </div>
        </div>
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={loading} className="btn btn-primary">
          {loading ? 'Creating...' : 'Create'}
        </button>
      </form>
    </div>
  );
}
