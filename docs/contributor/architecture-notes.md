# Architecture notes

Where things live, beneath the `DEVELOPMENT.md` overview.

- Component system: `resources/system.edn` (Aero `#ig/ref`, reader in
  `o11ylite.system`); each component ns owns its
  `ig/init-key` / `ig/halt-key!`.
- Config split: static/env in `components/core_config.clj`
  (`core-config-defs`, `list-config`); runtime-mutable in
  `components/app_config.clj` (SQLite/KV).
- HTTP: Ring+Jetty on virtual threads (`components/web_server.clj`);
  route groups in `components/router.clj`.
- Migrations: migratus via `components/storage_init.clj`, table
  `_o11ylite_migrations`.
- Inertia: `inertia/core.clj` (page data), `inertia/middleware.clj`,
  `inertia/template.clj`.
