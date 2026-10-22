# Interactive architecture diagrams

Two explorable diagrams, one per runtime. Open the `.html` in a browser — each is
self-contained (no network, no build step) and carries guided views, a legend,
per-node source badges, and PNG/SVG/WebM export.

| Diagram | Subject | Nodes / edges |
|---|---|---|
| `edge-go.architecture.html` | Request topology of the Go edge gateway | 12 / 12 |
| `core-java.architecture.html` | Tenant-isolation path through core-java | 12 / 12 |

The `.json` beside each one is the source of truth; the HTML is generated from it.
Edit the JSON, never the HTML.

## What the diagrams assert, and what is actually checked

Every node carrying a `SRC` badge cites a file and line in this repository, pinned to
the revision in `meta.repository.revision`. The renderer resolves those citations
against the git object at that revision and refuses to render if a path or line does
not exist there — a file present in the working tree but absent at the pinned commit
is rejected.

**That check certifies the cited files exist. It does not certify the diagram is
true.** Arrows, labels, sublabels and card text are authored prose. A diagram
claiming the wrong protocol, or citing a real file that has nothing to do with the
node it is attached to, renders and reports `verified: true` exactly as these do.
Treat the badges as "you can go read this", not as proof, and have someone who knows
the subsystem read the arrows.

Counted claims on the `core-java` diagram are **checked by the docs-freshness claim gate**
(every pull request to main, every push to main, and pre-push P-4): the controller count
is bound to `java_controllers` and "schema at V…" to `schema_version` in
`docs/metrics.json`, by rules in `scripts/gates/claims.manifest`. All four places they
appear in the `.json` are bound. In the generated `.html` only the two node sublabels
(`data-node-sublabel`) are, which is enough to fail a JSON edit whose `deliver` was never
re-run, but not a hand edit of the HTML's other copies. Edit the JSON, never the HTML.
The subtitle's Spring Boot version has no metric and is not checked. Counts with no
metric behind them (services, repositories) were dropped rather than left to go stale, as
the earlier "25 controllers · 66 migrations" did against a tree at 33 and V88.
The `edge-go` card's "5 declared edge→core calls" is still unchecked.

## Regenerating

Requires the `archify` skill (`npx skills add tt-a1i/archify -g`) and Node >= 18.

archify 3.x requires `meta.output` in the JSON, resolved from the working directory, so
run from the root of this checkout (the `core-java` JSON carries
`"output": "docs/architecture/core-java.architecture.html"`; add the same to `edge-go`
when it is next re-pinned):

```bash
A=~/.claude/skills/archify/bin/archify.mjs   # run from the checkout root

node "$A" validate architecture docs/architecture/core-java.architecture.json \
  --quality showcase --repo-root . --json

node "$A" deliver architecture docs/architecture/core-java.architecture.json \
  docs/architecture/core-java.architecture.html \
  --quality showcase --repo-root . --json

node "$A" visual-check docs/architecture/core-java.architecture.html --json
```

`deliver` also writes a `*.delivery.json` receipt beside the HTML; it is run evidence,
not an artifact (gitignored).

`deliver` must exit 0 with all 9 artifact checks and 0 composition errors/warnings;
`visual-check` runs a real headless browser at 1440x900 and 2048x1320 in both themes
and must report `pass`. Its PNG/JSON sidecars are gitignored — they are evidence for
the run, not artifacts.

## Updating after the code moves

The docs-freshness workflow posts a `::notice` naming every cited file that changed
since a diagram's pin (`scripts/check-diagram-evidence-drift.sh`). It never blocks: most
such changes are unrelated to what the diagram draws. It compares the pin with HEAD, so
the same notices repeat on every PR until someone re-pins. When it fires for a change
that does matter, re-pin.

Re-pin `meta.repository.revision` to a commit **already on `origin/main`**. A
branch-local commit is orphaned by the squash merge, and the PR run cannot tell you: it
checks out the merge commit, which contains that commit. Only the push-to-main run after
the merge reports "not an ancestor". Re-point any cited line that moved, and re-run
`validate` and `deliver`. Broken citations fail loudly; **prose does not**, so re-read the
arrows and labels by hand. The current pins are the `meta.repository.revision` values in
the two JSON files. `edge-go` was not re-pinned on 2026-10-22, when `core-java` was,
because its router had moved (`gin.Default()` became `gin.New()` in `newRouter`) and it
needs that read first.

To see what changed between two snapshots, keep the previous JSON and diff them:

```bash
node bin/archify.mjs compare architecture <base>.json <head>.json out.html \
  --quality showcase --repo-root "$WT" --json
```

That reports added/removed/changed/moved components and added/removed/rerouted
connections, and renders a Before / Delta / After view.

## Known gaps in the current pair

- `core-java` omits WebSocket/STOMP, the media upload and quarantine pipeline, and
  the GDPR, audit/Envers, onboarding and geo modules.
- `edge-go` shows the request path only; the OpenAPI/docs routes and the
  `ForwardWebhook` helper (declared `Unrouted` — no core endpoint exists) are not drawn.
