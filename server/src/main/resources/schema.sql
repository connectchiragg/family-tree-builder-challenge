CREATE TABLE IF NOT EXISTS person (id TEXT PRIMARY KEY, name TEXT NOT NULL CHECK(length(trim(name)) > 0));
CREATE TABLE IF NOT EXISTS parent_edge (
 parent_id TEXT NOT NULL REFERENCES person(id), child_id TEXT NOT NULL REFERENCES person(id),
 PRIMARY KEY(parent_id, child_id), CHECK(parent_id <> child_id)
);
CREATE TABLE IF NOT EXISTS spouse_edge (
 person_a_id TEXT NOT NULL REFERENCES person(id), person_b_id TEXT NOT NULL REFERENCES person(id),
 PRIMARY KEY(person_a_id, person_b_id), CHECK(person_a_id < person_b_id)
);
CREATE TABLE IF NOT EXISTS request_history (
 request_id TEXT PRIMARY KEY, payload_hash TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('processing','applied','completed','failed')),
 execution_result TEXT, reply TEXT, error_code TEXT, duration_ms INTEGER,
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
