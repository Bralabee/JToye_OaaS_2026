---
phase: 22-notifications-comms
plan: 01
subsystem: api
tags: [notifications, email, mime-multipart, whatsapp, spring-boot, configuration-properties, rfc8058, tdd]

# Dependency graph
requires:
  - phase: 21-onboarding-blocker-ux
    provides: onboarding.events outbox seam (unbound) that 22-04 will consume via this channel
provides:
  - NotificationChannel provider abstraction (email/webhook/whatsapp implement one contract, never throws)
  - NotificationMessage record (resolved recipient + rendered content; NO consent category — 22-02 owns that)
  - EmailChannel — MimeMessageHelper multipart/alternative sender with RFC 8058 one-click unsubscribe headers
  - EmailTemplateRenderer + RenderedEmail — {subject, html, text} seam per event family, both-audience variants (D-01)
  - RecipientRole enum (CUSTOMER/VENDOR audience axis, distinct from 22-02's consent category)
  - WhatsAppSmsChannel — INERT-by-default third channel (COMMS-07), fail-closed WARN no-op
  - NotificationProperties (notification.unsubscribe.*) + WhatsAppProperties (jtoye.whatsapp.*) — masked, config-injected
affects: [22-04 dispatch orchestration, 22-02 consent, webhook channel, future WhatsApp live-send (#208)]

# Tech tracking
tech-stack:
  added: []  # zero new deps — spring-boot-starter-mail (MimeMessageHelper) already present
  patterns:
    - "NotificationChannel provider seam: name()/enabled()/deliver(); deliver never throws (swallow+log)"
    - "MimeMessageHelper two-arg setText(text, html) => multipart/alternative + RFC 8058 List-Unsubscribe-Post: One-Click"
    - "Inline-styled HTML text-block templates + plain-text alternative via String.formatted (no Thymeleaf)"
    - "INERT-by-default channel: @ConfigurationProperties.configured() gate + AtomicBoolean warnedOnce one-time WARN no-op"
    - "Masked toString() on credential-bearing @ConfigurationProperties (KeycloakAdminProperties template)"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/notification/dispatch/NotificationChannel.java
    - core-java/src/main/java/uk/jtoye/core/notification/dispatch/NotificationMessage.java
    - core-java/src/main/java/uk/jtoye/core/notification/dispatch/EmailChannel.java
    - core-java/src/main/java/uk/jtoye/core/notification/dispatch/WhatsAppSmsChannel.java
    - core-java/src/main/java/uk/jtoye/core/notification/template/RenderedEmail.java
    - core-java/src/main/java/uk/jtoye/core/notification/template/RecipientRole.java
    - core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java
    - core-java/src/main/java/uk/jtoye/core/notification/NotificationProperties.java
    - core-java/src/main/java/uk/jtoye/core/notification/WhatsAppProperties.java
    - core-java/src/test/java/uk/jtoye/core/notification/dispatch/EmailChannelTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/dispatch/WhatsAppSmsChannelTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/template/EmailTemplateRendererTest.java
  modified:
    - core-java/src/main/resources/application.yml

key-decisions:
  - "RenderedEmail created in Task 1 (not Task 2) — NotificationMessage references it, so it must exist for Task 1 compileJava"
  - "Added RecipientRole enum {CUSTOMER, VENDOR} to satisfy the both-audience render contract — orthogonal to 22-02's consent NotificationCategory"
  - "Order path frozen (Pitfall 5, path A): EmailNotificationService + its SimpleMailMessage test untouched; all new events ride EmailChannel"
  - "Marked only COMMS-07 complete; COMMS-02 left pending (shared with 22-04, whose dispatch delivers its Mailhog/recipient acceptance)"

patterns-established:
  - "Provider-abstraction channel seam that the dispatcher (22-04) fans one event out to, no per-channel special-casing"
  - "Config-injected inert-by-default channel with masked-secret ConfigurationProperties (GLOBAL_RULE_6 + STRIDE T-22-01-01)"

requirements-completed: [COMMS-07]

# Metrics
duration: 11min
completed: 2026-07-15
---

# Phase 22 Plan 01: Notification Channel Seam Summary

**NotificationChannel provider abstraction + MimeMessageHelper multipart/alternative EmailChannel (branded HTML + plain-text, RFC 8058 one-click unsubscribe) + per-event EmailTemplateRenderer + INERT-by-default WhatsAppSmsChannel stub — the interface-first foundation the 22-04 dispatcher and future channels build on, with the working order-email path left completely frozen.**

## Performance

- **Duration:** ~11 min
- **Started:** 2026-07-15T02:05:57Z
- **Completed:** 2026-07-15T02:17Z
- **Tasks:** 3
- **Files modified:** 13 (12 created, 1 modified)

## Accomplishments
- Built the `NotificationChannel` seam (`name`/`enabled`/`deliver`, never-throws) + `NotificationMessage` carrier — deliberately owns NO consent category so 22-01/22-02/22-03 stay parallel-safe.
- `EmailChannel` sends `multipart/alternative` via `MimeMessageHelper.setText(text, html)` with RFC 8058 `List-Unsubscribe` + `List-Unsubscribe-Post: List-Unsubscribe=One-Click` headers; swallows `MailException`/`MessagingException` (order-path swallow contract).
- `EmailTemplateRenderer` returns `{subject, html, text}` per event family (order/onboarding/payment/refund + generic fallback), with branded J'Toye header/footer, unsubscribe footer link, and customer/vendor variants — no Thymeleaf, zero new deps.
- `WhatsAppSmsChannel` is a fail-closed WARN-no-op OFF by default (`jtoye.whatsapp.enabled=false`); enabling without creds is still a one-time WARN no-op, never a crash (COMMS-07 fully delivered).
- Two masked `@ConfigurationProperties` classes (`NotificationProperties`, `WhatsAppProperties`) + `application.yml` keys, all `${ENV:default}`, no literals (GLOBAL_RULE_6). Existing `notification.email.*` keys and `EmailNotificationService` untouched and green.

## Task Commits

Each task was committed atomically (TDD test → feat for behavior-adding tasks):

1. **Task 1: NotificationChannel + NotificationMessage + NotificationProperties/WhatsAppProperties + application.yml** — `197363b` (feat)
2. **Task 2: EmailTemplateRenderer + RenderedEmail + EmailChannel** — `31bc8b3` (test, RED) → `48e8520` (feat, GREEN)
3. **Task 3: WhatsAppSmsChannel INERT-by-default stub + tests** — `57a853f` (test, RED) → `c1ba301` (feat, GREEN)

**Plan metadata:** committed with this SUMMARY + STATE + ROADMAP + REQUIREMENTS.

## Files Created/Modified
- `notification/dispatch/NotificationChannel.java` - Provider abstraction interface (never-throws contract)
- `notification/dispatch/NotificationMessage.java` - Resolved recipient + rendered content record (no consent category)
- `notification/dispatch/EmailChannel.java` - MimeMessageHelper multipart/alternative sender + one-click unsubscribe headers
- `notification/dispatch/WhatsAppSmsChannel.java` - INERT-by-default third channel (COMMS-07)
- `notification/template/RenderedEmail.java` - {subject, html, text} record
- `notification/template/RecipientRole.java` - CUSTOMER/VENDOR audience enum
- `notification/template/EmailTemplateRenderer.java` - Per-event-family branded HTML + plain-text renderer
- `notification/NotificationProperties.java` - notification.unsubscribe.* (masked signing secret + base URL)
- `notification/WhatsAppProperties.java` - jtoye.whatsapp.* (masked creds + configured() gate)
- `application.yml` - Added notification.unsubscribe.* + jtoye.whatsapp.* config blocks (env-with-default)
- 3 unit test files (EmailChannelTest 4, EmailTemplateRendererTest 5, WhatsAppSmsChannelTest 4)

## Decisions Made
- **Path A (additive) for the order path:** `EmailNotificationService` + `EmailNotificationServiceTest` (SimpleMailMessage) left byte-for-byte untouched; all new events go through the new `EmailChannel`. Order test stays green (10/10).
- **RecipientRole is the audience axis, not consent:** kept separate from 22-02's `NotificationCategory` to preserve Wave-1 parallel-safety.
- **Only COMMS-07 marked complete:** COMMS-02's acceptance (correct-recipient assertions, stalled onboarding → Mailhog) is delivered by 22-04's dispatch; marking it now would be a false-green.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] RenderedEmail created in Task 1 instead of Task 2**
- **Found during:** Task 1 (NotificationMessage record)
- **Issue:** `NotificationMessage` (Task 1) has a `RenderedEmail email` field per the `<interfaces>` contract; Task 1's `compileJava` verify fails without `RenderedEmail`.
- **Fix:** Created the trivial, behaviorless `RenderedEmail` record in Task 1's commit. It is still one of the plan's listed files (Task 2 `<files>`), just committed one task earlier.
- **Files modified:** notification/template/RenderedEmail.java
- **Verification:** `:core-java:compileJava` exits 0.
- **Committed in:** `197363b` (Task 1 commit)

**2. [Rule 3 - Blocking] Added RecipientRole enum (not in files_modified)**
- **Found during:** Task 2 (EmailTemplateRenderer)
- **Issue:** Task 2 behavior requires "both-audience variants selected by a recipient-role arg," but no role type was listed in the plan's files.
- **Fix:** Added a self-contained `RecipientRole` enum {CUSTOMER, VENDOR} in the template package. It is the audience axis, orthogonal to 22-02's consent `NotificationCategory` — no cross-plan collision, Wave-1 parallel-safety preserved.
- **Files modified:** notification/template/RecipientRole.java
- **Verification:** EmailTemplateRendererTest audience-variant test passes (customer ≠ vendor html).
- **Committed in:** `48e8520` (Task 2 GREEN commit)

**3. [Rule 3 - Blocking] Adapted verify commands to the real Gradle layout**
- **Found during:** Task 1 verification
- **Issue:** The plan's verify used `cd core-java && ./gradlew`, but the wrapper lives at the repo root (multi-project build); `core-java` is a subproject and has no local `gradlew`. Build output is redirected to `build-local` (root-owned `build/` gotcha).
- **Fix:** Ran `./gradlew -p <repo-root> :core-java:<task>` for all compile/test verifications. No functional change to what is verified.
- **Files modified:** none (tooling invocation only)
- **Verification:** All plan verify tasks ran green under this invocation.
- **Committed in:** n/a (no code change)

**4. [Rule 1 - Correctness] Marked only COMMS-07 complete, not COMMS-02**
- **Found during:** State updates
- **Issue:** The plan frontmatter lists `requirements: [COMMS-02, COMMS-07]`, but COMMS-02 is also assigned to 22-04, and its REQUIREMENTS.md acceptance (per-recipient assertions, stalled onboarding → Mailhog) is only satisfiable once 22-04's dispatch lands. Marking it complete here would be a false-green (project anti-false-green doctrine).
- **Fix:** Marked only COMMS-07 (fully delivered by this plan's scaffold + tests) complete; left COMMS-02 pending for 22-04.
- **Files modified:** .planning/REQUIREMENTS.md
- **Verification:** COMMS-07 checkbox + traceability row updated; COMMS-02 remains pending.
- **Committed in:** plan metadata commit

---

**Total deviations:** 4 (3 Rule-3 blocking adaptations, 1 Rule-1 correctness). All necessary to make the plan executable and to avoid a false-green. No scope creep — every new artifact is within the plan's declared seam.

## Known Stubs

**WhatsAppSmsChannel `would_send` no-op (INTENTIONAL — COMMS-07 scaffold-only)**
- **File:** notification/dispatch/WhatsAppSmsChannel.java (`deliver` configured branch)
- **Reason:** Live WhatsApp/SMS send is explicitly OUT OF SCOPE this phase (CONTEXT deferred ideas; #208). The channel is a structural seam that is safe to ship OFF; when configured it logs a "would-send" INFO no-op instead of dispatching.
- **Resolved by:** Future milestone — live provider integration (#208), gated on real Twilio/WhatsApp Business creds + STOP handling. Not a blocker for Phase 22 (the seam + INERT behavior is the deliverable).

## Issues Encountered
None — planned work proceeded without problems. The RED phases failed for the correct reason (missing implementation classes) and GREEN passed on first implementation.

## User Setup Required
None for this plan. (Live email in prod is SES-over-SMTP config via existing `SMTP_*` env; WhatsApp stays inert until `WHATSAPP_ENABLED=true` + creds — both out of scope here.)

## Next Phase Readiness
- The channel seam is ready for **22-04** to consume: build `NotificationMessage`s from V46 outbox events, resolve recipients (D-04), gate on 22-02's consent + unsubscribe URL, and fan to `EmailChannel`/`WhatsAppSmsChannel`.
- `NotificationProperties.unsubscribe.signingSecret` is the inert HMAC seam 22-02/22-03 will key the stateless unsubscribe token on.
- No shared type is owned here, so 22-02 and 22-03 remain parallel-safe.

## Self-Check: PASSED

All 12 created source/test files + the SUMMARY exist on disk; all 5 task commits (197363b, 31bc8b3, 48e8520, 57a853f, c1ba301) are present in git history. Verification suite: EmailTemplateRendererTest 5/5, EmailChannelTest 4/4, WhatsAppSmsChannelTest 4/4, EmailNotificationServiceTest 10/10 (order-path regression guard). `EmailNotificationService.java` absent from the plan changeset.

---
*Phase: 22-notifications-comms*
*Completed: 2026-07-15*
