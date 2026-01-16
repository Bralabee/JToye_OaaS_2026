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
```bash
# Generate secure passwords
export POSTGRES_PASSWORD=$(openssl rand -base64 32)
export REDIS_PASSWORD=$(openssl rand -base64 32)
export RABBITMQ_PASSWORD=$(openssl rand -base64 32)
export KEYCLOAK_PASSWORD=$(openssl rand -base64 32)
export NEXTAUTH_SECRET=$(openssl rand -base64 32)
export FRONTEND_SECRET=$(openssl rand -base64 32)
export CORE_API_SECRET=$(openssl rand -base64 32)

# Create all secrets
kubectl create namespace jtoye-production

kubectl create secret generic postgres-credentials \
  --from-literal=host=postgresql-primary.jtoye-infrastructure.svc.cluster.local \
  --from-literal=port=5432 \
  --from-literal=database=jtoye \
  --from-literal=username=jtoye \
  --from-literal=password="$POSTGRES_PASSWORD" \
  -n jtoye-production

kubectl create secret generic redis-credentials \
  --from-literal=password="$REDIS_PASSWORD" \
  -n jtoye-production

kubectl create secret generic rabbitmq-credentials \
  --from-literal=username=jtoye \
  --from-literal=password="$RABBITMQ_PASSWORD" \
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

# IMPORTANT: Save these passwords securely!
echo "POSTGRES_PASSWORD=$POSTGRES_PASSWORD" >> ~/jtoye-secrets-backup.txt
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
**Version: v0.8.0**
**Last Updated: 2026-01-16**
