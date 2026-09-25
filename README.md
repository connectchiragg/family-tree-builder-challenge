# Family Tree Builder

A conversational family tree with a Java 21 / Spring Boot backend, SQLite persistence,
and the starter's React graph UI. The backend replaces the Express stubs while
preserving `POST /api/chat`, `GET /api/graph`, and `GET /api/health`.

## Run

Requires JDK 21+ (tested on 22 and 25), Maven 3.6.3+, and Node compatible with Vite 8
(Node 20.19+ or 22.12+).

```sh
npm install
cp server/.env.example server/.env
# Set ANTHROPIC_API_KEY in server/.env. Never commit the key.
npm run dev
```

Open http://localhost:5173. The Java server listens on port 3001. The frontend
proxies `/api` there. Run from the repository root with the command above so
Spring reads `server/.env` and uses `server/family-tree.db`.

The default provider is OpenRouter's Anthropic-compatible endpoint:

```properties
ANTHROPIC_BASE_URL=https://openrouter.ai/api
ANTHROPIC_MODEL=anthropic/claude-sonnet-4
```

For direct Anthropic, set the base to `https://api.anthropic.com` and configure an
available Anthropic model ID. The client appends `/v1/messages`. `PORT` and
`DB_PATH` are optional; changing the port also requires changing the Vite proxy.
Without a key the graph still works; chat reports a configuration error.

```sh
npm test                        # UI layout + Java unit/integration tests
npm run build -w client
npm run lint -w client
cd server && mvn package         # executable target/family-tree-1.0.0.jar
mvn spotless:apply              # formatter; use JDK 21/22 with this formatter version
```

## Design and reading order

1. `Family.java`: immutable people, edges and graph records.
2. `Plan.java`: sealed operation interface and typed operation records.
3. `GraphDraft.java`: pure business rules applied to a private graph copy.
4. `FamilyStore.java`: loads current state, validates the batch, then persists its diff atomically.
5. `Tools.java`: one batch-tool schema and strict Jackson deserialization.
6. `Agent.java`: planning call, execution, then explanation call; no repair loop.
7. `ModelClient.java` / `HttpModelClient.java`: provider interface and HTTP adapter.
8. `Api.java`: HTTP validation and frontend contracts.

Java paths are relative to `server/src/main/java/pro/workhero/family`. Tests are
under `server/src/test/java/pro/workhero/family`.

## Two-call workflow

The first call receives conversation history, the current graph and one tool:
`apply_family_changes`. It either asks a clarification/answers a read-only question
in text (one call total), or submits one complete ordered plan (two calls total).

```json
{
  "operations": [
    {"type": "create_person", "ref": "@parent", "name": "Sam"},
    {"type": "create_person", "ref": "@child", "name": "Alex"},
    {"type": "add_relationship", "kind": "parent", "from": "@parent", "to": "@child"}
  ]
}
```

New people must have explicit creation operations with unique `@references`.
Later operations resolve those references to server-generated UUIDs. References
without `@` must be existing database IDs; unknown IDs are errors, never implicit
person creation. Forward/duplicate temporary references are rejected.

Supported operations:

| Type | Required fields besides `type` |
| --- | --- |
| `create_person` | `ref`, `name` |
| `rename_person` | `person`, `name` |
| `delete_person` | `person` |
| `add_relationship` | `kind`, `from`, `to` |
| `remove_relationship` | `kind`, `from`, `to` |
| `replace_relationship` | `oldKind`, `oldFrom`, `oldTo`, `newKind`, `newFrom`, `newTo` |

Relationship kinds are `parent` and `spouse`. Parent direction is from → to.
The tool supplies a JSON Schema; Java deserializes to a sealed operation hierarchy
and rejects unknown fields, missing/null fields and scalar coercion. Schema/type
correctness does not replace business validation, and provider schema adherence
is not assumed to be infallible.

`FamilyStore.apply` loads the latest graph within a short transaction and simulates
all operations on `GraphDraft`. No SQL mutations occur until every operation
passes. Validation failure discards the draft, with nothing to roll back. After
validation, SQL persists the diff; a database failure during that phase rolls back
all writes. Model calls never hold a database transaction.

