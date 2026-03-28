const BASE_URL = 'http://localhost:8080';

async function request(method, path, body = null, headers = {}) {
  const options = {
    method,
    headers: { 'Content-Type': 'application/json', ...headers },
  };
  if (body) options.body = JSON.stringify(body);

  const res = await fetch(`${BASE_URL}${path}`, options);
  const data = await res.json();

  if (!res.ok) {
    throw new Error(data.error || `HTTP ${res.status}`);
  }
  return data;
}

export const api = {
  createPaymentIntent: (amount, currency) =>
    request('POST', '/payment_intents', { amount: Number(amount), currency }),

  confirmPaymentIntent: (id, paymentMethod, idempotencyKey) =>
    request('POST', `/payment_intents/${id}/confirm`, { paymentMethod }, {
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
    }),

  capturePaymentIntent: (id) =>
    request('POST', `/payment_intents/${id}/capture`),

  getPaymentIntent: (id) =>
    request('GET', `/payment_intents/${id}`),

  listPaymentIntents: () =>
    request('GET', '/payment_intents'),
};
