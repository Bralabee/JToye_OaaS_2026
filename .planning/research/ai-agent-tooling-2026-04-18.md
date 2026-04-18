# AI Agent Tooling Research — 2026-04-18

**Audience:** J'Toye OaaS maintainers (Next.js 16 + React 19 + Tailwind + Radix | Spring Boot 3.4 | Go 1.22 | Postgres 15 RLS).
**Goal:** Identify today's highest-leverage tools, repo conventions, and workflows for (1) frontend design quality, (2) implementation efficiency, (3) AI code accuracy, (4) delivery quality — all grounded in evidence from active projects.

---

## 1. TL;DR — Top 5 moves

1. **Install shadcn MCP + Playwright MCP + Context7 MCP today.** These three cover 80% of frontend agent pain: live component registry access, real-browser click-through validation, and version-correct framework docs. Each installs in one CLI call. Zero infra. ([shadcn/ui MCP](https://ui.shadcn.com/docs/mcp), [microsoft/playwright-mcp — 31k stars](https://github.com/microsoft/playwright-mcp), [upstash/context7](https://github.com/upstash/context7))
2. **Adopt a minimal `AGENTS.md` alongside your existing `CLAUDE.md`.** AGENTS.md is the Linux Foundation–stewarded open standard read by Codex, Cursor, Aider, Continue, OpenHands, and (as fallback) Claude Code — 60k+ repos already use it; `next.js` symlinks `CLAUDE.md → AGENTS.md`. ([AGENTS.md spec](https://agents.md/), [vercel/next.js AGENTS.md](https://github.com/vercel/next.js/blob/canary/AGENTS.md))
3. **Put a writer/reviewer two-session loop on every non-trivial change.** Anthropic's own guidance and practice: one Claude writes, a second Claude (fresh context) reviews. 2–3× quality improvement reported. Pair with `/security-review` and the `anthropics/claude-code-security-review` GitHub Action for PRs. ([Claude Code best practices — Anthropic](https://code.claude.com/docs/en/best-practices), [anthropics/claude-code-security-review — 4.3k stars](https://github.com/anthropics/claude-code-security-review))
4. **Ground UI work in a *hand-crafted* registry, not generic shadcn defaults.** Pull from Aceternity UI, Magic UI, Launch UI, or Tailwind Plus + use `tweakcn` to derive a real theme (even from a reference image). This is the documented antidote to "generic AI aesthetic." ([Aceternity UI](https://ui.aceternity.com), [Magic UI](https://magicui.design), [Launch UI](https://www.launchuicomponents.com/), [tweakcn](https://tweakcn.com/), [Tailwind Plus](https://tailwindcss.com/plus))
5. **Move feature work onto git worktrees + `claude -w`.** Native worktree support shipped; Anthropic engineers routinely run 5–15 parallel sessions. This is the single biggest *throughput* lever for a solo dev. ([Common workflows](https://code.claude.com/docs/en/common-workflows), [Parallel worktrees guide](https://claudefa.st/blog/guide/development/worktree-guide))

---

## 2. MCP Server Matrix

| Name | Purpose | Repo / URL | Install | Fit | Notes |
|---|---|---|---|---|---|
| **shadcn MCP** | Live access to every shadcn registry component, block, hook, icon; installs via natural language | [ui.shadcn.com/docs/mcp](https://ui.shadcn.com/docs/mcp) · [Jpisnice/shadcn-ui-mcp-server](https://github.com/Jpisnice/shadcn-ui-mcp-server) | `pnpm dlx shadcn@latest mcp init --client claude` | **High** | Stops Claude guessing shadcn props. Supports multi-registry. Pro registry gate for some blocks. |
| **Playwright MCP (Microsoft)** | Real browser automation via accessibility trees (not screenshots); 60+ tools | [microsoft/playwright-mcp](https://github.com/microsoft/playwright-mcp) (**31k stars**) | `claude mcp add playwright npx @playwright/mcp@latest` | **High** | Canonical implementation. Used by GitHub Copilot Coding Agent to verify its own UI work. Complements your existing `webapp-testing` skill. |
| **Chrome DevTools MCP** | DevTools-level debugging: network, console, LCP/CLS, DOM/CSS inspection, performance traces | [Chrome blog](https://developer.chrome.com/blog/chrome-devtools-mcp) | `npx chrome-devtools-mcp@latest` in `.mcp.json` | **High** | Complementary to Playwright. Playwright = drive, DevTools = debug. Both together = best-in-class. ([Kinney comparison](https://stevekinney.com/writing/driving-vs-debugging-the-browser)) |
| **Context7 (Upstash)** | Injects up-to-date, version-specific docs for 30+ frameworks directly into prompts | [upstash/context7](https://github.com/upstash/context7) | Remote MCP: `https://mcp.context7.com/mcp` + API key | **High** | Next.js 16.2 / React 19 / Spring Boot 3.4 are all post-training-cutoff for most models — Context7 pays for itself immediately. Say "use context7" or auto-invoke. ([Upstash blog](https://upstash.com/blog/context7-llmtxt-cursor)) |
| **Figma Dev Mode MCP** | "Code to Canvas" — read Figma frames, variables, tokens, components from your selected layer; write back edits (remote) | [figma/mcp-server-guide](https://github.com/figma/mcp-server-guide) · [Figma blog](https://www.figma.com/blog/introducing-claude-code-to-figma/) | Enable in Figma desktop → `http://127.0.0.1:3845/sse` → `/mcp` in Claude Code → Authenticate | **Med-High if design in Figma** | Best-in-class design grounding. Only worth adding if you have actual Figma files (even lightweight mockups) — otherwise skip. |
| **21st.dev Magic UI MCP** | Generate animated UI components on demand from natural language | [21st-dev/magic-mcp](https://github.com/21st-dev/magic-mcp) · [21st.dev/magic](https://21st.dev/magic) | `npx -y @21st-dev/magic@latest` | **Med** | Great for marketing/landing surfaces. Lower value for dashboards/KDS. |
| **Sentry MCP** | Query issues, traces, events from Sentry; includes `sentry-mcp` subagent | [getsentry/sentry-mcp](https://github.com/getsentry/sentry-mcp) | Remote: `https://mcp.sentry.dev/mcp` | **Med** (if you add Sentry) | You don't appear to have Sentry yet; adding it + MCP would give triage automation. |
| **GitHub MCP** | PR/issue/comment/CI operations from inside Claude | [github/github-mcp-server](https://github.com/github/github-mcp-server) | Remote or local | **Med** | Claude already handles `gh` CLI well; MCP adds value mainly for bulk PR/issue workflows. |
| **Supabase MCP** | SQL/schema/migration against Supabase Postgres | [supabase-community/supabase-mcp](https://github.com/supabase-community/supabase-mcp) | Docs: [supabase.com/docs/guides/getting-started/mcp](https://supabase.com/docs/guides/getting-started/mcp) | **Low** | You run your own Postgres + Flyway, not Supabase. **Skip.** |
| **Sequential Thinking MCP** | Structured step-by-step reasoning scaffold | [modelcontextprotocol/servers/sequentialthinking](https://github.com/modelcontextprotocol/servers/tree/main/src/sequentialthinking) | `npx @modelcontextprotocol/server-sequential-thinking` | **Low** (now) | Redundant with Opus 4.7's native extended thinking. Was useful 2025, less so today. |
| **Filesystem MCP** | Generic file access outside project | [MCP servers](https://github.com/modelcontextprotocol/servers) | `npx @modelcontextprotocol/server-filesystem <path>` | **Low** | Claude Code has superior native file tools. Only needed for out-of-repo dirs. |
| **Linear MCP** (official remote) | Ticket CRUD, cycle planning, chaining with Sentry | [linear.app/docs/mcp](https://linear.app/docs/mcp) | Remote MCP | **Med if you use Linear** | Strong chain: Sentry issue → Linear ticket → PR, all in one sentence. Skip if not on Linear. |
| **BrowserStack / Percy MCP** | Visual regression + AI Visual Review Agent (bounding boxes, 40% noise-filter) | [BrowserStack MCP docs](https://www.browserstack.com/docs/browserstack-mcp-server/tools/percy) | Per BrowserStack setup | **Med** | High-leverage if you want visual regression without owning the infra; paid service. |

**Recommendation for this project:** shadcn + Playwright + Chrome DevTools + Context7 + Figma (if any design asset exists). That's the core loadout.

---

## 3. Repo Conventions & Memory

### 3.1 The emerging convention stack

Four files do the work, with overlap handled via symlinks:

| File | Audience | Status |
|---|---|---|
| `AGENTS.md` | Codex, Cursor, Aider, Continue, OpenHands, Gemini CLI, Amp, **Claude Code fallback** | Linux Foundation open standard, 60k+ repos ([agents.md](https://agents.md/)) |
| `CLAUDE.md` | Claude Code primary | Anthropic's convention; `next.js` symlinks this to `AGENTS.md` ([ref](https://github.com/vercel/next.js/discussions/85033)) |
| `.cursor/rules/*.mdc` | Cursor | Project-scoped, Cursor-specific |
| `.claude/skills/*/SKILL.md` | Claude Code | Progressive-disclosure domain packs ([Anthropic docs](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)) |

Best practice for 2026: **one AGENTS.md, symlink `CLAUDE.md → AGENTS.md`**, keep it under 300 lines (ideally <60), use `@path/to/file` imports for everything task-specific, move domain knowledge into skills. ([HumanLayer — Writing a good CLAUDE.md](https://www.humanlayer.dev/blog/writing-a-good-claude-md))

### 3.2 What to put in it (evidence-based)

Anthropic's official guidance ([Best Practices](https://code.claude.com/docs/en/best-practices)) says **include**:

- Bash commands Claude can't guess (build, test, lint, how to run a single test)
- Code style rules that differ from defaults
- Repository etiquette (branch naming, PR conventions, commit message format)
- Architectural decisions specific to the project
- Developer environment quirks (required env vars, port conflicts like your 3100)
- Common gotchas / non-obvious behaviors

**Exclude** (explicit anti-patterns):

- Anything Claude can figure out by reading code
- Standard language conventions
- Long API documentation (link via Context7 instead)
- Information that changes frequently
- File-by-file codebase descriptions
- Self-evident practices ("write clean code")

Heuristic from Anthropic: *"For each line, ask — would removing this cause Claude to make mistakes? If not, cut it. Bloated CLAUDE.md files cause Claude to ignore your actual instructions."*

### 3.3 Exemplar public repos to study

| Repo | Why it's good | Link |
|---|---|---|
| **vercel/next.js** | Canonical example. Single AGENTS.md symlinked to CLAUDE.md. Points at bundled `node_modules/next/dist/docs/`. Strict anti-patterns section. Monorepo-aware. | [AGENTS.md](https://github.com/vercel/next.js/blob/canary/AGENTS.md) |
| **github/spec-kit** (89k stars) | Shows spec-driven workflow embedded in commands | [github/spec-kit](https://github.com/github/spec-kit) |
| **anthropics/skills** | Anthropic's official skills — template for SKILL.md structure | [anthropics/skills](https://github.com/anthropics/skills) |
| **wshobson/agents** (33.8k stars) | Production-grade mega-pack: 184 agents, 150 skills, 98 commands, 78 plugins | [wshobson/agents](https://github.com/wshobson/agents) |
| **hesreallyhim/awesome-claude-code** (39.3k stars) | Curated index of everything CLAUDE.md / skill / hook / subagent | [awesome-claude-code](https://github.com/hesreallyhim/awesome-claude-code) |
| **VoltAgent/awesome-claude-code-subagents** | 100+ specialised subagents — good starting material | [VoltAgent](https://github.com/VoltAgent/awesome-claude-code-subagents) |
| **Piebald-AI/claude-code-system-prompts** | Every internal Claude Code prompt (for reverse-engineering what Claude already knows so you *don't* repeat it) | [Piebald-AI](https://github.com/Piebald-AI/claude-code-system-prompts) |

### 3.4 The minimal "new repo" starter

```
AGENTS.md              # 40-80 lines, linked below
CLAUDE.md              # symlink → AGENTS.md
.claude/
  skills/              # domain/workflow packs (SKILL.md each)
  agents/              # subagents (reviewer, security-scanner, test-writer)
  commands/            # slash-commands (/review, /ship)
  settings.json        # hooks, permissions, plugin enables
.cursor/rules/         # only if team uses Cursor too
```

For this project you're already ahead on this — you have GSD, CARL, and a healthy `.claude/` layout. The gap is mainly AGENTS.md + pruning CLAUDE.md.

---

## 4. Frontend Design Quality — killing AI slop

The "generic AI aesthetic" (centered hero, gradient overlay, emoji bullet cards, pastel dark-mode) is a documented problem. ([Anna Arteeva — Medium](https://annaarteeva.medium.com/why-designers-should-care-about-tailwind-and-shadcn-especially-in-the-ai-era-55b744c42603)) The antidote stack:

### 4.1 Ground in a real design system, not defaults

- **Hand-crafted registries** (not generated) — these explicitly market themselves as "not AI-generated":
  - [Launch UI](https://www.launchuicomponents.com/) — shadcn-compatible, hand-designed blocks.
  - [Tailwind Plus](https://tailwindcss.com/plus) — 500+ official expertly-crafted components (the Tailwind team).
  - [Layouts.dev](https://layouts.dev/) — "production-grade HTML and React, no AI, no hallucinations."
  - [Aceternity UI](https://ui.aceternity.com) — 100+ animated Framer Motion components; shadcn-registry compatible.
  - [Magic UI](https://magicui.design) — design-engineer-grade animated blocks, has its own MCP.
- **`shadcn MCP`** lets Claude install any of the above by name. No copy-paste.
- **registry.directory** ([link](https://registry.directory)) — browse 40+ shadcn registries; install the ones that match your brand.

### 4.2 Own your theme — don't use shadcn defaults

- **[tweakcn](https://tweakcn.com/)** — visual theme editor for shadcn. AI generator at [tweakcn.com/ai](https://tweakcn.com/ai) accepts **images** (brand photos, moodboards) and produces full Tailwind v4 CSS variables. Exports raw CSS + `globals.css` patch. Supports WCAG contrast check. This single step kills the "looks like every shadcn project" complaint.
- **Style Dictionary → Tailwind** — if you later adopt Figma Tokens, the pipeline is well-trodden ([xtivia example](https://github.com/xtivia/design-token-tailwind-example), [StefanKandlbinder bridge](https://github.com/StefanKandlbinder/styledictionarytailwindbridge)).

### 4.3 Reference-image injection

Claude 4+ is multimodal. Evidence-backed patterns from Anthropic's docs ([Vision docs](https://platform.claude.com/docs/en/build-with-claude/vision)):

- Paste screenshots with Cmd+V directly into Claude Code ([amanhimself](https://amanhimself.dev/blog/using-images-in-claude-code/)).
- "Images before text" rule — drop the reference first, then the prompt.
- Use the Files API for images referenced repeatedly (e.g., brand logo / style guide page) to avoid re-encoding cost.
- Anthropic's verbatim recommendation: *"`[paste screenshot]` implement this design. Take a screenshot of the result and compare it to the original. List differences and fix them."* ([best practices](https://code.claude.com/docs/en/best-practices))

### 4.4 Browser-loop validation (the single biggest UI-quality lever)

Anthropic explicitly calls this out: *"UI changes can be verified using the Claude in Chrome extension."*

Layered approach:

1. **Drive** — Playwright MCP (accessibility snapshots, fast, reliable for click-through tests).
2. **Debug** — Chrome DevTools MCP (network, console, LCP, DOM — when something renders wrong).
3. **Test** — your existing `webapp-testing` skill.
4. **Visual regression** — three tiers:
   - **Tier 1 (free):** Playwright `toMatchSnapshot()` — baseline in-repo. Good enough for solo work.
   - **Tier 2 (Storybook):** [Chromatic](https://www.chromatic.com/) — component-level VRT, now explicitly AI-code-generation-friendly ([Storybook for AI](https://storybook.js.org/ai)).
   - **Tier 3 (SaaS):** [Percy](https://www.browserstack.com/percy/ai-agents) with AI Visual Review Agent — filters ~40% of noise, bounding-box diff summaries.

For this project: start at Tier 1. Add Storybook + Chromatic when the storefront/KDS stabilise. Skip Percy for now.

### 4.5 Mobile-first testing pattern

Playwright device emulation is first-class ([Playwright mobile testing guide](https://playwright.dev/docs/emulation)). Required pattern:

```ts
test.use({ ...devices['iPhone 15 Pro'] });
test.use({ ...devices['Pixel 8'] });
test.use({ viewport: { width: 390, height: 844 } });
```

Have subagents run mobile + desktop tests *in parallel* — they're independent.

### 4.6 Prompt patterns that reduce AI aesthetic

Evidence-backed from [Builder.io — 50 Claude Code tips](https://www.builder.io/blog/claude-code-tips-best-practices) and Anthropic's teams-use-Claude-Code PDF:

- **Show, don't describe.** Paste 2–3 reference screenshots, say "match this feel."
- **Forbid list.** "Do not use gradient backgrounds, emoji bullets, centered hero layouts, or generic placeholder imagery."
- **Constrain the palette.** Paste the actual tweakcn CSS vars — no vibes-based colour picking.
- **Describe grid and rhythm explicitly.** "8px grid, 1.25 type scale, line-height 1.5, max content width 72ch."
- **Verify then iterate.** "Screenshot the result. Compare to the reference. List three differences. Fix them."

---

## 5. Accuracy Multipliers

### 5.1 Spec-first / Spec-driven development

**[github/spec-kit](https://github.com/github/spec-kit)** (89.1k stars, v0.7.3 Apr 17 2026) formalises the pattern:

```
/speckit.constitution  → project principles
/speckit.specify       → requirements + user stories
/speckit.clarify       → probe underspecified areas
/speckit.plan          → technical plan with chosen stack
/speckit.tasks         → ordered atomic tasks
/speckit.implement     → execute
/speckit.analyze       → cross-artifact validation
```

Supports 30+ agents. ([GitHub Blog launch](https://github.blog/ai-and-ml/generative-ai/spec-driven-development-with-ai-get-started-with-a-new-open-source-toolkit/), [Microsoft Learn module](https://learn.microsoft.com/en-us/training/modules/spec-driven-development-github-spec-kit-enterprise-developers/))

The accuracy math (Anthropic's own framing): *if Claude is right 80% per decision and a feature has 20 decisions, first-pass success is ~1%. Planning collapses those 20 into 1 reviewed spec where each lands near 100%.* ([best practices](https://code.claude.com/docs/en/best-practices))

**This project already has GSD with `/gsd-spec-phase`, `/gsd-discuss-phase`, `/gsd-plan-phase` which maps 1:1 onto the spec-kit idea.** You're covered — the question is whether to cross-pollinate spec-kit templates.

### 5.2 Test-gated generation (TDD)

Key finding: *Claude naturally writes implementation first; TDD requires explicit inversion.* ([alexop.dev](https://alexop.dev/posts/custom-tdd-workflow-claude-code-vue/), [The New Stack](https://thenewstack.io/claude-code-and-the-art-of-test-driven-development/))

Practical pattern that works:

1. Ask Claude to write the test file first. No implementation.
2. Run it. Confirm it fails.
3. Ask a **separate subagent** to write implementation to pass the test.
4. A **third subagent** verifies the implementation isn't "overfitting" to the specific test.

The multi-subagent split matters — a single agent will rewrite its own test to match bad code.

### 5.3 Reviewer subagents

Anthropic's canonical pattern ([subagents docs](https://code.claude.com/docs/en/sub-agents)):

```
planner (product-manager) → ticket
  executor (senior-software-engineer) → code
    reviewer (code-reviewer / security-reviewer) → feedback
      loop until approved
```

Parallel is the unlock — run `style-checker + security-scanner + test-coverage` concurrently during review; Anthropic reports this "reduces review time from minutes to seconds."

Concrete wins for this project:

- **`/security-review` slash command** (native, works today).
- **`anthropics/claude-code-security-review` GitHub Action** — add one workflow file, get Claude-powered security comments on every PR. 4.3k stars, Python-based. ([repo](https://github.com/anthropics/claude-code-security-review))
- **`/review` command** for PRs (native).

### 5.4 Grounding tools

| Tool | What it grounds | Link |
|---|---|---|
| Context7 MCP | Framework docs (live, version-aware) | [upstash/context7](https://github.com/upstash/context7) |
| Aider repo-map | Symbol graph across codebase (PageRank-ranked) | [aider.chat/docs/repomap](https://aider.chat/docs/repomap.html) |
| shadcn MCP | Component registry | [shadcn docs](https://ui.shadcn.com/docs/mcp) |
| Figma MCP | Design tokens/frames | [figma/mcp-server-guide](https://github.com/figma/mcp-server-guide) |
| `/init` command | Generates a first-pass CLAUDE.md | native |

Note: Claude Code's internal repo understanding is already strong (better than Aider's repo-map for most tasks), so **don't bolt on Aider** — use its ideas conceptually.

### 5.5 Cross-AI peer review

You already have `/gsd-review` for cross-AI review. This is a documented efficient-markets trick: Cursor agents critiquing Claude's output catches blind spots that single-model review misses. Keep it.

---

## 6. Efficiency Multipliers

### 6.1 Git worktrees + parallel sessions

Native support: `claude -w` creates `.claude/worktrees/<name>/` on a branch, opens a scoped session. ([common workflows](https://code.claude.com/docs/en/common-workflows), [parallel-worktrees skill](https://github.com/spillwavesolutions/parallel-worktrees))

Guidance from Anthropic teams:

- 2–4 parallel sessions is the sustainable ceiling before review overhead dominates.
- Top practitioners run 5–15 (Boris/Anthropic engineers — 5 terminal tabs + 5–10 web sessions).
- Per-worktree CLAUDE.md for task-specific context.
- Add `.claude/worktrees/` to `.gitignore`.

You already know this via your `feedback_worktree_merge.md` — the gotcha is cherry-pick instead of merge due to the main-branch-base issue.

### 6.2 Background agents

| Option | Strengths | Weaknesses |
|---|---|---|
| **Claude Code GitHub Actions** ([docs](https://code.claude.com/docs/en/github-actions)) | Issue→PR triage, fix failing tests, respond to comments. Headless, event-driven, works in any CI. | Needs Anthropic API key in CI. |
| **`anthropics/claude-code-action`** ([repo](https://github.com/anthropics/claude-code-action)) | Canonical action for GH workflows. | — |
| **`erans/autoagent-action`** ([repo](https://github.com/erans/autoagent-action)) | Multi-agent (Claude + Cursor + Gemini + Codex + Amp) orchestrated from PR comments. | Less polished. |
| **Cursor 3 background agents** | IDE-initiated parallel agents; good for Cursor-first teams. | Not triggerable from infra events. ([InfoQ — Cursor 3](https://www.infoq.com/news/2026/04/cursor-3-agent-first-interface/)) |
| **Coder Tasks** | Fully managed issue→PR on Coder infra. | Paid platform. |

For this project: start with Claude Code GitHub Action for nightly audit + PR security review. Skip anything that requires moving off your local Docker stack.

### 6.3 Hooks — what to automate

Twelve lifecycle events available ([hooks guide](https://code.claude.com/docs/en/hooks-guide), [disler/claude-code-hooks-mastery](https://github.com/disler/claude-code-hooks-mastery)). High-ROI defaults:

| Hook | Use |
|---|---|
| `SessionStart` | Inject current branch, last test pass/fail, env state. **<1s runtime** — keep light. |
| `PostToolUse` (matcher `Edit\|Write`) | Run prettier/biome/google-java-format on touched files. |
| `PostToolUse` (matcher `Write`) | Block writes to `.env`, `V33__*.sql`, `migrations/` unless explicitly approved. |
| `Notification` | Desktop notification when Claude needs input (fixes "I forgot Claude was running"). |
| `Stop` | Run `git status` + `pnpm typecheck` summary into stdout so you don't have to ask. |

Avoid: PostToolUse hooks that themselves edit files (cascading trigger loops).

### 6.4 Prompt caching + session resume

- `claude --continue` / `claude --resume` — server-side cache hits ~90–96% when prefix matches. ([Claude Code Camp](https://www.claudecodecamp.com/p/how-prompt-caching-actually-works-in-claude-code))
- Handoff protocol: a proper handoff costs ~2k tokens vs 10k+ for naive re-explanation. ([Black Dog Labs — handoff protocol](https://blackdoglabs.io/blog/claude-code-decoded-handoff-protocol))
- `thepushkarp/handoff` Claude Code plugin automates this. ([repo](https://github.com/thepushkarp/handoff))
- Your CARL HANDOFF domain + `HANDOFF.md` already implements this pattern — keep.

### 6.5 Plugins & marketplaces

`/plugin` marketplace is live ([discover plugins](https://code.claude.com/docs/en/discover-plugins)). Official marketplace has ~101 plugins as of Mar 2026. ([claude-plugins-official](https://github.com/anthropics/claude-plugins-official))

High-signal picks reviewed in the wild ([Build to Launch — "10 tested, 4 kept"](https://buildtolaunch.substack.com/p/best-claude-code-plugins-tested-review), [aitmpl.com/plugins](https://www.aitmpl.com/plugins/)):

- **code-review** (official) — includes the `/code-review` command.
- **code-intelligence** — symbol-precise navigation + auto-error-detection after edits; strong for typed languages (Java, TS).
- **shadcn** registry plugin.
- **`EveryInc/compound-engineering-plugin`** — planner/executor/reviewer compound agent.

---

## 7. Project-Specific Adoption Plan

Grounded in your current state (Next.js 16 + React 19 + Tailwind + Radix + Spring Boot 3.4 + Go 1.22 + Postgres RLS; already running GSD + CARL + Claude skills; pain points = AI-looking UI, want mobile-first production-grade design, need click-through E2E).

### 7.1 Top 5 — Today (highest ROI, <1 hr each)

1. **Install `Context7` MCP** (30 min including API key).
   - *Why:* Next.js 16.2 and React 19 are post-training for Opus 4.7's most confident output. Spring Boot 3.4.2 likewise. Stops hallucinated imports immediately.
   - *Install:* remote MCP at `https://mcp.context7.com/mcp` + API key. Add to `.mcp.json`.

2. **Install `Playwright` MCP** (15 min).
   - *Why:* Your `feedback_e2e_click_through.md` says *"don't just check buttons exist — click them."* Playwright MCP makes click-through the default. Pairs with your existing `webapp-testing` skill.
   - *Install:* `claude mcp add playwright npx @playwright/mcp@latest`.

3. **Install `shadcn` MCP** (10 min).
   - *Why:* Your stack is shadcn-heavy. Live registry access + natural-language install + props grounded in real components = fewer hallucinated variants.
   - *Install:* `pnpm dlx shadcn@latest mcp init --client claude` from `frontend/`.

4. **Add `AGENTS.md` symlinked to `CLAUDE.md`** (15 min).
   - *Why:* Future-proofs against team members using Cursor/Codex/Aider. Your existing CLAUDE.md already qualifies — just symlink it.
   - *Command:* `cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && ln -s CLAUDE.md AGENTS.md && git add AGENTS.md`.

5. **Run `tweakcn` → generate a real OaaS theme, commit `globals.css`** (45 min).
   - *Why:* This is the single biggest "stop looking AI-generated" move. Pick a brand-relevant reference image (food, UK retail), upload to [tweakcn.com/ai](https://tweakcn.com/ai), export CSS vars, replace your current `globals.css`. Every subsequent Claude-generated UI inherits the theme automatically.

### 7.2 Top 5 — This milestone (v2.2 Production Hardening)

1. **Chrome DevTools MCP** for performance grounding.
   - *Why:* Vendor dashboards and KDS need real LCP/CLS data. DevTools MCP surfaces this. Pairs with production hardening milestone goal.

2. **`anthropics/claude-code-security-review` GitHub Action on PRs**.
   - *Why:* You're a multi-tenant UK retail platform — you need continuous security review. Low-effort adoption (one workflow file), gives you agent-driven security comments on every PR.

3. **Reviewer subagent per PR (local).**
   - *Why:* Implement a `.claude/agents/pr-reviewer.md` using the writer/reviewer pattern. You already run `/gsd-code-review`; formalise it as a required step before `/gsd-ship`.

4. **Mobile-first Playwright grid in CI** (iPhone 15 Pro + Pixel 8 + 1280x800 desktop).
   - *Why:* Your UI-quality standard is explicit about mobile-first production-grade. Cross-device matrix in CI turns that standard into a gate.

5. **Adopt a hand-crafted registry for marketing + KDS surfaces.**
   - *Why:* Aceternity UI for storefront/marketing (animated), Launch UI for dashboard chrome, keep base shadcn for forms/tables. Pull via `shadcn MCP`. Avoid the "every shadcn project" aesthetic.

### 7.3 Top 3 — Longer-term (milestone v2.3+)

1. **Storybook + Chromatic for component VRT.**
   - *Why:* Once storefront + vendor + KDS UIs stabilise, visual-regression at the component level beats page-level screenshots. Chromatic is explicitly AI-code-generation-aware.

2. **Figma Dev Mode MCP (once design assets exist).**
   - *Why:* If/when you hire a designer or source Figma templates, the code-to-canvas loop is revolutionary. Not worth setting up without real design files.

3. **Spec-kit cross-pollination with GSD.**
   - *Why:* Your GSD is a superset. Pull specific patterns from spec-kit — especially `/speckit.constitution` (project principles file) and `/speckit.analyze` (cross-artifact validation) — into your existing GSD skills. Don't replace, augment.

### 7.4 What NOT to adopt (evidence of low fit)

- **Supabase MCP** — you run your own Postgres + Flyway.
- **Sequential Thinking MCP** — redundant with Opus 4.7 extended thinking.
- **Aider** (as a tool) — Claude Code has better repo understanding; keep Aider's ideas, skip the binary.
- **Devin** / **Cursor 3 background agents** (exclusively) — you're on Claude Code; don't fragment.
- **Magic UI MCP** *everywhere* — great for marketing, wrong for dashboards; reach for it only on storefront/landing.
- **v0 / Lovable / Bolt** as production tools — useful for throwaway sketches (you already have `/gsd-sketch`); not where production code should live.

---

## 8. Sources

### Core docs
- [Anthropic — Claude Code Best Practices](https://code.claude.com/docs/en/best-practices)
- [Anthropic — How Anthropic teams use Claude Code (PDF)](https://www-cdn.anthropic.com/58284b19e702b49db9302d5b6f135ad8871e7658.pdf)
- [Anthropic — Hooks guide](https://code.claude.com/docs/en/hooks-guide)
- [Anthropic — Sub-agents](https://code.claude.com/docs/en/sub-agents)
- [Anthropic — Skills](https://code.claude.com/docs/en/skills)
- [Anthropic — Common workflows (worktrees)](https://code.claude.com/docs/en/common-workflows)
- [Anthropic — Plugins discovery](https://code.claude.com/docs/en/discover-plugins)
- [Anthropic — GitHub Actions](https://code.claude.com/docs/en/github-actions)
- [Anthropic — Vision / multimodal](https://platform.claude.com/docs/en/build-with-claude/vision)
- [Anthropic — Agent Skills spec](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)
- [Anthropic — Equipping agents for the real world](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- [Anthropic — Code Review blog (Mar 2026)](https://claude.com/blog/code-review)

### MCP servers
- [shadcn MCP](https://ui.shadcn.com/docs/mcp) · [Jpisnice/shadcn-ui-mcp-server](https://github.com/Jpisnice/shadcn-ui-mcp-server)
- [microsoft/playwright-mcp (31k stars)](https://github.com/microsoft/playwright-mcp)
- [Chrome DevTools MCP — Chrome blog](https://developer.chrome.com/blog/chrome-devtools-mcp)
- [upstash/context7](https://github.com/upstash/context7) · [Context7 blog](https://upstash.com/blog/context7-llmtxt-cursor)
- [Figma MCP guide](https://github.com/figma/mcp-server-guide) · [Figma: Code to Canvas blog](https://www.figma.com/blog/introducing-claude-code-to-figma/) · [Builder.io — Claude Code + Figma](https://www.builder.io/blog/claude-code-figma-mcp-server)
- [21st.dev Magic MCP](https://github.com/21st-dev/magic-mcp) · [21st.dev/magic](https://21st.dev/magic)
- [getsentry/sentry-mcp](https://github.com/getsentry/sentry-mcp) · [Sentry MCP blog](https://blog.sentry.io/yes-sentry-has-an-mcp-server-and-its-pretty-good/)
- [Sequential Thinking MCP](https://github.com/modelcontextprotocol/servers/tree/main/src/sequentialthinking)
- [Supabase MCP](https://github.com/supabase-community/supabase-mcp)
- [registry.directory](https://registry.directory) · [shadcn registry docs](https://ui.shadcn.com/docs/directory)

### Repo conventions
- [agents.md](https://agents.md/) · [OpenAI Codex AGENTS.md guide](https://developers.openai.com/codex/guides/agents-md)
- [vercel/next.js AGENTS.md](https://github.com/vercel/next.js/blob/canary/AGENTS.md)
- [Next.js AI Coding Agents guide](https://nextjs.org/docs/app/guides/ai-agents)
- [HumanLayer — Writing a good CLAUDE.md](https://www.humanlayer.dev/blog/writing-a-good-claude-md)
- [DeployHQ — AI coding config files](https://www.deployhq.com/blog/ai-coding-config-files-guide)

### Agent orchestration & parallelisation
- [wshobson/agents (33.8k stars)](https://github.com/wshobson/agents)
- [VoltAgent/awesome-claude-code-subagents](https://github.com/VoltAgent/awesome-claude-code-subagents)
- [hesreallyhim/awesome-claude-code (39.3k stars)](https://github.com/hesreallyhim/awesome-claude-code)
- [Shipyard — Multi-agent orchestration for Claude Code in 2026](https://shipyard.build/blog/claude-code-multi-agent/)
- [Anthropic Subagents SDK docs](https://platform.claude.com/docs/en/agent-sdk/subagents)

### Spec-driven & TDD
- [github/spec-kit (89k stars)](https://github.com/github/spec-kit)
- [GitHub Blog — spec-driven development launch](https://github.blog/ai-and-ml/generative-ai/spec-driven-development-with-ai-get-started-with-a-new-open-source-toolkit/)
- [Martin Fowler — SDD tools comparison](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)
- [alexop.dev — TDD red-green-refactor with Claude](https://alexop.dev/posts/custom-tdd-workflow-claude-code-vue/)
- [The New Stack — Claude Code and TDD](https://thenewstack.io/claude-code-and-the-art-of-test-driven-development/)

### Frontend design quality
- [Aceternity UI](https://ui.aceternity.com) · [Magic UI](https://magicui.design)
- [Launch UI](https://www.launchuicomponents.com/) · [Layouts.dev](https://layouts.dev/) · [Tailwind Plus](https://tailwindcss.com/plus)
- [tweakcn](https://tweakcn.com/) · [tweakcn AI](https://tweakcn.com/ai) · [jnsahaj/tweakcn](https://github.com/jnsahaj/tweakcn)
- [Creative Tim UI — shadcn blocks for AI](https://www.creative-tim.com/ui)
- [Anna Arteeva — Design systems ♡ Lovable/Bolt/v0](https://annaarteeva.medium.com/choosing-your-ai-prototyping-stack-lovable-v0-bolt-replit-cursor-magic-patterns-compared-9a5194f163e9)
- [Storybook for AI](https://storybook.js.org/ai)
- [Chromatic](https://www.chromatic.com/) · [Chromatic Storybook integration](https://www.chromatic.com/storybook)
- [Percy by BrowserStack (AI review agent)](https://www.browserstack.com/percy/ai-agents)

### Browser validation
- [Playwright Test Agents](https://playwright.dev/docs/test-agents)
- [Playwright codegen](https://playwright.dev/docs/codegen)
- [Steve Kinney — Driving vs Debugging the browser](https://stevekinney.com/writing/driving-vs-debugging-the-browser)
- [Test-Lab.ai — DevTools MCP vs Playwright MCP](https://www.test-lab.ai/blog/chrome-devtools-mcp-vs-playwright-mcp-cli)

### Efficiency & worktrees
- [claudefa.st — Worktree guide](https://claudefa.st/blog/guide/development/worktree-guide)
- [Tim Dietrich — parallel subagents](https://timdietrich.me/blog/claude-code-parallel-subagents/)
- [Dan Does Code — Parallel Vibe Coding](https://www.dandoescode.com/blog/parallel-vibe-coding-with-git-worktrees)
- [Dogukan Uraz Tuna — worktrees with Claude Code](https://medium.com/@dtunai/mastering-git-worktrees-with-claude-code-for-parallel-development-workflow-41dc91e645fe)
- [spillwavesolutions/parallel-worktrees](https://github.com/spillwavesolutions/parallel-worktrees)

### Security & review agents
- [anthropics/claude-code-security-review (4.3k stars)](https://github.com/anthropics/claude-code-security-review)
- [anthropics/claude-code-action](https://github.com/anthropics/claude-code-action)
- [Deriv — Automated security code reviews with Claude Code](https://derivai.substack.com/p/automated-security-code-reviews-claude-code-github-actions)
- [Anthropic — Code Review docs](https://code.claude.com/docs/en/code-review)

### Hooks & handoffs
- [disler/claude-code-hooks-mastery](https://github.com/disler/claude-code-hooks-mastery)
- [eesel AI — Hooks guide 2026](https://www.eesel.ai/blog/hooks-in-claude-code)
- [Pixelmojo — Hooks: 12 events with examples](https://www.pixelmojo.io/blogs/claude-code-hooks-production-quality-ci-cd-patterns)
- [thepushkarp/handoff](https://github.com/thepushkarp/handoff)
- [Black Dog Labs — Claude Code Handoff Protocol](https://blackdoglabs.io/blog/claude-code-decoded-handoff-protocol)
- [Claude Code Camp — How prompt caching actually works](https://www.claudecodecamp.com/p/how-prompt-caching-actually-works-in-claude-code)

### MCP lists & meta
- [claudefa.st — 50+ Best MCP Servers](https://claudefa.st/blog/tools/mcp-extensions/best-addons)
- [DeployHQ — Must-have MCP servers for web devs](https://www.deployhq.com/blog/6-must-have-mcp-servers-for-web-developers-in-2025)
- [Firecrawl — 10 Best MCP Servers](https://www.firecrawl.dev/blog/best-mcp-servers-for-developers)
- [Builder.io — Best MCP Servers 2026](https://www.builder.io/blog/best-mcp-servers-2026)
- [apidog — Top 10 MCP Servers for Claude Code](https://apidog.com/blog/top-10-mcp-servers-for-claude-code/)

### Grounding & repo mapping
- [aider.chat — repo map](https://aider.chat/docs/repomap.html)
- [RepoPrompt](https://repoprompt.com/)
- [Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts)

### Prompting & Builder.io guides
- [Builder.io — 50 Claude Code Tips](https://www.builder.io/blog/claude-code-tips-best-practices)
- [Builder.io — How I use Claude Code](https://www.builder.io/blog/claude-code)
- [Builder.io — Claude Code vs Cursor 2026](https://www.builder.io/blog/cursor-vs-claude-code)

---

**Report author:** Claude Opus 4.7 (research agent), based on 25+ WebSearch queries + 8 WebFetch deep-reads, 2026-04-18. No code modified.
