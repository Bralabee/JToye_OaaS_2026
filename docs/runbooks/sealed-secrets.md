# Runbook: K8s Sealed Secrets (bitnami-labs/sealed-secrets)

**Status:** Operational runbook for cluster admins.
**Scope:** Producing `SealedSecret` manifests (safe to commit) for every
secret the platform consumes, using
`k8s/base/secrets-template.yaml.example` as the reference input shape.
**Audience:** On-call + devops team.

## Why Sealed Secrets

K8s `Secret` objects are base64 (not encrypted). Committing plaintext
Secret manifests — even with placeholder values — is dangerous because real
secrets will eventually land there. SealedSecrets encrypt the payload with a
cluster-held private key so the manifest on disk is ciphertext; only the
in-cluster controller can decrypt it.

> **State since issue #100 (P2-9):** the kustomize builds (`k8s/base`,
> `k8s/staging`, `k8s/production`) emit **zero** `kind: Secret` objects.
> The old live template was renamed to
> `k8s/base/secrets-template.yaml.example` and removed from the base
> `resources:` list. CI enforces this via
> `k8s/scripts/check-no-plaintext-secrets.sh` (job `k8s-validate` in
> `.github/workflows/ci-cd.yaml`) — any attempt to re-add a plaintext Secret
> to a kustomize build fails the pipeline.

## Required out-of-band secrets

Because kustomize no longer creates any Secret, ALL of the following must
exist in the target namespace **before** `kubectl apply -k k8s/<env>/`.
Missing secrets leave pods in `CreateContainerConfigError` and break the
pg-backup CronJob. Create them via SealedSecrets (this runbook) or, for
bootstrap only, `kubectl create secret generic` (`k8s/QUICK_START.md` Step 1).

