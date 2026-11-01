# k8s/platform/traefik: the staging ingress controller

Phase 29, decision D-29: **Traefik v3 with its Kubernetes Ingress-NGINX provider.** The
retired ingress-nginx and Gateway API were both ruled out. The IngressClass stays `nginx`
and the `nginx.ingress.kubernetes.io/*` annotations on `k8s/base/ingress.yaml` and
`k8s/base/sse-ingress.yaml` stay the configuration source of truth. The provider
translates them.

| File | What it is |
|---|---|
| `traefik.yaml` | Namespace, ServiceAccount, RBAC, Deployment, LoadBalancer Service, IngressClass, NetworkPolicy, PodDisruptionBudget |
| `kustomization.yaml` | Lists `traefik.yaml` only. Applied by `scripts/staging-bootstrap.sh --stage traefik` with an explicit `--context` |
| `supported-annotations.txt` | The pinned version's Annotations Support table, one annotation suffix per line. Plan 29-08 turns it into a render invariant |
| `README.md` | This file. The D-29(a) record below |

`k8s/platform/` sits at depth 3, so the render gates (which discover kustomizations at depth 2)
and `render-golden.sh` (staging and production only) never treat it as a target. That is
deliberate (RESEARCH Pattern 3). The horizon gate does see its image, because
`scripts/check-dependency-horizons.sh` lists `k8s/platform` in `K8S_GLOB_DIRS`.

## Pinned version and sources

- **Image:** `traefik:v3.7.15@sha256:0f6150202bc1fed72a7bd2107460eaa510e5b8a905f3fdb5b84f2c8619c33368`.
  v3.7.15 was released 2026-10-15. It was the newest Traefik release when fetched on
  2026-10-18 (GitHub releases API). The digest is the multi-arch index digest, read from
  `registry-1.docker.io` the same day. endoflife.date `traefik` cycle 3.7: `eol: false`
  (horizon row `traefik`).
- **Provider options:** <https://doc.traefik.io/traefik/v3.7/reference/install-configuration/providers/kubernetes/kubernetes-ingress-nginx/>, fetched 2026-10-18.
- **Annotations Support table:** <https://doc.traefik.io/traefik/v3.7/reference/routing-configuration/kubernetes/ingress-nginx/>, fetched 2026-10-18.
  The versioned v3.7 page and the unversioned latest page gave the same 86 supported rows (diff empty).
- **Source, where the docs are silent:** `traefik/traefik` at tag `v3.7.15`,
  `pkg/provider/kubernetes/ingress-nginx/{kubernetes,client,build,middleware}.go` and
  `pkg/middlewares/ingressnginx/snippet/action.go`.

## Four facts measured from the pinned source, not in the plan's research

1. **`allowSnippetAnnotations` is false unless set** (it is not in `SetDefaults`). With it
   false, an Ingress that carries `configuration-snippet` is **skipped whole**:
   `validateIngress` returns "snippet annotations are not allowed when
   allowSnippetAnnotations is disabled", and the build loop logs "Invalid Ingress, skipping".
   So it is not only the security headers that would be dropped (RESEARCH Pitfall 3). The
   whole `jtoye-ingress` would be missing, with `api-` and `app-staging` unrouted. It is
   enabled in `traefik.yaml`. See the security note below.
