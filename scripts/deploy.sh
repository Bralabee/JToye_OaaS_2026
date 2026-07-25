#!/bin/bash
# Deployment script for J'Toye OaaS — STAGING and PRODUCTION only.
# Usage: ./scripts/deploy.sh [staging|production] [service]
# Examples:
#   ./scripts/deploy.sh production all
#   ./scripts/deploy.sh staging core-java
#
# For a LOCAL Kubernetes rehearsal use scripts/k8s-local-up.sh instead: local
# needs the compose-XOR guard, the out-of-band secret bootstrap and locally built
# images, none of which belong in a staging/production deploy path.

set -e

ENVIRONMENT="${1:-staging}"
SERVICE="${2:-all}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== J'Toye OaaS Deployment ===${NC}"
echo "Environment: $ENVIRONMENT"
echo "Service: $SERVICE"
echo ""

# Validate environment.
#
# Phase 26: `dev` was a PHANTOM target. The old regex accepted it, but there is
# no k8s/dev overlay anywhere in the repo, so the run then applied k8s/base files
# directly into a jtoye-dev namespace with no overlay at all. Local Kubernetes has
# its own committed overlay and its own guarded entry point, so this script is now
# strictly staging/production and says so.
#
# This check deliberately stays ABOVE every cluster call, so a rejected target
# performs no cluster action whatsoever.
case "$ENVIRONMENT" in
    staging|production)
        ;;
    dev)
        echo -e "${RED}Error: 'dev' is not a target of this script.${NC}"
        echo "It never was: no k8s/dev overlay exists anywhere in the repo, so the"
        echo "old code applied k8s/base files with no overlay at all."
        echo "For a local Kubernetes rehearsal use the guarded entry point instead:"
        echo "    scripts/k8s-local-up.sh"
        exit 1
        ;;
    local)
        echo -e "${RED}Error: 'local' is not a target of this script.${NC}"
        echo "The k8s/local overlay does exist, but bringing it up needs the compose-XOR"
        echo "guard, the out-of-band secret bootstrap and locally built images. Use its"
        echo "own entry point, which does all three in order:"
        echo "    scripts/k8s-local-up.sh"
        exit 1
        ;;
    *)
        echo -e "${RED}Error: Invalid environment '${ENVIRONMENT}'. Use: staging or production${NC}"
        exit 1
        ;;
esac

# Validate kubectl access
if ! kubectl cluster-info &> /dev/null; then
    echo -e "${RED}Error: Cannot connect to Kubernetes cluster${NC}"
    echo "Please configure kubectl with: export KUBECONFIG=/path/to/kubeconfig"
    exit 1
fi

NAMESPACE="jtoye-${ENVIRONMENT}"

# Check if namespace exists
if ! kubectl get namespace "$NAMESPACE" &> /dev/null; then
    echo -e "${YELLOW}Warning: Namespace $NAMESPACE does not exist. Creating...${NC}"
    kubectl create namespace "$NAMESPACE"
    kubectl label namespace "$NAMESPACE" environment="$ENVIRONMENT"
fi

# Apply this environment's overlay through kustomize.
#
# Phase 26 fix: this used to be three raw `kubectl apply -f k8s/base/<svc>-deployment.yaml`
# calls plus two more for the base configmap and ingress. Applying base files
# directly BYPASSES KUSTOMIZE ENTIRELY, so every one of those applies silently
# skipped the overlay's namespace transformer, its ConfigMap patch and its
# image-tag pin — i.e. it could push base defaults and an unpinned image into a
# real environment while reporting success. One `apply -k` per environment is the
# only form that deploys what the overlay says.
echo -e "\n${BLUE}Applying k8s/${ENVIRONMENT} overlay...${NC}"
kubectl apply -k "$PROJECT_ROOT/k8s/${ENVIRONMENT}"

# Function to wait for one service's rollout, rolling it back on failure.
deploy_service() {
    local svc=$1
    echo -e "\n${BLUE}Waiting for ${svc} rollout...${NC}"

    if ! kubectl rollout status deployment/"${svc}" -n "$NAMESPACE" --timeout=10m; then
        echo -e "${RED}Error: Rollout failed for ${svc}${NC}"
        echo "Rolling back..."
        kubectl rollout undo deployment/"${svc}" -n "$NAMESPACE"
        return 1
    fi

    echo -e "${GREEN}✓ ${svc} deployed successfully${NC}"
}

# Wait for rollouts. SERVICE still selects which rollout(s) to wait on.
if [ "$SERVICE" = "all" ]; then
    # In order: core-java (backend), edge-go (gateway), frontend (UI)
    deploy_service "core-java" || exit 1
    deploy_service "edge-go" || exit 1
    deploy_service "frontend" || exit 1
else
    deploy_service "$SERVICE" || exit 1
fi

# Display pod status
echo -e "\n${BLUE}Current Pod Status:${NC}"
kubectl get pods -n "$NAMESPACE" -l app="$SERVICE" --field-selector=status.phase=Running

# Display service endpoints
echo -e "\n${BLUE}Service Endpoints:${NC}"
kubectl get svc -n "$NAMESPACE"

# Display ingress
echo -e "\n${BLUE}Ingress Configuration:${NC}"
kubectl get ingress -n "$NAMESPACE"

echo -e "\n${GREEN}✓ Deployment completed successfully!${NC}"
echo -e "\nTo view logs:"
echo -e "  kubectl logs -f deployment/${SERVICE} -n ${NAMESPACE}"
echo -e "\nTo check status:"
echo -e "  kubectl get all -n ${NAMESPACE}"
