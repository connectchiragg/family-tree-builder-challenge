# Family Tree Builder

Create and edit a family tree through chat. React, Java 21 / Spring Boot, and SQLite.

## Start

Requires **JDK 21 or 22**, **Maven 3.6.3+**, and **Node.js 22.12+**.

```sh
git clone https://github.com/connectchiragg/family-tree-builder-challenge.git
cd family-tree-builder-challenge
./start.sh
```

Installs dependencies, asks for an OpenRouter key if missing, waits for the backend, then opens http://127.0.0.1:5173. On macOS, it also opens the metrics dashboard at http://127.0.0.1:3001/admin/. **Ctrl+C** stops both servers.

Keys stay in ignored `server/.env` (see `.env.example`). SQLite creates `server/family-tree.db` automatically and keeps data across restarts. **Clear conversation** clears browser chat and backend request history, not the family tree.

## Decisions and tradeoffs

- **Java + SQLite:** familiar, typed domain code and a database that needs no separate service. The React frontend keeps the starter API contract; this targets one local backend process.
- **Plan, validate, save:** one model call proposes an ordered batch. `GraphDraft` checks every operation in memory; `FamilyStore` commits only a valid batch. An optional second call answers a follow-up question using the saved graph, without mutation tools.
- **Six explicit operations:** create, rename, delete, add/remove/replace relationship. Java records, enums, a fixed tool schema and annotation validation keep the contract readable. The Sonnet 4 route uses tool calling, not native strict JSON output, so response checks remain necessary.
- **Identity over names:** stable IDs allow duplicate names and in-place spelling corrections; `@refs` identify new people within a batch. Ambiguous requests should ask for clarification before any edit. Valid IDs cannot prove the model chose the intended person.
- **Retry safety:** `RequestHistory` saves a request ID and payload hash, and records applied edits in the same transaction as the graph. Reusing that ID returns the saved result; pre-save failures can retry. New IDs and cleared history are outside that deduplication guarantee.
- **Simple graph UI:** generation layout, shared-parent connectors and search improve readability; polling plus a refresh after chat avoids a real-time messaging service.

## Limits and observability

Local single-family demo, bound to loopback, with no authentication or multi-instance coordination. Up to **40 operations and two model calls per turn**, two recorded parents and one spouse per person; remarriage and half-sibling workflows are outside scope. No stale-graph rejection; model interpretation can still be wrong, and dense trees can have crossing connectors.

Actuator exposes `/actuator/metrics` and `/actuator/prometheus`; the stock dashboard shows metrics and a Logfile viewer. Logs also go to the terminal and ignored `server/logs/family-tree.log`. Metrics reset on restart. Alert notifications and external metrics storage are not configured.

## Verify

```sh
npm test
npm run lint -w client
npm run build -w client
```
