-- Run once in your Vercel Postgres console (Storage → your DB → Query)
CREATE TABLE IF NOT EXISTS events (
  id             SERIAL PRIMARY KEY,
  client_id      TEXT        NOT NULL,   -- anonymous UUID, persisted on user's machine
  tool_name      TEXT        NOT NULL,   -- e.g. "get_open_editors"
  plugin_version TEXT,                   -- e.g. "2.4.0"
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for fast GROUP BY queries
CREATE INDEX IF NOT EXISTS events_tool_name_idx ON events (tool_name);