2. **`proxy-body-size`, `proxy-buffer-size` and `proxy-buffers-number` are inert unless
   buffering is on.** In `buildBuffering`, when request and response buffering are both off,
   no Buffering middleware is built and the function returns early. Both are off by default
   in the provider, and `jtoye-ingress` sets neither annotation. So under the defaults the
   10m body limit would not be enforced at the edge at all. Fix:
   `--providers.kubernetesingressnginx.proxyrequestbuffering=true`, which is NGINX's own
   default. The docs name this difference too ("NGINX enables proxy-request-buffering by
   default, while Traefik requires explicit opt-in"). Response buffering stays off. See the
   `proxy-buffer-size` row.
3. **`publishService` only resolves inside the watched namespaces.** `GetService` refuses a
   namespace that is not watched. With `watchNamespace=jtoye-staging`,
   `publishService=traefik/traefik` would fail every status update. It is not set, so Ingress
   `.status` stays empty. Nothing in this repo reads it: the bootstrap reads the Traefik
   Service's `.status.loadBalancer.ingress[0].ip`. For the same reason the provider never
   updates `ingresses/status`, so that verb is not granted.
4. **With `watchNamespace` set, every informer is namespace-scoped** (ingresses, services,
   endpointslices, secrets, configmaps). Only `ingressclasses` is listed cluster-wide. That
   is what makes the RBAC below possible.

## RBAC (T-29-07)

The documented RBAC (`kubernetes-ingress-nginx-rbac.yml` at tag v3.7) is one ClusterRole.
Here it is narrowed to:

- **ClusterRole `traefik-ingressclasses`:** `list`, `watch` on `ingressclasses`. This is the
  only cluster-scoped permission.
- **Role `traefik-provider` in `jtoye-staging`:** `list`, `watch` on `services`, `secrets`
  and `configmaps`; on `endpointslices`; and on `ingresses`.
- **Dropped from the documented set:**
  - `namespaces`: only `watchNamespaceSelector` needs it.
  - `pods get`: only OTel attribute injection needs it.
  - `ingresses/status update`: fact 3 above.
  - `get`: no documented rule for this provider grants it.
- **`configmaps` is kept** although no annotation in use needs a ConfigMap. The provider starts
  a configmap informer per watched namespace and waits for it to sync, so withholding the
  permission would stall the provider at start.

Traefik therefore reads Secrets (the TLS certificates) in `jtoye-staging` only.

## D-29(a): every annotation the overlays use

20 distinct `nginx.ingress.kubernetes.io/*` annotations appear in the staging and production
renders. The set is the same in both (measured 2026-10-18). `cert-manager.io/cluster-issuer`
is the 21st annotation on the same Ingresses. "Listed" means the annotation is a row of the
pinned version's Annotations Support table, and it appears in `supported-annotations.txt`.
"Documented" is not "proven": plan 29-15 probes the live edge (headers by `curl -sI`, a CORS
preflight, 429 on burst), and each probe is shown failing first. The **Observed** column is that
measurement, row by row, on 2026-10-19 against `jtoye-stg-aks`, with a pointer into
`.planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-15-edge.txt`. A row
that no external probe can distinguish says so; it is not claimed as a pass. The headers, CSP,
CORS, burst and admin-path checks are re-runnable as `scripts/check-staging-edge.sh --context
jtoye-stg-aks [--fail-arms]`.

| Annotation | Provider status (v3.7) | Behaviour note | Replacement, or why the loss is accepted | Observed (2026-10-19, live) |
|---|---|---|---|---|
| `cert-manager.io/cluster-issuer` | Not a provider annotation | Read by cert-manager's ingress-shim, not by Traefik. The HTTP-01 solver Ingress cert-manager creates names `ingressClassName: nginx` (issuer `k8s/platform/cert-manager/clusterissuers.yaml`), so Traefik serves the challenge | Nothing lost. Proven end to end by the ACME probe (plan 29-11) before any real host carries a production issuer | api-, app- and auth-staging Certificates Ready on `letsencrypt-prod` (issuer CN=YR1, notAfter 2027-01-17), issued over HTTP-01 through Traefik. evidence `29-15-edge.txt` TASK 3 certificates |
| `ssl-redirect` | Listed | "Cannot opt-out per route if enabled globally." It is not enabled globally here, so the per-Ingress value applies. A TLS Ingress redirects by default | Kept as is | `http://` on app-, api- and auth-staging answers **308** to the same path on `https://` (11:10:08Z). evidence `29-15-edge.txt` TASK 3 redirect |
| `force-ssl-redirect` | Listed | Same note as `ssl-redirect` | Kept as is | The same 308 as `ssl-redirect`. The two cannot be told apart from outside. evidence `29-15-edge.txt` TASK 3 redirect |
| `limit-rps` | Listed | Exceeding the limit returns **429**, not NGINX's 503. Traefik uses a token bucket (the bucket starts full at burst and refills at `limit-rps`), where NGINX used a leaky bucket | Accepted. 429 is the more accurate status, and clients that retry on 429 (the frontend's `rate-limited` handling) behave correctly. Plan 29-15 asserts 429 on burst | **429 from the edge**, never 503: 1500 requests at concurrency 40 drew 416 x 429 (empty content-type, not core-java's problem+json) and 0 x 503; 60 at 10/s drew 0 x 429. A no-annotation probe route drew 0 x 429 for the same burst. evidence `29-15-edge.txt` TASK 1 burst |
| `limit-burst-multiplier` | Listed | A value below 1 falls back to 5 (ours is 5). Over the limit gives 429 | Kept as is | Seen only through the bucket it sizes: the edge admitted 1084 of 1500 before refusing. Each Traefik replica keeps its own in-memory bucket and a client may reach both, so the exact burst size is not separately observable from outside. evidence `29-15-edge.txt` TASK 1 burst |
| `limit-connections` | Listed | Evaluated per client IP. Over the limit gives 429 | Kept. Per-IP is only correct because the Service has `externalTrafficPolicy: Local` (the client address survives the LoadBalancer). Phase 37's trusted-proxies item measures this | About **50 in flight per client per replica**: 40 at once drew 0 x 429; 100 on 100 connections drew 0; 150 on 150 connections drew 36 x 429; 150 multiplexed on 2 connections drew 87 x 429. None of these can be the rate limit (150 requests, bucket 500). evidence `29-15-edge.txt` TASK 3 limit-connections |
| `proxy-body-size` | Listed | **Inert unless request buffering is on** (fact 2) | Made effective by `proxyrequestbuffering=true` in `traefik.yaml`. `jtoye-ingress` then gets `MaxRequestBodyBytes` 10m. core-java's own multipart cap (6MB) still refuses first | An 11 MiB POST gets **413** `Request Entity Too Large` from the edge with 0 bytes uploaded; exactly 10 MiB and 1 MiB pass to core-java (401 problem+json). evidence `29-15-edge.txt` TASK 3 body-size |
| `enable-cors` | Listed, **partial support** | "NGINX responds with all configured headers unconditionally; Traefik handles headers differently between pre-flight and regular requests" | Accepted subject to proof. The dashboard's cross-origin calls need a correct preflight answer and `Access-Control-Allow-Origin` on the actual response. Plan 29-15 runs a CORS preflight against `api-staging`, shown failing first | Preflight from app-staging: `Access-Control-Allow-Origin` echoed, credentials true. From `https://evil.example`: no ACAO. A no-annotation probe route answers neither. evidence `29-15-edge.txt` TASK 1 cors |
| `cors-allow-origin` | Listed | One-level wildcard only (`protocol://*.domain`). Ours is an exact origin | Kept as is | Only `https://app-staging.olajay.co.uk` is echoed; `https://evil.example` is not. evidence `29-15-edge.txt` TASK 1 cors |
| `cors-allow-methods` | Listed | None | Kept as is | Preflight answers `Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS,PATCH`. evidence `29-15-edge.txt` TASK 3 cors-headers |
| `cors-allow-credentials` | Listed | None | Kept as is | Preflight answers `Access-Control-Allow-Credentials: true`. evidence `29-15-edge.txt` TASK 1 cors |
| `cors-allow-headers` | Listed | None | Kept as is; the list itself was extended with `Idempotency-Key` and `X-Tenant-Id` (issue #1025: the original ingress-nginx default list refused the storefront order POST at the preflight) | Preflight answers the configured list verbatim, `DNT,Keep-Alive,User-Agent,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Range,Authorization,Idempotency-Key,X-Tenant-Id`. evidence `29-15-edge.txt` TASK 3 cors-headers (old list); `1025-cors-live-preflight-prefix.txt` (the refusal); INV-24 holds the render, `scripts/check-cors-preflight.sh` the live edge |
| `cors-max-age` | Listed | None | Kept as is | Preflight answers `Access-Control-Max-Age: 86400`. evidence `29-15-edge.txt` TASK 3 cors-headers |
| `configuration-snippet` | Listed, **gated by `allowSnippetAnnotations`** (fact 1) | Only these directives are supported: `add_header`, `proxy_method`, `more_set_headers`, `proxy_set_header`, `more_set_input_headers`, `set`, `rewrite`, `if`, `return`. Our snippet is five `more_set_headers "Name: value"` lines (six on staging, with `X-Robots-Tag`), all inside that subset. `Referrer-Policy` left it in issue #1071: `more_set_headers` replaced the frontend's `no-referrer` on `/pay` and `/invite`, so the apps now send it themselves (frontend `next.config.mjs`, core-java `SecurityConfig`). `more_set_headers` parses `"Name: value"` (`snippet/action.go`) | Kept, with `allowSnippetAnnotations=true`. The security headers (HSTS, X-Frame-Options, nosniff, X-XSS-Protection, Permissions-Policy) ride on it. Plan 29-15 checks them by `curl -sI` | All six headers plus `X-Robots-Tag: noindex, nofollow` on app- and api-staging; all absent on a no-snippet probe route. evidence `29-15-edge.txt` TASK 1 headers. Since issue #1071 the `headers` arm still reads `Referrer-Policy` from those two hosts, now sent by the apps, and the `pay-referrer` arm requires `no-referrer` on app-staging `/pay` |
| `proxy-connect-timeout` | Listed (Timeout) | Becomes the backend transport's dial timeout | Kept. **Research flagged it as undocumented. On 2026-10-18 it is listed** under Timeout in the v3.7 table, and `build.go` reads it into `DialTimeout` | **Configured, not externally observable.** Telling a dial timeout apart needs a backend that does not accept connections, and no public path has one. evidence `29-15-edge.txt` TASK 3 not-observable |
| `proxy-send-timeout` | Listed (Timeout) | Becomes the backend transport's write timeout | Kept. **Research flagged it as undocumented. On 2026-10-18 it is listed** under Timeout, and `build.go` reads it into `WriteTimeout`. `sse-ingress` relies on its 3600 | **Configured, not externally observable.** It needs a backend that stops reading the request. The SSE path's 3600 cannot be told from the default in a 90 s hold. evidence `29-15-edge.txt` TASK 3 not-observable |
| `proxy-read-timeout` | Listed (Timeout) | Same mechanism as the two above | Kept. `sse-ingress` relies on its 3600 | SSE path: an authenticated stream held 90 s stayed open past 60 s until curl's own cut (rc 28). That shows no 60 s total cap, not the 3600 value: keep-alives every 25 s would also defeat a 60 s idle timeout. The main Ingress's 60 is **not externally observable** (no public path is slow). evidence `29-15-edge.txt` TASK 3 sse |
| `proxy-buffer-size` | Listed (Buffering) | Only used when **response** buffering is on. Response buffering stays off (provider default `proxyBuffering=false`, and `jtoye-ingress` does not set `proxy-buffering`), so it is inert on `jtoye-ingress` | **Research flagged it as undocumented. On 2026-10-18 it is listed** under Buffering. Loss accepted. In NGINX these two values sized the buffers for the upstream response and its headers, and too small a buffer gave 502 "upstream sent too big header". Traefik streams the response to the client without those buffers, so that failure mode does not exist here. Turning on response buffering only to honour a tuning value would make Traefik hold whole responses (memory, then `/tmp`) | **Configured, inert by design** (response buffering off), so not externally observable. evidence `29-15-edge.txt` TASK 3 not-observable |
| `proxy-buffers-number` | Listed (Buffering) | "used to compute the size of a single buffer (size × number)". Inert for the same reason as `proxy-buffer-size` | **Research flagged it as undocumented. On 2026-10-18 it is listed** under Buffering. Loss accepted, same reason as `proxy-buffer-size` | **Configured, inert by design**, the same as `proxy-buffer-size`. Not externally observable. evidence `29-15-edge.txt` TASK 3 not-observable |
| `proxy-buffering` | Listed | Any value other than `on` disables it. `sse-ingress` sets `off` | Kept. Each SSE event flushes immediately | SSE keep-alive comments arrive one by one, 25 s apart (+18.0, +43.0, +68.0 s), not held back in a buffer. evidence `29-15-edge.txt` TASK 3 sse |
| `proxy-request-buffering` | Listed | Any value other than `on` disables it. `sse-ingress` sets `off`, which overrides the provider-level `true` | Kept. The SSE path stays unbuffered while the main Ingress buffers requests | `off` on the SSE path is **not externally observable** there (the stream is a GET with no body). The provider-level `true` on `jtoye-ingress` is what produces the 413 above. evidence `29-15-edge.txt` TASK 3 body-size |

The page's own **Unsupported Annotations** table (45 rows, for example `limit-rate`,
`mirror-target`, `stream-snippet`) shares no annotation with the set above. The docs say an
unimplemented annotation is not guaranteed to be rejected (`load-balance` is "silently
ignored"). That is why plan 29-08 makes `supported-annotations.txt` a render invariant: a new
annotation not in the table fails CI.

## Security note: `allowSnippetAnnotations=true`

Snippet annotations are the class of risk that retired ingress-nginx's default. Here the flag
is on because the security headers are delivered by `more_set_headers`, and with it off the
whole `jtoye-ingress` is skipped (fact 1). The risk is bounded three ways:

1. **The provider's own directive subset** (listed above). It is not a raw NGINX config
   injection. There is no `lua`, `proxy_pass` or `include`.
2. **Who can write an Ingress.** In `jtoye-staging`, only the CI principal can create or patch
   Ingresses (`k8s/platform/rbac/ci-deployer.yaml` grants it no more than Azure's built-in
   RBAC Writer on that namespace path, plus the custom resources the render needs). Traefik
   watches no other namespace.
3. **Cluster-wide blast radius.** Traefik's Secret read is a Role in `jtoye-staging`, not a
   ClusterRole. A snippet cannot reach another namespace's certificates through the
   controller.

## Known caveat for later plans

`jtoye-ingress` redirects HTTP to HTTPS on `api-` and `app-staging`. The cert-manager solver
Ingress for the same host is a separate Ingress with the longer `Path` rule, so Traefik's
default rule-length priority routes the challenge to the solver ahead of the redirect. The
ACME probe (plan 29-11) proves the challenge path on a host with no other Ingress. The
interaction with `jtoye-ingress` is first exercised when the real hosts are issued (plans
29-08 and 29-14). A redirected challenge shows up there as a Certificate stuck in `pending`.
