# Family Tree Builder

Build and edit a family tree through chat. React displays the tree; Java 21 / Spring Boot validates changes and saves them in SQLite. The backend replaces the starter Express stubs while preserving its API.

## Start locally

**Requirements:** JDK 21 or 22, Maven 3.6.3+, and Node.js 22.12+.

```sh
git clone https://github.com/connectchiragg/family-tree-builder-challenge.git
cd family-tree-builder-challenge
./start.sh
```

The script installs Node dependencies, prompts for your **OpenRouter API key** only when absent, starts the backend, waits for its database health check to pass, then opens the UI and a metrics tab (macOS/Linux with a desktop browser). Key entry is hidden and saved in Git-ignored `server/.env`; existing configuration is preserved. Maven downloads backend dependencies on startup. Java, Maven, and Node must already be installed.

Run `./start.sh` again for subsequent starts, or `npm run dev` to skip dependency installation.

Open **http://127.0.0.1:5173**. This starts both the frontend and Java backend (port **3001**). Stop them with **Ctrl+C**. Check backend health at http://localhost:3001/api/health.

Try: “My name is Maya. My parents are Ravi and Anitha. They are married, and my brother Arjun shares both parents.”

Use **Search people** to find and focus a person; **Fit View** returns to the full tree. Ask the chat to rename a typo or delete an unwanted person.

## Persistence and configuration

Family data and the request history are stored in `server/family-tree.db` and survives restarts. Chat history and pending request IDs are saved in this browser across reloads. `DB_PATH` can select another database. Changing `PORT` also requires updating the proxy in `client/vite.config.js`.

Without an API key, saved graphs still load but chat is unavailable. Provider settings are in `server/.env.example`.

## How it works

1. The LLM receives the conversation and current graph, then answers, asks for clarification, or proposes one batch of changes.
2. Java validates the entire batch in memory, checking identities, parent limits and ancestry cycles. Only a valid batch is saved, in one transaction.
3. Java confirms saves and explains validation errors without another model call. Only edits that also ask a question use a second call, receiving just the resolved question and saved graph. Full conversation history is retained for the first call.
4. The UI fetches the saved graph after chat and every four seconds.

Names can repeat; people have stable IDs. Renaming preserves relationships. Deleting a person removes their relationships, not other people. Marriage does not imply parenthood.

Backend code lives in `server/src/main/java/pro/workhero/family`: `Agent` coordinates calls, `Tools` parses plans, `GraphDraft` validates them, and `FamilyStore` persists them. UI code is in `client/src`.

## Checks and build

Run from the repository root:

```sh
npm test                       # Java and frontend tests
npm run lint -w client
npm run build -w client
mvn -f server/pom.xml package   # executable backend JAR
```

To run the packaged backend (keep the frontend running separately):

```sh
cd server
java -jar target/family-tree-1.0.0.jar
```

## Scope

A local demo with one family graph, no login or tenant isolation. Maximum **40 operations per turn** and **two model calls**. Ambiguity and wording still depend on the model; Java enforces graph rules. Multiple spouses and half-sibling modeling are outside the current scope. Large trees need zooming and may have connector crossings.

## Request audit and retries

Each chat submission carries a browser-generated request ID. Use **Retry request** after a network/provider failure to resend the same ID and payload. Completed requests replay their reply; active duplicates return HTTP 409; changing content under an existing ID is rejected. New submissions get new IDs. Pending IDs and their payloads survive reloads in browser local storage.

SQLite records a payload hash, status, execution result/reply, timestamps, duration, and failure code, without raw input conversations or credentials. Graph changes and the applied history result commit atomically. After a crash, applied requests return a saved-status fallback; unfinished requests can retry after restart. This recovery policy assumes **one backend process per database**. There are no distributed leases or automatic retries, and separate IDs are not deduplicated. History replies/results contain family data and currently have no automatic retention cleanup.

**Clear conversation** clears this browser’s chat and calls `DELETE /api/history` to remove backend request history. It leaves the family graph intact and refuses while requests are active. Clearing history also removes deduplication records, so old request IDs must not be replayed afterward. Other browsers retain their own local chat.

## Metrics and alerting

The backend binds to loopback for this local demo. Spring Boot Admin displays health and metrics at **http://127.0.0.1:3001/admin/**. Select the application, then Metrics to inspect `llm.request` and `llm.tokens` after a model call.

Spring Boot Actuator + Micrometer expose these backend endpoints (port 3001 by default):

- `/actuator/health`: application/database health.
- `/actuator/metrics`: metric names; append `/http.server.requests` or `/llm.request` to inspect one.
- `/actuator/prometheus`: scrape endpoint for Prometheus-compatible monitoring.

HTTP request counts/timing, JVM and connection-pool metrics are automatic. `llm.request` records provider-call count and duration by `phase` (plan/answer) and `outcome` (success/error). `llm.tokens` counts provider-reported input/output tokens by phase and direction; absent usage is not estimated or recorded as zero. Provider success means HTTP/JSON success, not that the proposed plan passed validation. Metrics contain no request IDs, names, prompts or keys. Counters reset on restart; a scraper retains historical samples.

Suggested alerts for a deployed instance:

- Health probe fails for 1 minute: service unavailable.
- Provider errors exceed 10% of calls over 5 minutes, with at least 10 calls: investigate provider access/availability.
- Chat p95 exceeds 20 seconds for 5 minutes, with at least 20 requests in the window: compare provider timing against HTTP timing.

Example provider-error PromQL:

```promql
(sum(increase(llm_request_seconds_count{outcome="error"}[5m]))
 / sum(increase(llm_request_seconds_count[5m])) > 0.10)
and sum(increase(llm_request_seconds_count[5m])) >= 10
```

These are documented alert conditions, **not active notifications**. A monitoring service must scrape the endpoint, evaluate rules and route alerts. Spring Boot Admin supplies an embedded live dashboard at `/admin/`; no separate monitoring stack is required. Startup opens this dashboard automatically. It does not provide durable metric history or configured alert notifications. Keep metrics endpoints private when deploying publicly; the demo has no authentication.

The dashboard uses a purple Family Tree theme. Select the app instance, then **Logfile** to read live backend logs. Logs also remain in the terminal. Files rotate at 5 MB, retain up to 7 days of archives with a 50 MB archive cap, and live in Git-ignored `server/logs/` during normal startup. Set `LOG_FILE` to change the location. The logfile endpoint serves the current log, not the rotated archives.
