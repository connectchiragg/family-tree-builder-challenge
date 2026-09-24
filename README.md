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

1. `Family.java`: immutable records for people, edges, and graph snapshots.
2. `FamilyStore.java`: small JDBC repository with domain validation and transactions.
3. `Tools.java`: command registry holding each tool's schema and executable handler.
4. `ModelClient.java` / `HttpModelClient.java`: provider boundary and HTTP adapter.
5. `Agent.java`: bounded tool loop, independent of transport and controller.
6. `Api.java`: request validation and the original frontend API contract.
7. `src/test/java/pro/workhero/family`: behavioral tests.

Paths above are relative to `server/src/main/java/pro/workhero/family` except the
test path, which is relative to `server`.

Java records, text blocks, and small functions keep the code direct. The only
intentional abstraction is the model interface, allowing scripted offline tests.
The tool registry is a lightweight command pattern; no separate class per tool,
ORM, agent framework, distributed lock service, or generic repository hierarchy.

## Tool contract

| Tool | Required input | Purpose |
| --- | --- | --- |
| `get_family_tree` | none | Read current IDs and relationship context |
| `find_people` | `name` | Return every exact case-insensitive name match |
| `create_person` | `name` | Create an explicitly new person with a server-generated UUID |
| `update_person` | `id`, `name` | Correct a name while retaining identity |
| `add_relationship` | `kind`, `fromId`, `toId` | Add `parent` or `spouse` |
| `remove_relationship` | same | Retract an explicitly identified relationship |
| `replace_relationship` | `oldKind`, `oldFromId`, `oldToId`, `newKind`, `newFromId`, `newToId` | Atomic correction |

All fields are required; extra fields and wrong types are rejected at runtime.
IDs and names are nonblank strings of at most 120 characters. Tool errors are
returned as `is_error` results with a code and explanation. Unknown tools cannot
invoke arbitrary application behavior.

Names are deliberately not unique. Tools return stable person IDs; writes use
those IDs. The system prompt includes the current graph on every model call,
so persisting internal tool transcripts across HTTP requests is unnecessary for
this small graph. Name matching is exact, not fuzzy. The model can use the full
graph for contextual lookup and spelling corrections.

## Correctness and ambiguity

The model must ask a specific clarification when several people fit a reference.
It must establish the speaker for “I”/“my” and must not invent missing parents.
This semantic behavior depends on the model; database constraints cannot prove
that a chosen person is the one the user intended. The scripted clarification
test checks orchestration, not live-model comprehension.

The backend independently enforces existing endpoints, no self-links, at most
two parents, and a directed acyclic parent graph. Before adding parent → child,
it searches from child to parent. A reachable parent would create a cycle.
Spouse pairs are sorted and unique. Marriage never adds parent edges; multiple
spouses receive an unsupported-scope error.

Renaming preserves the ID and edges. `replace_relationship` removes the old edge
and validates/inserts the new edge in one transaction. Any failure restores the
original. A single-connection pool serializes short database transactions,
including concurrent writes in this single application process. Model calls
never hold a database transaction. SQLite foreign keys are enabled on connection
creation. Restarting the process preserves graph data.

## Agent behavior and reliability

The loop preserves assistant blocks and returns a result for each requested tool.
There is no separate model checker. A text-only answer returns immediately.
The main prompt specifies resolve → create → relate/correct → report, prohibits
invented IDs/results, and distinguishes failed, unchanged, and committed facts.
Current graph state and actual in-request tool outcomes accompany each call;
older assistant claims are not treated as evidence of a saved write. These
instructions improve grounding but cannot guarantee semantic correctness.
Calls execute sequentially. Limits are eight model rounds and 24 tool calls per
HTTP request, with a 30-second timeout per provider request and a 10-second
connection timeout. There is no streaming; worst-case total waiting can span
multiple provider timeouts. Truncated or empty output fails explicitly.

Identical repeated tool-call IDs replay their in-request result; changed input
under a reused ID fails. Duplicate parent/spouse additions are no-ops. There is
no automatic model or whole-turn retry.

**Boundaries:** independent HTTP retries are not exactly-once; a newly generated
person call can create a duplicate. Successful earlier tools remain committed
if a later tool/provider call fails. The UI reports this possibility. A correction
is atomic, but an entire conversational turn is not. Durable request IDs and
stored response replay are the first extension for reliable retries. Concurrent
chat turns can reason from stale semantic context even though database writes
preserve graph invariants; version checks would address this in a multi-user app.

Logs include request ID, duration, tool counts, tool names and validation error
codes, not API keys, names or chat bodies. `/api/health` checks database access.
For deployment, add metrics for provider errors, p95 latency, validation failures,
and loop exhaustion; alert on sustained changes, not individual user mistakes.
There is no metrics backend or alert service in this take-home. Distributed
leases become relevant only if work is queued across multiple workers.

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

## Limitations

One local user / one shared family graph; no authentication or tenant isolation.
The development server is not a public deployment. Remarriage, half-siblings,
more than two parents, and invented unknown parents are unsupported. The graph
is loaded in full, appropriate for a small exercise. No fuzzy identity resolution,
merge-person operation, durable chat history, undo log or full-turn transaction.
The UI offers a top-down family tree and a grouped relationship list. Spouses
and co-parents are aligned where ancestry permits, with independent family
groups spaced apart. Layout grouping never implies marriage or parenthood.
Cross-generation spouse links retain ancestry ranks; dense graphs may still
have crossing lines, and the List view gives an unambiguous relationship readout. Provider model availability and
semantic quality require a live check with the supplied credential.

## Validation

Tests cover graph invariants, duplicate names/edges, rename identity, correction
rollback, concurrent parent additions, persistence through a separate connection,
API input validation, tool dispatch errors, repeated tool IDs, bounded iteration,
all final text blocks, and actual HTTP protocol against a local stub provider.
A local stub verifies wiring only; it does not establish live-model correctness.

Verified locally: all 19 Java tests pass; frontend build and lint pass. Browser
verification with an explicitly labeled offline provider rendered three people
and two parent edges. A full server restart preserved the same graph and IDs.
The offline provider used a separate temporary database and is not shipped.
Live OpenRouter validation passed creation, marriage, stable-ID renaming, cycle
rejection, two same-name people, ambiguity without a write, clarified renaming,
and atomic parent replacement. A model false-success response observed during
the initial run motivated explicit tool-first instructions. The extra model
checker was removed; a regression test enforces single-call text replies. These
scenario checks are evidence for this demo, not a guarantee across all prompts.

UI verification: five layout tests pass; build/lint pass; both Tree and List
views were inspected in the browser using the live eight-person family graph.
