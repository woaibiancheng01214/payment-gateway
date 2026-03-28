import { useState } from 'react';
import CreateForm from './components/CreateForm';
import ConfirmForm from './components/ConfirmForm';
import CaptureForm from './components/CaptureForm';
import PaymentIntentDetail from './components/PaymentIntentDetail';

export default function App() {
  const [intentId, setIntentId] = useState(null);
  const [intentStatus, setIntentStatus] = useState(null);

  const handleCreated = (intent) => {
    setIntentId(intent.id);
    setIntentStatus(intent.status);
  };

  const handleStatusChange = (status) => {
    setIntentStatus(status);
  };

  const handleReset = () => {
    setIntentId(null);
    setIntentStatus(null);
  };

  return (
    <div className="app">
      <header className="header">
        <div className="header-inner">
          <div>
            <h1>Payment Orchestrator</h1>
            <p>Auth + Capture Demo — Mock Visa Gateway</p>
          </div>
          {intentId && (
            <button onClick={handleReset} className="btn btn-outline">
              New Payment
            </button>
          )}
        </div>
      </header>

      <main className="main">
        <div className="left-column">
          <CreateForm onCreated={handleCreated} />
          <ConfirmForm
            intentId={intentId}
            currentStatus={intentStatus}
            onConfirmed={(r) => setIntentStatus(r.status)}
          />
          <CaptureForm
            intentId={intentId}
            currentStatus={intentStatus}
            onCaptured={(r) => setIntentStatus(r.status)}
          />
        </div>

        <div className="right-column">
          <div className="card info-card">
            <h3 style={{ marginBottom: 12, fontSize: 14, fontWeight: 600 }}>Gateway Simulation</h3>
            <ul className="info-list">
              <li><span className="dot success-dot" /> 70% Success → authorized</li>
              <li><span className="dot failure-dot" /> 20% Failure → failed</li>
              <li><span className="dot timeout-dot" /> 10% Timeout → retry (max 2×)</li>
            </ul>
            <p style={{ fontSize: 12, color: '#9ca3af', marginTop: 12 }}>
              Webhook fires after 1–2 seconds. Status updates automatically.
            </p>
          </div>

          <PaymentIntentDetail
            intentId={intentId}
            onStatusChange={handleStatusChange}
          />
        </div>
      </main>
    </div>
  );
}
