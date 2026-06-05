-- Code Intelligence Engine schema (SQLite).
-- All identifiers stored as TEXT; numeric ids are INTEGER (rowid alias).
-- Tuned for read-heavy traversal with periodic batched writes.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS repos (
  id              INTEGER PRIMARY KEY,
  root_path       TEXT NOT NULL UNIQUE,
  created_at      INTEGER NOT NULL,
  last_indexed_at INTEGER
);

CREATE TABLE IF NOT EXISTS files (
  id          INTEGER PRIMARY KEY,
  repo_id     INTEGER NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
  path        TEXT NOT NULL,
  mtime       INTEGER NOT NULL,
  sha         TEXT NOT NULL,
  size        INTEGER NOT NULL,
  lang        TEXT NOT NULL DEFAULT 'java',
  indexed_at  INTEGER,
  UNIQUE(repo_id, path)
);
CREATE INDEX IF NOT EXISTS idx_files_repo ON files(repo_id);

-- Unified symbol table for classes/interfaces/enums/records/annotations,
-- methods, constructors, fields, packages. parent_id chains methods/fields
-- to their declaring type and nested types to the enclosing type.
CREATE TABLE IF NOT EXISTS symbols (
  id              INTEGER PRIMARY KEY,
  repo_id         INTEGER NOT NULL,
  file_id         INTEGER REFERENCES files(id) ON DELETE CASCADE,
  parent_id       INTEGER REFERENCES symbols(id) ON DELETE CASCADE,
  kind            TEXT NOT NULL,    -- CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE,
                                    -- METHOD, CONSTRUCTOR, FIELD, PACKAGE
  package         TEXT,
  simple_name     TEXT NOT NULL,
  fqn             TEXT NOT NULL,    -- type:  pkg.Outer.Inner
                                    -- method: pkg.Outer#method
                                    -- field:  pkg.Outer.fieldName
  signature       TEXT,             -- methods/constructors: full text signature
  return_type     TEXT,             -- methods: declared return type
  modifiers       TEXT,             -- comma joined: public,static,abstract,final
  framework_role  TEXT,             -- REST_CONTROLLER, CONTROLLER, SERVICE, REPOSITORY,
                                    -- COMPONENT, CONFIGURATION, SCHEDULED,
                                    -- SERVLET, FILTER, LISTENER,
                                    -- ENTITY, JPA_REPOSITORY, MESSAGE_LISTENER,
                                    -- HTTP_ENDPOINT (per-method)
  start_line      INTEGER,
  end_line        INTEGER,
  param_types     TEXT              -- methods/ctors: comma-joined erased simple param type names.
                                    -- Empty string == no-arg. NULL for non-callables.
);
CREATE INDEX IF NOT EXISTS idx_symbols_fqn        ON symbols(fqn);
CREATE INDEX IF NOT EXISTS idx_symbols_simple     ON symbols(simple_name);
CREATE INDEX IF NOT EXISTS idx_symbols_kind_name  ON symbols(kind, simple_name);
CREATE INDEX IF NOT EXISTS idx_symbols_file_kind  ON symbols(file_id, kind);
CREATE INDEX IF NOT EXISTS idx_symbols_role       ON symbols(framework_role);
CREATE INDEX IF NOT EXISTS idx_symbols_parent     ON symbols(parent_id);
CREATE INDEX IF NOT EXISTS idx_symbols_ctor_lookup ON symbols(parent_id, kind, simple_name, param_types);

CREATE TABLE IF NOT EXISTS annotations (
  id         INTEGER PRIMARY KEY,
  symbol_id  INTEGER NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,         -- simple name as written
  fqn        TEXT,                  -- resolved if symbol solver could
  args_json  TEXT                   -- compact JSON of arg name->literal
);
CREATE INDEX IF NOT EXISTS idx_ann_symbol ON annotations(symbol_id);
CREATE INDEX IF NOT EXISTS idx_ann_name   ON annotations(name);

