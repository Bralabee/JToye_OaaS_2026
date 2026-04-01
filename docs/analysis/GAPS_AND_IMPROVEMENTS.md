# Gaps, Discrepancies & Improvement Opportunities

> **Generated**: 2026-04-01  
> **Method**: Full codebase audit -- code vs docs vs config cross-referenced

---

## Discrepancies

| Area | Docs Claim | Code Reality | Severity | Status |
|------|-----------|-------------|----------|--------|
| ~~**Build version**~~ | ~~v1.1.0 (README badge)~~ | ~~`0.1.0-SNAPSHOT` (build.gradle.kts)~~ | ~~Medium~~ | FIXED 2026-04-01 |
| **Git tags** | v1.1.0 referenced in docs | Only `v0.1.0` tag exists | Medium | Open |
| ~~**Spring Boot version**~~ | ~~AI_CONTEXT.md says 3.3.4~~ | ~~build.gradle.kts has 3.4.2~~ | ~~Low~~ | FIXED 2026-04-01 |
| **Readiness score** | PROJECT_STATUS says 92/100 | README says 100/100 | Low | Open |
| **Edge rate limit config** | .env.example lists RATE_LIMIT_RPS/BURST | Code hardcodes 20/40 | Low | Open |

---

## Feature Gaps

### High Priority (Functional)

1. **RabbitMQ unused**: Provisioned in Docker Compose and K8s but zero application code references. Either wire it up or remove it to avoid confusion.

2. **WhatsApp webhook incomplete**: Signature verification works in edge-go but handler only logs -- no forwarding to Core API implemented.

3. **No pagination UI**: All frontend pages fetch `?size=100` with no page navigation, infinite scroll, or "load more". Will fail silently for tenants with >100 records.

4. **OrderDto excludes items**: `OrderMapper` intentionally omits `OrderItem` list. Frontend can't display order line items.

5. **No order update endpoint**: Orders can only be created and transitioned through states. Fields (customer, notes) can't be edited after creation.

6. **Financial transactions not linked to orders**: No FK from `FinancialTransaction` to `Order`, no automatic transaction creation on order completion.

### Medium Priority (Robustness)

7. **No token refresh**: NextAuth stores refresh token but doesn't implement silent rotation when access token expires. Long sessions will fail with 401.

8. **Customer ID not used in order creation**: Order entity has `customerId` FK but `CreateOrderRequest` takes denormalized name/email/phone. No customer lookup integration.

9. **No search/filter in frontend**: No text search, status filter, or date range filter on any dashboard page.

10. **No batch delete**: No bulk operations in any controller.

### Low Priority (Polish)

11. ~~**Version alignment**~~: FIXED 2026-04-01 -- build.gradle.kts aligned to 1.1.0, Spring Boot refs updated to 3.4.2.

12. ~~**AI_CONTEXT.md stale**~~: FIXED 2026-04-01 -- Updated to Spring Boot 3.4.2.

13. **Test page exposed**: `dashboard/test/page.tsx` is a debug page showing raw session data -- should be removed or gated.

14. **No dark mode toggle**: Tailwind config supports class-based dark mode but no UI toggle exists.

15. **No loading skeletons**: Pages show spinner during load rather than skeleton placeholders.

---

## Architecture Opportunities

### Short Term
- **Wire RabbitMQ**: Use for async order state change notifications, audit event publishing, or WhatsApp message forwarding
- **Add OrderItemDto**: Create a full order detail endpoint that includes items
- **Implement token refresh**: Add NextAuth token rotation callback

### Medium Term
- **Event-driven architecture**: Order state changes publish events to RabbitMQ, consumed by notification service
- **Real-time updates**: WebSocket or SSE for order status changes on dashboard
- **Reporting service**: Aggregate financial transactions for tenant reporting

### Long Term
- **Multi-region**: PostgreSQL read replicas with tenant-aware routing
- **Tenant onboarding**: Self-service tenant registration and provisioning
- **Payment integration**: Stripe/payment gateway integration with FinancialTransaction

---

## Documentation Gaps

1. No API contract/OpenAPI spec exported to docs (only available via running Swagger UI)
2. No sequence diagrams for key flows (auth, order creation, batch sync)
3. No ADR (Architecture Decision Records) documenting why RLS over schema-per-tenant
4. No runbook for incident response
5. No capacity planning guide

---

## Security Observations

All positive -- no critical security issues found:
- RLS enforcement verified at startup
- Non-superuser application role
- JWT validation with proper issuer/signature checks
- HMAC-SHA256 for webhook verification
- Rate limiting at multiple layers
- Non-root Docker containers
- Security headers in K8s ingress
- Audit trail on all domain entities

Minor notes:
- `NEXTAUTH_SECRET` has a default value in .env.local.example and docker-compose (should be randomized per environment)
- Keycloak admin password is `admin123` in all configs (acceptable for dev, not for staging/prod)
- Redis password visible in docker-compose.full-stack.yml (acceptable for local dev)
