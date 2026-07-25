# Quick Start - Kubernetes Deployment

## TL;DR - Deploy to Production in 5 Minutes

### Prerequisites Installed?
- [ ] kubectl configured
- [ ] NGINX Ingress Controller running
- [ ] cert-manager installed
- [ ] Metrics server running

**If NO, run:**
```bash
# Install all prerequisites
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.0/cert-manager.yaml
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

---

## Step 1: Create Secrets (REQUIRED)

> The kustomize manifests deliberately ship **no** Secret objects (#100).
> Every secret below must exist BEFORE Step 2, or pods sit in
> `CreateContainerConfigError`. For production prefer SealedSecrets —
> see `docs/runbooks/sealed-secrets.md`; the commands below are the
> bootstrap path. Reference key shapes:
> `k8s/base/secrets-template.yaml.example`.

```bash
# Generate secure passwords
#
# NAMING NOTE (Phase 26 reconcile): the shell variable below is
# POSTGRES_BACKUP_PASSWORD and stays that way — it is the name this document has
# always used. The `.env` key that plan 26-05 adds for the local-k8s bootstrap is
# DB_BACKUP_PASSWORD. The two names are the same thing at different layers: both
# feed the SAME `backup-password` key of the postgres-credentials Secret, which
# pg-backup-cronjob.yaml reads as PGPASSWORD for the BYPASSRLS jtoye_backup role.
# Do not treat a mismatch between the two documents as a defect.
export POSTGRES_PASSWORD=$(openssl rand -base64 32)
export POSTGRES_BACKUP_PASSWORD=$(openssl rand -base64 32)
export REDIS_PASSWORD=$(openssl rand -base64 32)
export RABBITMQ_PASSWORD=$(openssl rand -base64 32)
export KEYCLOAK_PASSWORD=$(openssl rand -base64 32)
export NEXTAUTH_SECRET=$(openssl rand -base64 32)
export FRONTEND_SECRET=$(openssl rand -base64 32)
export CORE_API_SECRET=$(openssl rand -base64 32)

# Create all secrets
kubectl create namespace jtoye-production

# backup-* keys: BYPASSRLS dump role for the pg-backup CronJob (#90).
# Create the role itself via infra/backups/create-backup-role.sql.
#
# DEF-2 (Phase 26 / INFRA-02b): `username` MUST be the NOSUPERUSER application
# role `jtoye_app`, NOT the `jtoye` superuser this recipe used to name. A
# superuser BYPASSES every Row-Level Security policy, so multi-tenant isolation
# becomes impossible — and core-java refuses to start rather than run that way:
# DatabaseConfigurationValidator queries pg_roles at boot and throws
# SecurityConfigurationException ("Application is using PostgreSQL superuser ...
# Superusers BYPASS Row-Level Security policies"). A copy-paste of the old
# recipe therefore produced a pod that never became READY.
# In .env the two are already separate pairs and it is the FIRST you want:
#   DB_USER / DB_PASSWORD             -> the app role (jtoye_app) — USE THIS
#   POSTGRES_USER / POSTGRES_PASSWORD -> the superuser — do NOT use for the app
# `backup-username=jtoye_backup` below is the deliberate exception: a read-only
# BYPASSRLS role that exists precisely so pg_dump captures rows from FORCE-RLS
# tenant tables (as the app role it would dump 0 rows).
kubectl create secret generic postgres-credentials \
  --from-literal=host=postgresql-primary.jtoye-infrastructure.svc.cluster.local \
  --from-literal=port=5432 \
  --from-literal=database=jtoye \
  --from-literal=username=jtoye_app \
  --from-literal=password="$POSTGRES_PASSWORD" \
  --from-literal=backup-username=jtoye_backup \
  --from-literal=backup-password="$POSTGRES_BACKUP_PASSWORD" \
  -n jtoye-production

# S3 credentials for pg-backup uploads (#90) — use a bucket-limited IAM /
# MinIO service account (PutObject/ListBucket/DeleteObject only).
kubectl create secret generic s3-backup-credentials \
  --from-literal=access-key='YOUR_S3_ACCESS_KEY' \
  --from-literal=secret-key='YOUR_S3_SECRET_KEY' \
  -n jtoye-production

kubectl create secret generic redis-credentials \
  --from-literal=password="$REDIS_PASSWORD" \
  -n jtoye-production

