## Deferred Items

- `docs/AI_CONTEXT.md:256` gives the cache key format as `products::{tenantId}::{productId}`
  status: resolved
  **Resolved by:** 38-16 Task 1. Line 256 now gives `v4:{region}::tenant:{tenantId}:{method}:{params}`, the format `CacheConfig` and `TenantAwareCacheKeyGenerator` produce.
  **Found by:** 38-09 (out of its scope: that plan changes no docs).
  **What:** the documented format was already wrong before Phase 38. The real key is `{region}::tenant:{tid}:{method}:{params}` (`TenantAwareCacheKeyGenerator`), and since 38-09 every region also carries the `v4:` format version, so the key is `v4:{region}::tenant:…`. Owner of the fix: the 38-16 docs pass.
- `mcp-server/src/tools/create-customer.ts:33-36` and `create-order.ts:36-39` say the OpenAPI snapshot's `required` array under-reports `name`/`email` and `items`
  status: resolved
  **Resolved by:** 38-16 Task 1. Both comments now say the springdoc 3.1.1 snapshot lists the fields in `required` (checked with jq: `["email","name"]` and `["items","shopId"]`). Comment-only.
  **Found by:** 38-14 (out of its scope: mcp-server is outside phase 38 per CONTEXT, and outside 38-14's files).
  **What:** both comments say "springdoc does not propagate @NotBlank/@NotEmpty to `required`". Since 38-14
  regenerated the snapshot on springdoc 3.1.1, it does: `CreateCustomerRequest.required` is `["email","name"]` and
  `CreateOrderRequest.required` is `["items","shopId"]`. The code is unaffected, because the Zod schemas keep the
  fields required, which matches the server. Only the comments' claim about the snapshot is now false. Owner of the
  fix: the 38-16 docs pass (comment-only; no behaviour change, and mcp-server's 61 vitest tests stay as they are).
