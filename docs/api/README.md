# OpenAPI Snapshot & Breaking-Change Gate (issue #97)

`openapi-snapshot.json` is the **reviewed** core-java API contract — a
normalized capture of `/v3/api-docs` as served by the full Spring context.
The `openapi-compat` CI job (`.github/workflows/ci-cd.yaml`) regenerates the
spec from source on every PR/push and fails the build when it no longer
matches this snapshot, so no API change (breaking or otherwise) lands without
a reviewable diff.

## How it works

| Piece | What it does |
|---|---|
| `OpenApiSnapshotTest` (`core-java/src/test/java/uk/jtoye/core/integration/`) | Boots the full context against a throwaway Testcontainers Postgres, fetches `/v3/api-docs` through MockMvc, normalizes it, and (in default `check` mode, part of `integrationTest`) asserts byte-equality with this snapshot. |
| `./gradlew :core-java:generateOpenApiSpec` | Writes the normalized spec to `core-java/build-local/openapi/openapi-current.json` without asserting anything. |
| `./gradlew :core-java:updateOpenApiSnapshot` | Rewrites `docs/api/openapi-snapshot.json` from current code. |
| `scripts/openapi-gate.sh` | Compares snapshot vs regenerated spec with a pinned [oasdiff](https://github.com/oasdiff/oasdiff) (v1.23.0, checksum-verified in CI): breaking change → fail with report; any other drift → fail with a regenerate instruction. |

**Normalization** (what makes the snapshot byte-stable): all JSON object keys
sorted, the environment-dependent `servers` block stripped, root `tags`
sorted by name, 2-space indent, `\n` line endings, trailing newline.

## I changed the API on purpose — what do I do?

```bash
./gradlew :core-java:updateOpenApiSnapshot
git add docs/api/openapi-snapshot.json   # commit in the SAME PR
```

The snapshot diff in your PR **is** the contract review: reviewers approve the
API change by approving that diff. This applies to intentional breaking
changes too — regenerating the snapshot is how you mark the break as reviewed.
Never edit the snapshot by hand.

## Running the gate locally (exactly what CI runs)

```bash
./gradlew :core-java:generateOpenApiSpec
OASDIFF=/path/to/oasdiff scripts/openapi-gate.sh   # or have oasdiff on PATH
```