There is no stale-snapshot rejection. Later operations apply to the current graph;
for example a later rename overwrites an earlier name. They still must satisfy
current business rules, including the existence of an edge being replaced.
Unrelated intervening changes are preserved, not overwritten by a stale full graph.
The single-connection pool serializes database transactions in this local process.

The execution result is returned to the second model call, with `tool_choice: none`.
That call explains success or rejection and can ask the user for clarification.
It cannot repair/replan or execute another mutation; unexpected tool output is
never executed. Multiple first-call plans are rejected before any one is applied.
If the explanation fails after commit, Java returns a truthful saved-status fallback
rather than inviting a duplicate submission. Model-call count and duration are logged.

## Identity and rules

Names are not unique. Same-name people retain different IDs. The model must clarify
ambiguous identity before submitting any mutations for that turn. The user answers
on the next turn. Semantic ambiguity remains model-dependent; the backend cannot
prove that an existing ID represents the intended person.

Business rules check existing endpoints, no self-relationships, no directed parent
cycles, at most two parents per child, and no multiple spouses. Duplicate edges are
no-ops. Spouse pairs are canonical and never imply parent edges. Name corrections
preserve IDs and relationships. Explicit person deletion removes that person and
all their parent/spouse edges, preserving every other person (including namesakes).
Deletion is included in full-batch validation and atomic persistence. Replacement requires the old edge to exist.

## Reliability and limits

At most two model calls per turn, each with a 30-second request timeout and a
10-second connection timeout. Plans contain 1–40 operations; output budget is 4096
tokens. Truncated plans are rejected before execution. No automatic whole-turn
retry, model checker, or automatic repair loop. Independent repeated HTTP requests
can still create duplicate people; durable request IDs are a future improvement.

One local family graph; no authentication or tenant isolation. Browser chat history
is ephemeral. Remarriage, half-siblings and invented unknown parents are outside
scope. The model sees the full small graph; this is not intended for large datasets.
Type safety and graph constraints do not guarantee correct language interpretation.

The UI offers a top-down family tree. Spouses and co-parents
are aligned where ancestry permits; presentation never invents relationships.
Dense graphs may still have crossing/overlapping connectors.

Logs contain request ID, model-call count, duration and error codes, not chat bodies
or credentials. `/api/health` checks the database. Production work would add provider
error/latency metrics and alerts; no monitoring service or distributed leases are
included in this local exercise.

## Demo walkthrough

1. “My name is Alex. My parents are Sam and Jordan.” Check three people and two
   parent edges. Do not infer a spouse edge.
2. “Sam and Jordan are married.” Check one spouse edge.
3. “Jordan's name should be Jordon.” Check that the same node and edges remain.
4. “Sam is not my parent; Taylor is. Taylor is a new person.” Check replacement.
5. Introduce two distinct Johns and then refer ambiguously to “John”. Expect a
   clarification and no ambiguous write.
6. Ask for a parent relationship that would create a cycle. Expect rejection and
   unchanged edges.
7. Restart the server and refresh the graph. Data remains; browser conversation
   history is not persisted and refreshing the page clears it.

## Validation

The tests cover typed plan parsing, temporary references, duplicate names, graph
invariants, zero SQL writes on validation rejection, transaction rollback on actual
persistence failure, later-rename-wins behavior, and one-call clarification/two-call
mutation behavior. Provider tests inspect `tool_choice: none` on explanation calls.
The frontend has eight layout/connector tests plus build/lint checks.

Live verification results are recorded in the PR. The key remains only in ignored
local `server/.env`, never in browser code or source control.

To remove an accidental person, ask “Delete [name] from the tree.” Ambiguous
names require clarification. For spelling corrections, ask to rename instead.

Children with identical recorded parent sets share a parent bar, central stem
and sibling bar. Spouse edges remain separate; this rendering does not infer
parenthood from marriage.