# stomp-login/stomp-passcode: STOMP relay credentials consumed by core-java
# (STOMP_CLIENT_LOGIN / STOMP_CLIENT_PASSCODE) — omit them and pods fail
# with a missing-key error. That operational instruction still holds: those two
# secretKeyRef entries carry no `optional` flag, so a missing KEY inside an
# EXISTING Secret does put the pod into CreateContainerConfigError.
#
# Phase 26 / D-05 correction to what the envs then DO: before Phase 26 neither
# STOMP_CLIENT_LOGIN nor STOMP_CLIENT_PASSCODE was read by any application*.yml,
# so the injected values reached nothing and the relay silently fell back to
# `guest` (the observed boot-time "Access refused for user 'guest'"). After
# Phase 26's additive chain — ${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}} — they
# genuinely feed the relay login, with the RabbitMQ pool credential as the
# fallback and `guest` only when nothing at all is supplied.
#
# `username=jtoye` below is the RabbitMQ BROKER user and is CORRECT — the DEF-2
# NOSUPERUSER correction applies only to the postgres-credentials DB role above.
kubectl create secret generic rabbitmq-credentials \
  --from-literal=username=jtoye \
  --from-literal=password="$RABBITMQ_PASSWORD" \
  --from-literal=stomp-login=jtoye \
  --from-literal=stomp-passcode="$RABBITMQ_PASSWORD" \
  -n jtoye-production

kubectl create secret generic keycloak-credentials \
  --from-literal=admin-username=admin \
  --from-literal=admin-password="$KEYCLOAK_PASSWORD" \
  --from-literal=frontend-client-secret="$FRONTEND_SECRET" \
  --from-literal=core-api-client-secret="$CORE_API_SECRET" \
  -n jtoye-production

kubectl create secret generic nextauth-secret \
  --from-literal=secret="$NEXTAUTH_SECRET" \
  -n jtoye-production

# ---------------------------------------------------------------------------
# OPTIONAL secrets (Phase 26 / DEF-6 / D-15) — create ONLY the ones whose
# feature you are activating in this environment.
#
# core-java references all four with the secretKeyRef `optional` flag set, so
# skipping one does NOT block pod start: the env stays unset, application.yml's
# own default applies, and that feature stays INERT. That is deliberately the
# same behaviour these environments had before Phase 26 supplied the config.
# Creating the Secret is the act that switches the feature on.
# ---------------------------------------------------------------------------

# OPTIONAL — vendor media uploads (Phase 24). Bucket-limited IAM user / MinIO
# service account (GetObject/PutObject/DeleteObject only) for the bucket named by
# app-config s3.bucket. A NARROWER, separate grant from s3-backup-credentials.
kubectl create secret generic s3-media-credentials \
  --from-literal=access-key='YOUR_S3_MEDIA_ACCESS_KEY' \
  --from-literal=secret-key='YOUR_S3_MEDIA_SECRET_KEY' \
  -n jtoye-production

# OPTIONAL — outbound email (Phase 22). SES SMTP credentials (an IAM SMTP user),
# not an IAM access key pair. Creating this alone is NOT enough: also flip
# app-config smtp.auth to "true" in k8s/<env>/configmap-patch.yaml, and verify
# the SES sending domain in the deployment region first — otherwise the first
# send is a loud SMTP auth failure instead of the previous silent no-op.
kubectl create secret generic smtp-credentials \
  --from-literal=username='YOUR_SES_SMTP_USERNAME' \
  --from-literal=password='YOUR_SES_SMTP_PASSWORD' \
  -n jtoye-production

# OPTIONAL — Stripe. api-key is the SECRET key (sk_live_…), never a publishable
# key. webhook-secret (whsec_…) is what inbound Stripe event signatures are
# verified against, so treat it as required once payments are live here.
kubectl create secret generic stripe-credentials \
  --from-literal=api-key='YOUR_STRIPE_SECRET_KEY' \
  --from-literal=webhook-secret='YOUR_STRIPE_WEBHOOK_SECRET' \
  -n jtoye-production

# OPTIONAL but SECURITY-RELEVANT — HMAC signing key for one-click unsubscribe
# links. Its application.yml default is the EMPTY STRING (an HMAC over an empty
# key, which is forgeable), so create this in any environment that sends
# notification email.
export UNSUBSCRIBE_SIGNING_SECRET=$(openssl rand -base64 32)
kubectl create secret generic notification-credentials \
  --from-literal=unsubscribe-signing-secret="$UNSUBSCRIBE_SIGNING_SECRET" \
  -n jtoye-production

