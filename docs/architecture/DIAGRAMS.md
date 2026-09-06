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

Counted claims in the cards (25 controllers, 41 services, 25 repositories,
64 migrations, 5 declared edge→core calls) were verified against `git ls-tree` at the
pinned revision. Nothing in the toolchain re-checks them; they go stale silently.

## Regenerating

Requires the `archify` skill (`npx skills add tt-a1i/archify -g`) and Node >= 18.

```bash
cd ~/.claude/skills/archify
WT=<path to this checkout>

node bin/archify.mjs validate architecture \
  "$WT/docs/architecture/edge-go.architecture.json" \
  --quality showcase --repo-root "$WT" --json

node bin/archify.mjs deliver architecture \
  "$WT/docs/architecture/edge-go.architecture.json" \
  "$WT/docs/architecture/edge-go.architecture.html" \
  --quality showcase --repo-root "$WT" --json

node bin/archify.mjs visual-check \
  "$WT/docs/architecture/edge-go.architecture.html" --json
```

`deliver` must exit 0 with all 9 artifact checks and 0 composition errors/warnings;
`visual-check` runs a real headless browser at 1440x900 and 2048x1320 in both themes
and must report `pass`. Its PNG/JSON sidecars are gitignored — they are evidence for
the run, not artifacts.

## Updating after the code moves

Re-pin `meta.repository.revision` to the new commit and re-run `validate`. Broken
citations fail loudly; **prose does not**, so re-read the labels and counts by hand.

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
