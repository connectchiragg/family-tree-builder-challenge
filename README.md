# Family Tree Builder

Create and edit a family tree through chat. React frontend, Java 21 / Spring Boot backend, and SQLite persistence.

## Start

Requires **JDK 21 or 22**, **Maven 3.6.3+**, and **Node.js 22.12+**.

```sh
git clone https://github.com/connectchiragg/family-tree-builder-challenge.git
cd family-tree-builder-challenge
./start.sh
```

The script installs dependencies, prompts for an OpenRouter API key if missing, starts the backend, waits until it is ready, then opens:

- **App:** http://127.0.0.1:5173
- **Metrics and logs:** http://127.0.0.1:3001/admin/ — select the application instance.

Press **Ctrl+C** to stop. Run `./start.sh` to restart.

## Data and behavior

- Keys stay in ignored `server/.env`; configuration options are in `server/.env.example`.
- SQLite creates `server/family-tree.db` automatically and preserves data across restarts.
- Responses use Java records, generated tool schemas and Jakarta Validation; relationship kinds are enums. The configured Sonnet 4 route uses tool calling, not native strict JSON output.
- Java validates each batch before saving atomically. Maximum 40 operations and two model calls per turn.
- Retry uses the original request ID to prevent duplicate application of completed requests.
- **Clear conversation** removes chat/request history, leaving the family graph intact.
- Local, single-family demo: no authentication; metrics reset on restart and alert notifications are not configured.

## Verify

```sh
npm test
npm run lint -w client
npm run build -w client
```
