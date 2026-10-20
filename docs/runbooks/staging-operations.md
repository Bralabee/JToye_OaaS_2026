# Runbook — operating the staging environment

Owner: unassigned. Written by plan 29-27 (Phase 29, decision D-18), 2026-10-20. This is the one
page an operator needs to run the staging estate: reach the cluster, read Grafana, restart a
workload, run the restore drill, know where alerts go, and run the gates after a change. It is
deliberately one page. SLOs and the full first-response runbook set are deferred until after the
first tenant is live (issue #112; the remainder is tracked on #982).

> **No literal credential value appears in this file.** It names Key Vault objects and the
> commands that read them. A value is read into a shell variable or a mode-600 file in the
> operator's own terminal and is never pasted into a chat, a ticket, a commit or an evidence
> file. Evidence files are scanned for the objects' values by
> `scripts/staging-secrets.sh --leak-scan FILE`.

**The estate, in six facts** (each is read back from the repository, not from memory):

| Fact | Value |
|---|---|
| Azure subscription | `c483d353-5f61-4587-a790-addb9ab5fb94` (J'Toye's own; the CLI default on the owner's machine is the **employer's**, so it is never relied on) |
| Resource group | `jtoye-rg` |
| AKS cluster / kube context | `jtoye-stg-aks` / `jtoye-stg-aks` |
| Namespace | `jtoye-staging` (`k8s/staging/namespace.yaml`) |
| Key Vault | `jtoye-stg-kv` |
| Grafana | `https://grafana-staging.olajay.co.uk` (the only monitoring host with a public name) |

The other public hosts are `app-staging`, `api-staging`, `auth-staging` and `mail-staging`, all
under `olajay.co.uk`, behind Traefik v3 (`k8s/platform/traefik`).

---

## 1. Reach the cluster

The cluster is Entra-only with local accounts disabled, so the kubeconfig that `az` writes holds
no credential; `kubelogin` supplies a short-lived token through the Azure CLI. The subscription
is passed on the command, **never** set with `az account set`, because that would change the
default for every other shell and project on the machine.

```bash
source ~/miniconda3/etc/profile.d/conda.sh && conda activate jtoye-ops
az aks get-credentials --subscription c483d353-5f61-4587-a790-addb9ab5fb94 \
  -g jtoye-rg -n jtoye-stg-aks --overwrite-existing
kubelogin convert-kubeconfig -l azurecli
kubectl --context jtoye-stg-aks -n jtoye-staging get pods
```

Every `kubectl` in this page and every script that touches the cluster names
`--context jtoye-stg-aks`. Never rely on `current-context`: the employer's context
(`sipbihs2aks`) can be the current one, and the staging scripts refuse it by name
(`scripts/lib/staging-kube-guard.sh`; the context's API server must equal the one recorded in
`k8s/staging/azure-identity.yaml`). A healthy namespace runs 13 workloads: `alertmanager`,
`core-java`, `edge-go`, `frontend`, `grafana`, `keycloak`, `mailpit`, `postgres-exporter`,
`prometheus`, `redis`, `redis-exporter`, `secret-sync`, plus the `pg-backup` CronJob. Cluster-wide,
nothing should be unhealthy:

```bash
kubectl --context jtoye-stg-aks get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded
```

CI reaches the same cluster the same way, as the `jtoye-ci` identity through OIDC
(`.github/workflows/ci-cd.yaml`, job `deploy-staging`).

## 2. Read Grafana

Open `https://grafana-staging.olajay.co.uk`. It has its own login (anonymous access and sign-up
are off). The user and password are two Key Vault objects of `jtoye-stg-kv`:

- `grafana-admin-credentials--admin-user` (an estate fact, not a secret)
- `grafana-admin-credentials--admin-password`

Read them **with the subscription pinned**, in your own terminal, into the clipboard or a
variable, never with the CLI default subscription:

```bash
az keyvault secret show --subscription c483d353-5f61-4587-a790-addb9ab5fb94 \
  --vault-name jtoye-stg-kv -n <object> --query value -o tsv
```

or through the guard library, in a subshell (`az_read` refuses until `az_guard_init` has passed
in the same shell):

```bash
( export JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94
  source scripts/lib/azure-guard.sh && az_guard_init >/dev/null \
    && az_read keyvault secret show --vault-name jtoye-stg-kv -n <object> --query value -o tsv )
```

Grafana reads Prometheus at `http://prometheus:9090` inside the namespace. Prometheus and
Alertmanager have no public name; to look at them directly, port-forward with the pinned
context (`kubectl --context jtoye-stg-aks -n jtoye-staging port-forward deploy/prometheus 9090`).
Grafana keeps no state on disk (`/var/lib/grafana` is an `emptyDir`): dashboards and the
datasource are provisioned from committed files, so a restart loses only UI edits and sessions.

## 3. Restart a workload

```bash
kubectl --context jtoye-stg-aks -n jtoye-staging rollout restart deploy/<name>
kubectl --context jtoye-stg-aks -n jtoye-staging rollout status deploy/<name> --timeout=5m
```

**Why a rotated secret needs a restart.** Secrets arrive from Key Vault through the Secrets Store
CSI driver, which fetches a value when a pod **mounts** it. Rotation is off (the add-on default),
and a container that reads a Secret through an environment variable sees it only when the
container starts anyway. So a new Key Vault version reaches a workload only after a pod restart.
For an object no workload mounts permanently, the always-on `secret-sync` Deployment is the
mounting pod: restart that one (`deploy/secret-sync`) and the synced Secret it produces is
refreshed. The worked example, including the refusal check for the superseded value, is
`docs/runbooks/credential-rotation.md` section 6.1.

**Mailpit reads its certificate only at start.** Its TLS certificate comes from the cert-manager
internal CA. After a renewal, restart `deploy/mailpit` so it serves the new one; plan 29-22
measured the expiry at 2027-01-17. A Mailpit serving an expired certificate fails the STARTTLS
handshake core-java requires (section 6).

Do not restart `core-java` for convenience. Its order counter (`NoOrdersCreated`) is in memory
and resets on every restart, so a quiet staging re-arms that alert; `scripts/seed-order-metric.sh`
places one order to re-create the counter and is a write the owner approves.

## 4. Run the restore drill

`scripts/staging-pitr-drill.sh` rehearses both recovery lines of the managed PostgreSQL server
(`jtoye-stg-pg`) by row count: the provider's point-in-time restore, and the logical dump the
`pg-backup` CronJob writes. The full account (arms, trap, first execution) is in
`docs/runbooks/backups.md`, section "Managed PITR drill (staging)".

```bash
JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 \
  bash scripts/staging-pitr-drill.sh --context jtoye-stg-aks     # 0 PASS - 1 a defect - 2 VOID
bash scripts/staging-pitr-drill.sh --selftest                     # offline, no cluster, no Azure
```

The script initialises the Azure guard itself and refuses without the exported subscription id.
What to know before running it:

- **It writes.** It creates a temporary server `jtoye-stg-pg-drill-<run id>` (a Burstable
  `Standard_B2s` copy, about 0.06 GBP an hour), and writes exactly two marker products to the live
  staging database through the vendor API. Run it with the owner's approval. The first execution
  (2026-10-19) took about 20 minutes.
- **The trap.** `trap cleanup EXIT` deletes the temporary server and the drill pod on every exit
  path and reads the deletion back. A SIGKILL cannot be trapped: if a run dies hard, list the
  servers and delete any `jtoye-stg-pg-drill-*` by hand, because a server left behind bills every
  hour:
  `az postgres flexible-server list --subscription c483d353-5f61-4587-a790-addb9ab5fb94 -g jtoye-rg`
  (the long-lived ones are `jtoye-stg-pg` and, from another project, `snackpass-pg`).
- **A real recovery differs from the drill.** A point-in-time restore carries no firewall rules
  and an empty `azure.extensions` allow-list. Re-add the firewall rule and re-set
  `azure.extensions` before any migration or restore that creates an extension.
- **Backups.** The `pg-backup` CronJob runs at 02:00 UTC and writes to the `jtoye-db-backups`
  container, whose retention policy is locked (30 days). The lock is one-way and was applied by
  the owner after the first verified backup.

## 5. Where alerts go, and the only safe mute

Prometheus evaluates the same rule corpus as the compose stack. Alertmanager sends each alert
by authenticated STARTTLS SMTP to the owner's real inbox (Zoho), and a copy to the in-cluster
Mailpit, which is also reachable at `https://mail-staging.olajay.co.uk` by testers. The
addresses, login and app password are four Key Vault objects, `alertmanager-smtp-credentials--*`
(`from`, `username`, `password`, `to`), supplied by the owner; none is generated.

The proof the path works is `scripts/check-alert-liveness.sh`: it posts a synthetic alert and
requires it to arrive, and was shown failing with the real mail path broken before it was shown
passing (plan 29-25). To run it against staging, port-forward Prometheus, Mailpit and
Alertmanager with the pinned context first, and always set `ALERTMANAGER_URL` explicitly (its
default, `localhost:9093`, can be the local compose Alertmanager on the operator host):

```bash
K='kubectl --context jtoye-stg-aks -n jtoye-staging'
$K port-forward svc/prometheus 19090:9090 &
$K port-forward svc/mailpit 18025:8025 &
$K port-forward svc/alertmanager 19093:9093 &
PROM_URL=http://localhost:19090 ALERTMANAGER_URL=http://localhost:19093 \
PROM_EXEC='kubectl --context jtoye-stg-aks -n jtoye-staging exec deploy/prometheus --' \
SINK=mailpit MAILPIT_URL=http://localhost:18025 bash scripts/check-alert-liveness.sh
```

Stop the three port-forwards afterwards (`kill %1 %2 %3` in the same shell).

**The only safe mute** is `ALERTMANAGER_MUTE_ALERTNAMES` on the `alertmanager` Deployment
(`k8s/components/monitoring/alertmanager.yaml`). Staging sets it to `NoOrdersCreated`, which a
quiet staging fires by design. The mute is **alertname-only**: a mute keyed on severity,
component or service would also swallow the liveness probe's transport alert and make the
transport look broken. `scripts/check-alert-mute.sh` enforces this (every matcher keys on
`alertname`, every muted name is on its written allow-list and still exists as a rule, and no
render other than staging sets the variable). Do not mute anything else; fix or delete the rule.

## 6. Why a mail fault turns the deploy red

The CI `deploy-staging` health step runs
`kubectl exec deployment/core-java -- curl -f http://localhost:9091/actuator/health`. That is the
**aggregate** health endpoint, and it includes Spring Boot's mail indicator (no
`management.health.mail` key is set). core-java requires STARTTLS to the mail host
(`mail.smtp.starttls.required: true`, a literal in `application-prod.yml`, the profile Kubernetes
runs). So a core-java that cannot negotiate STARTTLS with Mailpit answers 503 `DOWN`, the deploy
step fails, and the job's rollback step undoes the deploy. This is by design, and plan 29-22 showed
it both ways: 200 `UP` with Mailpit serving TLS, 503 `DOWN` with Mailpit offering no STARTTLS.

