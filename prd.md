Technical PRD: Payment Orchestrator MVP (Auth + Capture)
1. Project Overview

Objective: Build a simplified Payment Orchestrator that models PaymentIntents, PaymentAttempts, InternalAttempts, with idempotency, async gateway simulation, retry, and capture flow. Include a minimal frontend for demo purposes. No real card network integration; all gateway interactions are mocked.

Scope:

Core backend API for PaymentIntent lifecycle
Mock gateway (simulate Visa)
Idempotency handling
Async webhook simulation
Retry for timeouts
Minimal frontend to demo flow

Out of scope:

Real card network integration
Wallet / LPM payments
3DS flows
Incremental auth
Multi-gateway routing
2. Entities / Data Model
1. PaymentIntent (Client-facing)
Field	Type	Description
id	UUID	Unique identifier
amount	Integer	Amount in minor currency units
currency	String	ISO currency code
status	Enum	requires_payment_method, requires_confirmation, authorized, captured, failed
created_at	Timestamp	Record creation time
updated_at	Timestamp	Last updated
2. PaymentAttempt (Client-facing)
Field	Type	Description
id	UUID	Unique identifier
payment_intent_id	UUID	FK → PaymentIntent
payment_method	String	Card type / masked info
status	Enum	pending, authorized, failed, captured
created_at	Timestamp	
updated_at	Timestamp	
3. InternalAttempt (Vendor-facing)
Field	Type	Description
id	UUID	Unique identifier
payment_attempt_id	UUID	FK → PaymentAttempt
provider	String	e.g., “mock-visa”
status	Enum	pending, success, failure, timeout
request_payload	JSON	Mock request sent to gateway
response_payload	JSON	Mock response from gateway
created_at	Timestamp	
updated_at	Timestamp	
4. IdempotencyKeys
Field	Type	Description
key	String	Unique idempotency key
request_hash	String	Hash of request payload
response	JSON	Cached response
created_at	Timestamp	
3. API Specification
1. Create PaymentIntent
POST /payment_intents
Request:
{
  "amount": 1000,
  "currency": "USD"
}
Response:
{
  "id": "uuid",
  "amount": 1000,
  "currency": "USD",
  "status": "requires_confirmation"
}
2. Confirm PaymentIntent
POST /payment_intents/{id}/confirm
Headers: Idempotency-Key: <uuid>
Request: Optional payment_method
{
  "payment_method": "card_4242"
}
Behavior:
Create PaymentAttempt
Create InternalAttempt → call MockGateway (async)
Return payment_intent immediately (requires_confirmation)
Webhook updates PaymentAttempt / PaymentIntent
Response:
{
  "id": "uuid",
  "status": "requires_confirmation"
}
3. Capture PaymentIntent
POST /payment_intents/{id}/capture
Precondition: PaymentIntent status = authorized
Behavior:
Create InternalAttempt for capture (async)
Update PaymentAttempt → captured
Update PaymentIntent → captured
Response:
{
  "id": "uuid",
  "status": "captured"
}
4. Webhook (Mock Gateway)
POST /webhooks/gateway
Payload:
{
  "internal_attempt_id": "uuid",
  "status": "success" | "failure" | "timeout"
}
Behavior:
Update InternalAttempt → status
Update PaymentAttempt & PaymentIntent based on new status
5. Mock Gateway
Success 70%, failure 20%, timeout 10%
Async callback to /webhooks/gateway after 1–2 seconds
Payload: {internal_attempt_id, status}
4. State Machines
PaymentIntent
requires_confirmation → authorized → captured
requires_confirmation → failed
PaymentAttempt
pending → authorized → captured
pending → failed
InternalAttempt
pending → success
pending → failure
pending → timeout → retry → success | failure
5. Retry Logic
InternalAttempt with timeout → retry max 2 times
Exponential backoff (simulate 1s → 2s)
PaymentAttempt / PaymentIntent only updated after final InternalAttempt
6. Frontend Requirements
Minimal UI:
Create PaymentIntent
Confirm PaymentIntent
Capture PaymentIntent
Display status updates in real time
Can be React + fetch API
7. Tech Stack Suggestions
Backend: Node.js + Express or Kotlin + Spring Boot
DB: SQLite or Postgres
Frontend: React or plain HTML + JS
Async queue: in-memory (for demo)
Use coding agent to scaffold API + DB + frontend templates
8. Success Criteria (for agent)
Backend
CRUD for PaymentIntent / PaymentAttempt / InternalAttempt
Async mock gateway + webhook updates
Idempotency handling
Retry logic
Frontend
Can create, confirm, capture
Real-time status reflection
Demo
Show PaymentIntent lifecycle from creation → confirmation → capture
Webhook simulation works
Retry logic visible (timeout → retry → success/failure)
9. Deliverables for Coding Agent
Backend with APIs above + DB schema
Mock gateway + webhook handler
Minimal frontend
README explaining how to run & demo