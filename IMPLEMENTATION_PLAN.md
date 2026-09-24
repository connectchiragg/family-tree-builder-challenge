# Family Tree Builder: implementation plan

Status: proposed; implementation awaits approval.

## Starting point verified

Reviewed upstream commit `6bf19310810e5714eee1d74c23bb06061e3d45b2`.
The React/Vite UI and Express API exist. Contrary to the README, the LLM
client already contains an unbounded tool loop and an `add_person` tool that
ignores its input and returns a fake success. The graph route returns three
hard-coded people and sample edges. There is no persistence or test suite.

## Scope and design

Keep JavaScript, Express, the Anthropic message/tool protocol, and the current
graph response shape. Use SQLite through `better-sqlite3`, with prepared
statements, foreign keys, and short transactions. Keep the existing UI;
make only small integration fixes if acceptance testing exposes a blocker.
Target one local user and one family graph. Graph data persists; browser chat
history remains ephemeral, as in the starter.

### 1. Persistence and domain rules (approximately 40 minutes)

- Add `server/src/db.js` for opening a configurable database file and schema
  initialization, and `server/src/family/store.js` for reads and mutations.
- Store people with server-generated stable IDs and nonempty names. Names are
  deliberately not unique. Avoid speculative demographic attributes.
- Store parent edges with a composite unique key and foreign keys. Reject
  self-parenting, a third distinct parent, and any edge where the child can
  already reach the proposed parent. Perform checks and writes in the same
  transaction.
- Store spouse edges as sorted ID pairs, reject self-links, and enforce pair
  uniqueness. Never infer parenthood from marriage. Unsupported remarriage
  receives a stated limitation.
- Treat an existing identical relationship as a successful no-op.
- Replace graph route examples with database reads, initially returning an
  empty graph. Extend ignore rules to cover SQLite journal/WAL sidecars.

### 2. Tool contract and corrections (approximately 35 minutes)

Add `server/src/llm/tools.js` with definitions, runtime validation, dispatch,
and structured results. Proposed tools:

| Tool | Purpose |
| --- | --- |
| `get_family_tree` | Read the small current graph and stable IDs |
| `find_people` | Return all name matches with relationship context |
| `create_person` | Create an explicitly new person; return their generated ID |
| `update_person` | Rename an existing person by ID, preserving all edges |
| `add_relationship` | Add a parent or spouse relationship by IDs |
| `remove_relationship` | Remove an explicitly corrected relationship |
| `replace_relationship` | Remove an old edge and add its replacement atomically |

Use strict schemas and validate at execution time, not merely in the model
prompt. Reject unknown tools, extra fields, invalid IDs, and malformed inputs
with useful error codes. Relationship correction rolls back completely if the
replacement violates an invariant.

Read current graph context at each turn so later requests and process restarts
do not depend on tool transcripts retained by the browser. Instruct the model
to resolve existing people before creating new ones, distinguish matching
names using relationship context, and ask when references remain ambiguous.
Mutations reference IDs rather than name-based updates. A matching name is
never sufficient reason to merge people. Clarify who “I” refers to when the
conversation does not establish it; do not manufacture unknown parents.

Boundary: backend validation guarantees graph invariants, but determining
whether language identifies a person unambiguously still involves the model.
Tests and manual scenarios must assess that behavior; ID-based tools alone
do not guarantee semantic correctness.

### 3. Agent loop and operational boundaries (approximately 35 minutes)

- Refactor `server/src/llm/client.js` to provider configuration and add
  `server/src/llm/agent.js` for orchestration, with an injectable model client
  for offline tests.
- Preserve assistant content blocks and pair every tool call with its result.
  Execute calls sequentially to respect dependent mutations; return tool
  failures as error results the model can explain or recover from.
- Bound the loop, calls per turn, and provider timeout. Handle truncated or
  empty model replies explicitly. Return all final text blocks, not just the
  first one. Do not claim success after failed writes.
- Validate API message roles, string content, and reasonable payload limits.
- Make model/base URL configurable and document the supplied OpenRouter
  settings. Verify actual model availability during an authorized live smoke
  test; do not assume the starter's default is available on the supplied key.
- Cache tool-call results within a request so repeated identical call IDs do
  not rerun writes; reject reuse with different inputs. Database uniqueness
  makes repeated edge operations harmless. Avoid automatic whole-turn retry.
- Document the remaining boundary: a new HTTP retry can repeat person creation,
  and completed writes can survive a later provider failure. Durable request
  IDs/result replay are a next step, not a claim of exactly-once execution.
- Emit structured request duration, model/tool call counts, validation failure
  codes, and loop/timeout failures without credentials or conversation bodies.
  Explain future alerts for sustained API errors, latency, and loop exhaustion.
  Distributed leases and monitoring infrastructure are unnecessary for this
  single-process take-home; describe when multiple workers would require them.

### 4. Verification and documentation (approximately 40 minutes)

Use Node's test runner and temporary databases. Prioritize behavioral tests:

1. A fresh database is empty; people and relationships survive reopening it.
2. Duplicate names are distinct people; searches return all candidates.
3. Renaming preserves identity and relationships.
4. Reject direct/indirect cycles, self-links, missing endpoints, and a third
   parent; accepted spouse edges never create parent edges.
5. Duplicate parent edges and reversed spouse pairs do not duplicate facts.
6. A valid correction replaces its old edge; an invalid replacement restores
   the original graph.
7. Mocked model turns exercise multiple tool rounds, tool errors, duplicate
   call IDs, final text, and loop exhaustion without paid inference.
8. API contract and frontend build checks, then a manual chat/graph/restart
   smoke test if a credential is available.

Manual conversation scenarios: describe a small family; introduce two Johns
and give an ambiguous reference; clarify which John; correct a name; correct
a parent; attempt a cycle; request an unsupported family structure. Confirm
both assistant wording and stored graph. Mock tests do not prove live model
ambiguity handling.

Update README with setup/runtime requirements, tool schema rationale,
identity and correction behavior, tests, limitations, operational tradeoffs,
and an example walkthrough. Keep credentials and local databases out of Git.

## Timebox and review checkpoints

The allocations total approximately 150 minutes, leaving up to 30 minutes
for integration issues within the requested 2–3 hours. Record actual elapsed
implementation time. If time runs short, prioritize persistence, graph
invariants, a real bounded tool loop, corrections, and meaningful tests;
document deferred polish honestly.

Suggested commits: (1) persistence and graph rules with tests; (2) tools and
bounded agent loop with tests; (3) integration verification and README.
Read the store first, tools second, agent loop third, then routes and tests
during the follow-up preparation.

## Approval boundary

The fork and this plan are the only deliverables at this stage. No feature
implementation, dependency installation, or paid model calls have begun.
Start implementation only after the user's approval. Collaborator invitations
and submission communications are separate from this planning step.