# IMPORTANT: Save these passwords securely!
echo "POSTGRES_PASSWORD=$POSTGRES_PASSWORD" >> ~/jtoye-secrets-backup.txt
echo "POSTGRES_BACKUP_PASSWORD=$POSTGRES_BACKUP_PASSWORD" >> ~/jtoye-secrets-backup.txt
echo "REDIS_PASSWORD=$REDIS_PASSWORD" >> ~/jtoye-secrets-backup.txt
echo "RABBITMQ_PASSWORD=$RABBITMQ_PASSWORD" >> ~/jtoye-secrets-backup.txt
echo "KEYCLOAK_PASSWORD=$KEYCLOAK_PASSWORD" >> ~/jtoye-secrets-backup.txt
echo "NEXTAUTH_SECRET=$NEXTAUTH_SECRET" >> ~/jtoye-secrets-backup.txt
echo "FRONTEND_SECRET=$FRONTEND_SECRET" >> ~/jtoye-secrets-backup.txt
echo "CORE_API_SECRET=$CORE_API_SECRET" >> ~/jtoye-secrets-backup.txt
chmod 600 ~/jtoye-secrets-backup.txt
```

---

## Step 2: Deploy Application
```bash
# Preview what will be deployed
kubectl kustomize k8s/production

# Deploy to production
kubectl apply -k k8s/production

# Watch deployment progress
kubectl get pods -n jtoye-production -w
```

---

## Step 3: Verify Deployment
```bash
# Check all resources
kubectl get all,hpa,pdb,ingress -n jtoye-production

# Wait for all pods to be ready (may take 2-5 minutes)
kubectl wait --for=condition=ready pod -l app=core-java -n jtoye-production --timeout=300s
kubectl wait --for=condition=ready pod -l app=edge-go -n jtoye-production --timeout=300s
kubectl wait --for=condition=ready pod -l app=frontend -n jtoye-production --timeout=300s

# Check health
kubectl port-forward svc/core-java 9090:9090 -n jtoye-production &
curl http://localhost:9090/actuator/health
```

---

## Step 4: Configure DNS
```bash
# Get ingress IP
export INGRESS_IP=$(kubectl get svc nginx-ingress-ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "Configure these DNS records:"
echo "api.jtoye.co.uk    A    $INGRESS_IP"
echo "app.jtoye.co.uk    A    $INGRESS_IP"
```

---

## Step 5: Verify TLS Certificates
```bash
# Wait for certificate to be issued (may take 2-3 minutes)
kubectl get certificate -n jtoye-production -w

# Once ready, test
curl -I https://api.jtoye.co.uk
curl -I https://app.jtoye.co.uk
```

---

## Common Commands

### View Logs
```bash
kubectl logs -f deployment/core-java -n jtoye-production
kubectl logs -f deployment/edge-go -n jtoye-production
kubectl logs -f deployment/frontend -n jtoye-production
```

### Scale Manually
```bash
kubectl scale deployment/core-java --replicas=5 -n jtoye-production
```

### Rollback
```bash
kubectl rollout undo deployment/core-java -n jtoye-production
```

### Update Application
```bash
# Edit image tag in k8s/production/kustomization.yaml
kubectl apply -k k8s/production
kubectl rollout status deployment/core-java -n jtoye-production
```

---

## Troubleshooting One-Liners

```bash
# Pods not starting?
kubectl describe pod <pod-name> -n jtoye-production

# Service not accessible?
kubectl get endpoints -n jtoye-production

# TLS not working?
kubectl describe certificate jtoye-tls -n jtoye-production

# Database connection issues?
kubectl logs -f deployment/core-java -n jtoye-production | grep -i "database\|connection"

# High memory usage?
kubectl top pods -n jtoye-production

# Restart a deployment
kubectl rollout restart deployment/core-java -n jtoye-production
```

---

## Resource Monitoring
```bash
# CPU and Memory usage
kubectl top pods -n jtoye-production
kubectl top nodes

# HPA status
kubectl get hpa -n jtoye-production

# Events (troubleshooting)
kubectl get events -n jtoye-production --sort-by='.lastTimestamp' | tail -20
```

---

## Full Documentation
For comprehensive guide, see:
- **DEPLOYMENT.md** - Complete deployment guide (462 lines)
- **PRODUCTION_READINESS_REPORT.md** - Detailed audit report
- **k8s/base/** - Base Kubernetes manifests
- **k8s/production/** - Production overlay
- **k8s/staging/** - Staging overlay

---

## Emergency Contacts
- Technical Lead: devops@jtoye.co.uk
- On-Call: Use PagerDuty/OpsGenie
- Documentation: https://github.com/jtoye/oaas-platform/wiki

---

**Status: Production Ready ✓**
**Version: 2.1.0**
**Last Updated: 2026-07-06**
