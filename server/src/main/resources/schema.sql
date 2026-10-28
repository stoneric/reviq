CREATE TABLE IF NOT EXISTS schema_version (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS anonymous_sessions (
 id text PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL DEFAULT now() + interval '24 hours', active_until timestamptz, active_request text
);
CREATE TABLE IF NOT EXISTS datasets (
 id uuid PRIMARY KEY, session_id text NOT NULL REFERENCES anonymous_sessions(id) ON DELETE CASCADE,
 data jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL DEFAULT now() + interval '24 hours',
 UNIQUE(session_id,id)
);
CREATE INDEX IF NOT EXISTS datasets_session_idx ON datasets(session_id);
CREATE TABLE IF NOT EXISTS reviews (
 session_id text NOT NULL, dataset_id uuid NOT NULL, review_id text NOT NULL, text text NOT NULL,
 rating smallint CHECK(rating BETWEEN 1 AND 5), review_date date, data jsonb NOT NULL,
 PRIMARY KEY(dataset_id,review_id), FOREIGN KEY(session_id,dataset_id) REFERENCES datasets(session_id,id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS chat_messages (
 id bigserial PRIMARY KEY, session_id text NOT NULL, dataset_id uuid NOT NULL, role text NOT NULL CHECK(role IN ('user','assistant')), content text NOT NULL, citations jsonb NOT NULL DEFAULT '[]', mode text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(session_id,dataset_id) REFERENCES datasets(session_id,id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS chat_dataset_idx ON chat_messages(dataset_id,id);
CREATE TABLE IF NOT EXISTS quota_counters (key text PRIMARY KEY, count integer NOT NULL DEFAULT 0, reserved_usd numeric(12,6) NOT NULL DEFAULT 0, expires_at timestamptz NOT NULL);
CREATE TABLE IF NOT EXISTS cost_reservations (request_id text PRIMARY KEY, quota_key text NOT NULL REFERENCES quota_counters(key) ON DELETE CASCADE, amount numeric(12,6) NOT NULL, reconciled boolean NOT NULL DEFAULT false);
INSERT INTO schema_version(version) VALUES(1) ON CONFLICT DO NOTHING;
