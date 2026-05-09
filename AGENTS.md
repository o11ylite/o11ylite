# O11yLite project rules

## General Guidelines

Read the following file as it's relevant to all workflows: @README.md.

## Build/Test Commands

**Backend (Clojure):** Run from `backend/` directory

- **With `clojure-eval` skill (PREFERRED):**
  - Read `backend/dev/user.clj` to understand the dev environment system.
  - Use REPL to start/stop individual components for ad-hoc testing.
  - Use REPL to run tests: `(require '[clojure.test :refer [run-tests]]) (run-tests 'ns-name)`
  - Use REPL to validate code changes compile correctly.
  - If REPL is not running, use `make repl` to start repl.

- **Without `clojure-eval` skill (fallback only):**
  - `make test` - Run all tests (fail-fast)
  - Single test: `clojure -M:test/env:test/run --focus o11ylite.integration.health-test/api-status-test`

**Frontend (TypeScript):** Run from `frontend/` directory
- `npm test` - Run tests powered by msw, React Testing Library.
- `npm run build` - TypeScript compile + Vite build
- `npm run lint` - ESLint check

**End-to-end / Browser verification:**

- If you have vision capability and `playwright` related agent skill available, you can do browser verification:
  - Find our dev site location by reading the output of this command: `./dev/dev-site-url`.
  - We have `opentelemetry-javaagent` enabled to continuously send data so no need to worry about seeding data.

## Code Style

**Clojure:**
- File header: `;; ---` section block with namespace and description
- Docstrings required on public functions; private fns use `-` prefix (e.g., `parse-json-body` -> `-parse-json-body`)
- Rich comment block `(comment ...)` at end of files for REPL experimentation
- Formatting enforced by cljstyle (2-space indent, kebab-case naming)
- Tests in `test/o11ylite/integration/` use fixture `(use-fixtures :each h/with-system)`
- **Cost of `with-system`:** The fixture starts and tears down the entire system (SQLite, DuckDB, HTTP server, gRPC server, etc.) for **every single `deftest`**. This is expensive. Two ways to manage this: (1) use `(use-fixtures :each (h/with-partial-system [:component/key]))` when a test only needs a subset of components, and (2) group related assertions into fewer `deftest` blocks using `testing` to amortize startup cost — one `deftest` with multiple `testing` sections is much cheaper than many single-assertion `deftest` blocks.
- Backend is all-in Java virtual thread.
- Avoid parameter lists with more than three or four positional parameters.
- Namespace splitting patterns:
  - **Child + facade** (`foo` → `foo.bar`): Parent re-exports child vars. Use for utility bags where consumers want one import (e.g., `test-helpers`).
  - **Sibling** (`foo` ↔ `foo-impl`): Parent wraps/uses sibling internally. Use when sibling is implementation detail (e.g., `query` uses `query-schema`).
- When using namespace alias, follow:
  - Make the alias the same as the namespace name with the leading parts removed.
  - Keep enough trailing parts to make each alias unique.


**Observability attribute keys (logs and spans):** Always namespaced. Use OTel semconv verbatim as a keyword where it applies (`:http.request.method`, `:server.port`, `:db.system`); otherwise `:o11ylite.<subsystem>.<key>` with snake_case tails (`:o11ylite.scheduler.job_name`), or top-level `:o11ylite.<key>` for cross-cutting attributes (`:o11ylite.dev_mode`). Applies to `mulog/log` kwargs and `span/with-span!` / `span/add-span-data!` attribute maps. Mulog internals (`:mulog/*`, `:log/*`, `:app-name`, `:version`) stay as-is.

**Reporting exceptions:** In `catch` blocks, prefer `(o11ylite.util.telemetry/report-error! ::event-name e extra-kvs...)` over hand-rolled `mulog/log` + `span/add-exception!`. It records the exception on both the mulog event (with OTel `:exception.*` semconv attrs) and the current span in one call.

**TypeScript/React:**
- Functional components with default exports for pages
- Inline object types for props (e.g., `{ greeting: string }`)
- Imports: external libs first, then local, CSS last
- Use Inertia `<Link href="...">` for internal navigation, not `<a href="...">`
- Avoid unnecessary deep nesting in JSX, I believe the happy path is left-aligned.
- `components/ui/` is reserved for shadcn components installed via `npx shadcn add`; place custom components in `components/`

## Agent Skill Maintenance

O11yLite ships an agent skill package in `skills/o11ylite/`. When you change a route, schema, or protocol that agents consume, update the matching file in that directory. `SKILL.md` is the entry point (keep it under 60 lines); `docs/*.md` files hold per-domain API reference.

## Special notes

### Stuck in parenthesis mismatch issue?

The command `clj-paren-repair` is installed on your path.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file
instead of attempting to fix them yourself. If the tool doesn't work,
report to the user that they need to fix the delimiter error manually.

The tool automatically formats files with cljfmt when it processes them.