| Secret name | Keys | Consumed by |
|---|---|---|
| `postgres-credentials` | `host`, `port`, `database`, `username`, `password`, `backup-username`, `backup-password` | `core-java-deployment.yaml`, `pg-backup-cronjob.yaml` (#90 backup role) |
| `s3-backup-credentials` | `access-key`, `secret-key` | `pg-backup-cronjob.yaml` (#90) |
| `keycloak-credentials` | `admin-username`, `admin-password`, `frontend-client-secret`, `core-api-client-secret` | `frontend-deployment.yaml` |
| `nextauth-secret` | `secret` | `frontend-deployment.yaml` |
| `redis-credentials` | `password` | `core-java-deployment.yaml` |
| `rabbitmq-credentials` | `username`, `password`, `stomp-login`, `stomp-passcode` | `core-java-deployment.yaml` |

The exact key shapes live in `k8s/base/secrets-template.yaml.example` — that
file is reference-only and is never applied.

## Prerequisites

Before anything in this runbook will work:

1. **Cluster-admin access** to target cluster (`kubectl auth can-i '*' '*'`
   returns `yes`).
2. **`kubeseal` CLI** installed locally. Install with:
   ```bash
   # macOS
   brew install kubeseal
   # Linux (replace VERSION with the latest from https://github.com/bitnami-labs/sealed-secrets/releases)
   VERSION=v0.27.3
   wget -O /tmp/kubeseal.tar.gz \
     "https://github.com/bitnami-labs/sealed-secrets/releases/download/${VERSION}/kubeseal-${VERSION#v}-linux-amd64.tar.gz"
   tar -xzf /tmp/kubeseal.tar.gz -C /tmp kubeseal
   sudo install /tmp/kubeseal /usr/local/bin/
   ```
3. **helm 3.12+** for the controller install.

## 1. Install the controller

```bash
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
helm repo update

helm install sealed-secrets-controller sealed-secrets/sealed-secrets \
  --namespace kube-system \
  --set-string fullnameOverride=sealed-secrets-controller \
  --wait
```

Verify:

```bash
kubectl get pods -n kube-system -l name=sealed-secrets-controller
# Expect one Running pod.

kubectl logs -n kube-system -l name=sealed-secrets-controller --tail=20
# Expect "Starting sealed-secrets controller" + "Loaded key pair ..."
```

## 2. Export the public key

The controller generates a key pair on first start. You encrypt against the
public half; only the controller can decrypt with the private half.

```bash
kubeseal --fetch-cert > /tmp/sealed-secrets-pub.pem

# Sanity check
head -2 /tmp/sealed-secrets-pub.pem
# Expect: -----BEGIN CERTIFICATE-----
```

Commit `sealed-secrets-pub.pem` to the deployment repo under
`k8s/certs/<env>/sealed-secrets-pub.pem` (one per cluster — staging vs prod
have DIFFERENT keys). This lets developers seal new secrets offline without
cluster access.

## 3. Convert the existing plaintext secrets

There are two paths: interactive (one secret at a time) and batch
(whole file at once via the script).

### 3a. Interactive — one secret

```bash
# Start from a plaintext Secret manifest. NEVER commit this to git.
cat > /tmp/postgres-credentials.yaml <<'EOF'
apiVersion: v1
kind: Secret
metadata:
  name: postgres-credentials
  namespace: jtoye-production
type: Opaque
stringData:
  host: "postgresql-primary.jtoye-infrastructure.svc.cluster.local"
  port: "5432"
  database: "jtoye"
  username: "jtoye"
  password: "THE_REAL_PASSWORD_HERE"
EOF

# Seal it
kubeseal --format=yaml \
  --cert=/tmp/sealed-secrets-pub.pem \
  < /tmp/postgres-credentials.yaml \
  > k8s/production/sealed-secrets/postgres-credentials.sealed.yaml

# Scrub the plaintext
shred -u /tmp/postgres-credentials.yaml

# Confirm the output is safe to commit
head -3 k8s/production/sealed-secrets/postgres-credentials.sealed.yaml
# Expect: apiVersion: bitnami.com/v1alpha1 / kind: SealedSecret
```

### 3b. Batch — all secrets at once

Use `k8s/scripts/seal-secrets.sh` (shipped with this runbook). It takes a
plaintext multi-document Secret file and emits SealedSecret manifests in
a target directory.

```bash
# INPUT: a plaintext file with the real values substituted in (DO NOT COMMIT).
#        Start from the reference shape:
#        cp k8s/base/secrets-template.yaml.example /tmp/all-plaintext-secrets.yaml
#        then replace every REPLACE_WITH_* placeholder with the real value.
# OUTPUT: one *.sealed.yaml per Secret in the target dir

./k8s/scripts/seal-secrets.sh \
  --cert /tmp/sealed-secrets-pub.pem \
  --namespace jtoye-production \
  --input  /tmp/all-plaintext-secrets.yaml \
  --output k8s/production/sealed-secrets/

# Scrub after
shred -u /tmp/all-plaintext-secrets.yaml
```

## 4. Wire SealedSecrets into the overlay

Edit `k8s/production/kustomization.yaml`:

```yaml
resources:
  - ../base
  - namespace.yaml
  - sealed-secrets/postgres-credentials.sealed.yaml
  - sealed-secrets/s3-backup-credentials.sealed.yaml
  - sealed-secrets/keycloak-credentials.sealed.yaml
  - sealed-secrets/nextauth-secret.sealed.yaml
  - sealed-secrets/redis-credentials.sealed.yaml
  - sealed-secrets/rabbitmq-credentials.sealed.yaml
```

(The base `resources:` list no longer includes any Secret manifest — that
was done by issue #100. `kind: SealedSecret` manifests pass the CI guard;
`kind: Secret` manifests fail it.)

## 5. Apply + verify

```bash
kubectl apply -k k8s/production/

# The controller watches for SealedSecret objects and emits a matching Secret
# in the same namespace. Verify:
kubectl get sealedsecret -n jtoye-production
kubectl get secret       -n jtoye-production

# Numbers should match: every SealedSecret has a derived Secret.
```

## 6. Dev / local fallback — unchanged

Local docker-compose development uses `.env` files loaded by `docker-compose`,
NOT Kubernetes Secrets of any kind. This runbook does NOT affect the local
dev workflow. The `.env` file is git-ignored and each developer maintains
their own copy seeded from `.env.local.example`.

## 7. `secrets-template.yaml` — what happened to it?

**Done (issue #100, 2026-07-11):** the file was renamed to
`k8s/base/secrets-template.yaml.example` and removed from
`k8s/base/kustomization.yaml`'s `resources:` list. It is now reference-only:
the values are all `REPLACE_WITH_*` placeholders and kustomize never applies
it. No overlay patch/exclusion is needed — the Secret objects simply are not
part of any build.

Consequence for cluster bootstrap: on a brand-new cluster you must create
the six secrets in the *Required out-of-band secrets* table above BEFORE the
first `kubectl apply -k` — either seal them per §3 and commit the
`*.sealed.yaml` files into the overlay, or (bootstrap only) run the
`kubectl create secret generic` commands in `k8s/QUICK_START.md` Step 1.

The regression gate `k8s/scripts/check-no-plaintext-secrets.sh` (CI job
`k8s-validate`) fails any build that emits a `kind: Secret` again.

## 8. Key rotation

Controller keys rotate automatically every 30 days. **Old keys are kept
indefinitely** — the controller can decrypt SealedSecrets encrypted against
any past key. Rotation is transparent until a compromise.

### Normal rotation (monthly, automatic)

No action required. Verify by listing active keys:

```bash
kubectl get secret -n kube-system \
  -l sealedsecrets.bitnami.com/sealed-secrets-key \
  -o name | wc -l
# Expect: 2-3 keys (one per 30 days, all retained)
```

### Emergency rotation on key compromise

If you suspect the controller's private key is leaked:

1. **Generate new key + rotate:**
   ```bash
   kubectl delete pod -n kube-system -l name=sealed-secrets-controller
   # The new pod generates a fresh key pair. Old keys are RETAINED so
   # existing SealedSecrets still decrypt.
   ```
2. **Export the new public key:**
   ```bash
   kubeseal --fetch-cert > k8s/certs/<env>/sealed-secrets-pub.pem
   git commit -am "chore: rotate sealed-secrets public key for <env>"
   ```
3. **Re-seal every SealedSecret** using the new key:
   ```bash
   find k8s/production/sealed-secrets -name '*.sealed.yaml' | while read f; do
     # Recover plaintext from the cluster (requires cluster access)
     secret_name=$(yq '.spec.template.metadata.name' "$f")
     kubectl get secret "$secret_name" -n jtoye-production -o yaml > /tmp/plain.yaml
     # Strip server-side metadata
     yq -i 'del(.metadata.creationTimestamp,.metadata.resourceVersion,.metadata.uid,.metadata.annotations,.metadata.ownerReferences)' /tmp/plain.yaml
     # Re-seal with new key
     kubeseal --format=yaml --cert=k8s/certs/production/sealed-secrets-pub.pem \
       < /tmp/plain.yaml > "$f"
     shred -u /tmp/plain.yaml
   done
   git commit -am "chore: re-seal secrets after key rotation"
   ```
4. **Delete the compromised key from the cluster** — ONLY after confirming
   every SealedSecret re-sealed correctly and `kubectl get secret` shows all
   the derived Secrets match expected values:
   ```bash
   # List keys, keep the newest
   kubectl get secret -n kube-system \
     -l sealedsecrets.bitnami.com/sealed-secrets-key \
     --sort-by=.metadata.creationTimestamp
   # Delete older compromised keys
   kubectl delete secret -n kube-system <old-compromised-key>
   ```

5. **Rotate the underlying credentials too.** A leaked controller key means
   the attacker may have decrypted your Secrets already. Changing Postgres /
   Stripe / Keycloak passwords / client-secrets is mandatory.

## 9. Rollback if a sealed manifest decrypts incorrectly

If `kubectl apply -f foo.sealed.yaml` succeeds but the derived Secret has
the wrong values (controller decryption returned garbage, typically due to
manifest corruption in git):

```bash
# 1. Immediately delete the SealedSecret so the derived Secret is recreated
#    from a good source.
kubectl delete sealedsecret <name> -n jtoye-production

# 2. Revert the manifest in git
git log -1 -- k8s/production/sealed-secrets/<name>.sealed.yaml
git revert <bad-commit>

# 3. Re-apply
kubectl apply -f k8s/production/sealed-secrets/<name>.sealed.yaml
```

If the derived Secret has stale values because the controller never
decrypted successfully:

```bash
kubectl describe sealedsecret <name> -n jtoye-production
# Look for events: "failed to decrypt" or "no key found for cert sha256=..."
```

Usually this means you sealed against the wrong public key (dev vs prod).
Re-seal against the correct cluster key.

## 10. Backup the controller key — mandatory

If the controller private key is lost, every existing SealedSecret becomes
permanently undecryptable. Back up the keys off-cluster:

```bash
kubectl get secret -n kube-system \
  -l sealedsecrets.bitnami.com/sealed-secrets-key \
  -o yaml > /secure/backup/sealed-secrets-keys-$(date +%Y%m%d).yaml

# Encrypt with your team's GPG key or upload to a password manager.
# Store outside the cluster, outside git, outside the laptop you just ran
# the command on.
```

Restore:

```bash
kubectl apply -f /secure/backup/sealed-secrets-keys-YYYYMMDD.yaml
kubectl delete pod -n kube-system -l name=sealed-secrets-controller
# Controller restarts, picks up the restored keys.
```

## 11. Cheatsheet

| I want to... | Command |
|---|---|
| Install controller | `helm install sealed-secrets-controller sealed-secrets/sealed-secrets -n kube-system --wait` |
| Fetch public key | `kubeseal --fetch-cert > cert.pem` |
| Seal a secret | `kubeseal --format=yaml --cert=cert.pem < plain.yaml > sealed.yaml` |
| Seal in bulk | `./k8s/scripts/seal-secrets.sh --cert cert.pem --namespace jtoye-production --input plain.yaml --output dir/` |
| Apply to cluster | `kubectl apply -f sealed.yaml` |
| Check decryption | `kubectl describe sealedsecret <name> -n <ns>` |
| List keys in cluster | `kubectl get secret -n kube-system -l sealedsecrets.bitnami.com/sealed-secrets-key` |
| Force key rotation | `kubectl delete pod -n kube-system -l name=sealed-secrets-controller` |
| Backup keys | `kubectl get secret -n kube-system -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml > backup.yaml` |

## 12. Related docs

- `k8s/base/networkpolicies/README.md` — pod-to-pod isolation (sister to this runbook)
- `k8s/DEPLOYMENT.md` — broader deployment guide (already referenced SealedSecrets)
- `.planning/phases/15-k8s-networkpolicies-sealed-secrets/15-RESEARCH.md` — design notes
- Upstream: <https://github.com/bitnami-labs/sealed-secrets>

---
*Runbook authored 2026-04-18 for Phase 15 INF-02.*
