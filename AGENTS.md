# O11yLite project rules

## General Guidelines

Read the following file as it's relevant to all workflows: @README.md.

## Build/Test Commands

**Backend (Clojure):** Run from `backend/` directory
- `make test` - Run all tests (fail-fast)
- `make test-all` - Run all tests (no fail-fast)
- Single test: `clojure -M:test/env:test/run --focus o11ylite.integration.health-test/api-status-test`
- `make format-check` / `make format-fix` - Check/fix formatting with cljstyle

**Frontend (TypeScript):** Run from `frontend/` directory
- `npm run build` - TypeScript compile + Vite build
- `npm run lint` - ESLint check

## Code Style

**Clojure:**
- File header: `;; ---` section block with namespace and description
- Docstrings required on public functions; private fns use `-` prefix (e.g., `parse-json-body` -> `-parse-json-body`)
- Rich comment block `(comment ...)` at end of files for REPL experimentation
- Formatting enforced by cljstyle (2-space indent, kebab-case naming)
- Tests in `test/o11ylite/integration/` use fixture `(use-fixtures :each h/with-system)`
- Backend is all-in Java virtual thread.
- Avoid parameter lists with more than three or four positional parameters.
- Namespace splitting patterns:
  - **Child + facade** (`foo` → `foo.bar`): Parent re-exports child vars. Use for utility bags where consumers want one import (e.g., `test-helpers`).
  - **Sibling** (`foo` ↔ `foo-impl`): Parent wraps/uses sibling internally. Use when sibling is implementation detail (e.g., `query` uses `query-schema`).
- When using namespace alias, follow:
  - Make the alias the same as the namespace name with the leading parts removed.
  - Keep enough trailing parts to make each alias unique.


**TypeScript/React:**
- Functional components with default exports for pages
- Inline object types for props (e.g., `{ greeting: string }`)
- Imports: external libs first, then local, CSS last
- Use Inertia `<Link href="...">` for internal navigation, not `<a href="...">`
- Avoid unnecessary deep nesting in JSX, I believe the happy path is left-aligned.
- `components/ui/` is reserved for shadcn components installed via `npx shadcn add`; place custom components in `components/`

