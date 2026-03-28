import { useState } from 'react';
import { api } from '../api/client';

export default function CaptureForm({ intentId, currentStatus, onCaptured }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const canCapture = currentStatus === 'authorized';

  const handleCapture = async () => {
    setError(null);
    setLoading(true);
    try {
      const result = await api.capturePaymentIntent(intentId);
      onCaptured(result);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card">
      <h2 className="section-title">3. Capture Payment Intent</h2>
      {!intentId ? (
        <p className="hint">Create a payment intent first.</p>
      ) : (
        <div>
          <div className="form-group">
            <label>Intent ID</label>
            <input type="text" value={intentId} readOnly className="readonly" />
          </div>
          <div className="form-group">
            <label>Current Status</label>
            <div style={{ paddingTop: 4 }}>
              {!canCapture ? (
                <p className="hint" style={{ margin: 0 }}>
                  {currentStatus === 'captured'
                    ? 'Already captured.'
                    : currentStatus === 'failed'
                    ? 'Payment failed — cannot capture.'
                    : 'Waiting for authorization...'}
                </p>
              ) : (
                <p className="hint success" style={{ margin: 0 }}>Ready to capture!</p>
              )}
            </div>
          </div>
          {error && <p className="error">{error}</p>}
          <button
            onClick={handleCapture}
            disabled={loading || !canCapture}
            className={`btn ${canCapture ? 'btn-success' : 'btn-disabled'}`}
          >
            {loading ? 'Capturing...' : 'Capture'}
          </button>
        </div>
      )}
    </div>
  );
}
