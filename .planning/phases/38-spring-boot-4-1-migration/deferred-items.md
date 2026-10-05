## Deferred Items

- `docs/AI_CONTEXT.md:256` gives the cache key format as `products::{tenantId}::{productId}`
  status: open
  **Found by:** 38-09 (out of its scope: that plan changes no docs).
  **What:** the documented format was already wrong before Phase 38. The real key is `{region}::tenant:{tid}:{method}:{params}` (`TenantAwareCacheKeyGenerator`), and since 38-09 every region also carries the `v4:` format version, so the key is `v4:{region}::tenant:…`. Owner of the fix: the 38-16 docs pass.