CREATE TABLE IF NOT EXISTS imports (
  id          INTEGER PRIMARY KEY,
  file_id     INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
  fqn         TEXT NOT NULL,
  is_static   INTEGER NOT NULL DEFAULT 0,
  is_wildcard INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_imports_file ON imports(file_id);

-- The graph. src is always a resolved symbol_id (method/ctor for behaviour
-- edges, class for structural edges). dst is preferred as symbol_id; when
-- the parser cannot resolve, we keep (dst_fqn, dst_member) so the Linker
-- pass can match it later as more files become known.
CREATE TABLE IF NOT EXISTS edges (
  id             INTEGER PRIMARY KEY,
  repo_id        INTEGER NOT NULL,
  src_symbol_id  INTEGER NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,
  dst_symbol_id  INTEGER REFERENCES symbols(id) ON DELETE SET NULL,
  dst_fqn        TEXT,
  dst_member     TEXT,
  kind           TEXT NOT NULL,     -- EXTENDS, IMPLEMENTS, CALLS, STATIC_CALL,
                                    -- FIELD_READ, FIELD_WRITE, NEW,
                                    -- ANNOTATED_WITH, THROWS
  file_id        INTEGER REFERENCES files(id) ON DELETE CASCADE,
  line           INTEGER,
  resolution     TEXT NOT NULL DEFAULT 'unresolved',  -- ast | heuristic | unresolved
  param_types    TEXT                                  -- CALLS/STATIC_CALL/NEW: erased simple param types.
                                                       -- Empty string == no-arg. NULL == unknown.
);
CREATE INDEX IF NOT EXISTS idx_edges_src_kind  ON edges(src_symbol_id, kind);
CREATE INDEX IF NOT EXISTS idx_edges_dst_kind  ON edges(dst_symbol_id, kind);
CREATE INDEX IF NOT EXISTS idx_edges_dst_fqn   ON edges(dst_fqn, dst_member, kind);
CREATE INDEX IF NOT EXISTS idx_edges_dst_ctor  ON edges(dst_fqn, kind, param_types);
CREATE INDEX IF NOT EXISTS idx_edges_file      ON edges(file_id);

CREATE TABLE IF NOT EXISTS index_status (
  repo_id        INTEGER PRIMARY KEY REFERENCES repos(id) ON DELETE CASCADE,
  state          TEXT NOT NULL DEFAULT 'IDLE',  -- IDLE | BUILDING | FAILED | COMPLETED
  phase          TEXT,              -- fine-grained: IDLE | SCANNING | PARSING | LINKING | DONE | ERROR
  files_total    INTEGER DEFAULT 0,
  files_changed  INTEGER DEFAULT 0,
  files_done     INTEGER DEFAULT 0,
  symbols_count  INTEGER DEFAULT 0,
  edges_count    INTEGER DEFAULT 0,
  started_at     INTEGER,
  finished_at    INTEGER,
  error          TEXT
);

CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT
);
INSERT OR IGNORE INTO meta(key, value) VALUES ('schema_version', '3');

-- Debug Intelligence Engine: every analysed failure leaves a row here so
-- future investigations can score "historical failure correlation". Keyed
-- by a normalised signature (exception_class + top frame fqn#method).
CREATE TABLE IF NOT EXISTS failure_history (
  id              INTEGER PRIMARY KEY,
  repo_id         INTEGER REFERENCES repos(id) ON DELETE CASCADE,
  signature       TEXT NOT NULL,    -- normalised: exceptionFqn|class#method
  exception_fqn   TEXT,
  top_class_fqn   TEXT,
  top_method      TEXT,
  file_path       TEXT,
  source          TEXT,             -- stack | log | test | manual
  occurred_at     INTEGER NOT NULL  -- epoch millis
);
CREATE INDEX IF NOT EXISTS idx_fh_sig   ON failure_history(signature);
CREATE INDEX IF NOT EXISTS idx_fh_repo  ON failure_history(repo_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_fh_class ON failure_history(top_class_fqn);
CREATE INDEX IF NOT EXISTS idx_fh_exc   ON failure_history(exception_fqn);
