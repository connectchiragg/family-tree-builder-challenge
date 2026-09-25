# Current implementation

Java 21/Spring Boot backend with SQLite, preserving the React frontend/API contracts.
One typed batch tool accepts a complete ordered mutation plan. Java validates it
on a private graph copy before writes, then persists atomically. The second model
call has tools disabled and only explains the result. Clarification/read-only
turns use one call. No automatic repair or stale-snapshot rejection.

See README for architecture, schemas, reading order, setup and limitations.
