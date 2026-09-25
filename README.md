# Family Tree Builder

Build and edit a family tree through chat. React displays the tree; Java 21 / Spring Boot validates changes and saves them in SQLite. The backend replaces the starter Express stubs while preserving its API.

## Start locally

**Requirements:** JDK 21 or 22, Maven 3.6.3+, and Node.js 22.12+.

```sh
git clone --branch feat/java-family-tree https://github.com/connectchiragg/family-tree-builder-challenge.git
cd family-tree-builder-challenge
./start.sh
```

The script installs Node dependencies, prompts for your **OpenRouter API key** only when absent, and starts both services. Key entry is hidden and saved in Git-ignored `server/.env`; existing configuration is preserved. Maven downloads backend dependencies on startup. Java, Maven, and Node must already be installed.

Run `./start.sh` again for subsequent starts, or `npm run dev` to skip dependency installation.

Open **http://localhost:5173**. This starts both the frontend and Java backend (port **3001**). Stop them with **Ctrl+C**. Check backend health at http://localhost:3001/api/health.

Try: “My name is Maya. My parents are Ravi and Anitha. They are married, and my brother Arjun shares both parents.”

Use **Search people** to find and focus a person; **Fit View** returns to the full tree. Ask the chat to rename a typo or delete an unwanted person.

## Persistence and configuration

Family data is stored in `server/family-tree.db` and survives restarts. Chat history is browser memory only and clears on reload. `DB_PATH` can select another database. Changing `PORT` also requires updating the proxy in `client/vite.config.js`.

Without an API key, saved graphs still load but chat is unavailable. Provider settings are in `server/.env.example`.

## How it works

1. The LLM receives the conversation and current graph, then answers, asks for clarification, or proposes one batch of changes.
2. Java validates the entire batch in memory, checking identities, parent limits and ancestry cycles. Only a valid batch is saved, in one transaction.
3. A second LLM call explains the actual result. It cannot execute more changes. Questions and clarifications need only one call.
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
