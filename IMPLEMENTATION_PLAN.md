# Implementation scope

The original JavaScript proposal was superseded by the user's approved Java
backend direction. Implementation uses Java 21 records, Spring Boot, SQLite/JDBC,
a command registry for tools and a small model-client interface. The React UI
and API contracts are retained; the Express stubs are removed.

Completed: persistence and graph rules; strict tool schemas and atomic corrections;
bounded agent loop; HTTP provider adapter; API validation; frontend error messages;
unit/integration tests and README walkthrough.

See README for architecture, reading order, verification commands and explicit
limitations. Live OpenRouter validation has passed the README scenarios. The supplied key
is kept only in ignored `server/.env`; it is never shipped to the browser or Git.
