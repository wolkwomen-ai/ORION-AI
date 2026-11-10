# ARION 4.0 — Autonomous Agent Backend

This is the persistent backend foundation for ARION's autonomous business-research agent.
It is separate from the Android APK. The Android app must be connected to a deployed HTTPS
instance before these server features become available on the phone.

## What it does
- Stores the business profile, agent state, work products, audit events and a multi-currency ledger in SQLite.
- Provides an internal wallet accounting view that splits verified receipts 20% / 80%; it does not custody money.
- Runs an autonomous research cycle on a configurable interval (minimum 15 minutes).
- Uses an OpenAI-compatible API (Groq by default) to create a concrete business opportunity brief and deliverable.
- Searches public web results and treats them as untrusted evidence.
- Exposes authenticated API endpoints for profile, start/stop, manual cycle, task history, ledger and audit.
- Uses integer minor units for money.

## What it does NOT do yet
No marketplace OAuth adapter, customer messaging, publishing, bank/payment connection, bank-card payout, crypto exchange adapter,
account creation, order acceptance, money transfer, trade execution or contract signing is implemented.
The agent can research and prepare work products, but it cannot truthfully claim revenue until an actual
payment is recorded. Platform/account connections must use official OAuth/API flows and the account owner's
real KYC details where required. No bypass or impersonation functionality is provided.

## Deploy
Deploy this folder as a persistent container with a durable volume mounted at /data.
Required environment variables:
- ARION_API_TOKEN: long random bearer token (never commit it).
- ARION_AI_API_KEY: provider API key (never commit it).
Optional:
- ARION_AI_BASE_URL (default Groq OpenAI-compatible endpoint)
- ARION_MODEL (default qwen/qwen3.8-27b)
- ARION_DB_PATH (default /data/arion.sqlite3)
- ARION_CYCLE_SECONDS (default 3600; minimum 900)

Build: docker build -t arion-agent backend/arion_agent
Run: docker run -p 8080:8080 -v arion-data:/data --env-file .env arion-agent

Use HTTPS at the hosting provider or behind a trusted TLS reverse proxy. Do not expose the API without
ARION_API_TOKEN. Back up the persistent database. A single process is assumed; use a managed database
before horizontal scaling.

## API
- GET /health
- GET/PUT /v1/profile
- POST /v1/agent/start
- POST /v1/agent/stop
- POST /v1/agent/cycle
- GET /v1/agent/status
- GET /v1/tasks
- GET/POST /v1/ledger
- GET /v1/audit
- GET /v1/capabilities

The backend is not yet deployed and the current Android APK is not yet wired to this API.