The fix is always the mail path: the Mailpit certificate, the cluster CA, the JVM truststore.
Never weaken `spring.mail.properties.mail.smtp.starttls.required`, and never exclude mail from
the health that is checked.

## 7. Run the gates after any change

| Gate | What it proves | Invocation |
|---|---|---|
| `scripts/check-deploy-digest-parity.sh` | the running `core-java`, `edge-go` and `frontend` pods are the images pushed for the deployed commit (the Kubernetes half of runtime parity) | `bash scripts/check-deploy-digest-parity.sh --context jtoye-stg-aks --tag <40-hex deployed sha>` |
| `scripts/check-staging-edge.sh` | the live edge does what its annotations say: security headers, CSP, CORS, rate limit, noindex | `bash scripts/check-staging-edge.sh --context jtoye-stg-aks` |
| `scripts/check-networkpolicy-enforcement.sh` | a NetworkPolicy denial really happens (a control passes, the denial times out). It creates and deletes pods, a namespace and a policy | `bash scripts/check-networkpolicy-enforcement.sh --context jtoye-stg-aks` |

The `--tag` is the **recorded** deployed sha (`deploy.sh` prints `DEPLOYED_SHA`, and CI deploys
with `DEPLOY_TAG`), never a fresh `git rev-parse HEAD`: a docs commit moves `HEAD` to a commit
whose images were never built. For each gate, exit 0 is a pass, 1 is a failure, and 2 is VOID
("could not evaluate"). A VOID is never a pass. After a change to monitoring, also run
`scripts/check-alert-metrics.sh` (every alert selector matches a live series; point `PROM_URL` at
the port-forwarded Prometheus, because its default is the local compose one) and the liveness
command in section 5.
