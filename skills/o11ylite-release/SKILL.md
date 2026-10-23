---
name: o11ylite-release
description: Cut and publish an O11yLite release through Buildkite
---

# Release O11yLite

Releases are cut from `main` by the Buildkite pipeline `o11ylite-ci` (org `zhming0`). Each main build runs the backend, frontend, and chart jobs, then waits at a manual `:rocket: Release` block.

## Cut the release

1. Open the latest passing `main` build at https://buildkite.com/zhming0/o11ylite-ci.
2. Unblock `:rocket: Release` (UI, API, or Buildkite MCP: `list_builds` → `list_jobs` → `unblock_job`). Unblocking runs the pipeline upload step for `.buildkite/pipeline.release.yml`.
3. Wait for all four release steps to pass:
   - `Calculate version` — CalVer `YYYY.M.D-1HHMMSS` in UTC; the leading `1` keeps the time from having leading zeros. Stored as `release-version` meta-data.
   - `Build & Push` — multi-arch image to `ghcr.io/o11ylite/o11ylite:<version>` and `:latest`. Takes about 10 minutes.
   - `Create GitHub release` — tags the build commit, pushes the tag, creates the release with generated notes.
   - `Package & Push Chart` — stamps the version into `chart/o11ylite/Chart.yaml`, pushes `oci://ghcr.io/o11ylite/charts/o11ylite:<version>`.
4. Get the version from the `Calculate version` log (`Version: ...`) or the new GitHub release.

Do not tag or create the GitHub release by hand; the pipeline owns both.

## Write the release note

Keep the generated PR list; it is the record of what shipped. Categorize it — for example Features, Fixes, Docs & developer workflow, Maintenance, Dependencies — and keep every merged PR. Include a Docker pull block and a full-changelog compare link.

**Highlights are opt-in.** Do not write the upbeat top section unless the maintainer explicitly asks for a release highlight. When asked, put it above the PR list; it complements the list, never replaces it.

```bash
gh release edit <version> --repo o11ylite/o11ylite --notes-file notes.md
```

## Verify

```bash
gh release view <version> --repo o11ylite/o11ylite
docker manifest inspect ghcr.io/o11ylite/o11ylite:<version>
helm show chart oci://ghcr.io/o11ylite/charts/o11ylite --version <version>
```

Report the release URL. If a step fails, read that job's log before retrying; a retry reuses the same version.
