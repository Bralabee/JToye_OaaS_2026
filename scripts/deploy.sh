#!/bin/bash
# Deployment script for J'Toye OaaS
# Usage: ./scripts/deploy.sh [environment] [service]
# Examples:
#   ./scripts/deploy.sh production all
#   ./scripts/deploy.sh staging core-java

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

# Validate environment
if [[ ! "$ENVIRONMENT" =~ ^(dev|staging|production)$ ]]; then
    echo -e "${RED}Error: Invalid environment. Use: dev, staging, or production${NC}"
    exit 1
fi

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

# Function to deploy a service
deploy_service() {
    local svc=$1
    echo -e "\n${BLUE}Deploying ${svc}...${NC}"
    
    if [ ! -f "$PROJECT_ROOT/k8s/base/${svc}-deployment.yaml" ]; then
        echo -e "${RED}Error: Deployment file not found for ${svc}${NC}"
        return 1
    fi
    
    # Apply deployment
    kubectl apply -f "$PROJECT_ROOT/k8s/base/${svc}-deployment.yaml" -n "$NAMESPACE"
    
    # Wait for rollout
    echo "Waiting for ${svc} rollout..."
    if ! kubectl rollout status deployment/"${svc}" -n "$NAMESPACE" --timeout=10m; then
        echo -e "${RED}Error: Rollout failed for ${svc}${NC}"
        echo "Rolling back..."
        kubectl rollout undo deployment/"${svc}" -n "$NAMESPACE"
        return 1
    fi
    
    echo -e "${GREEN}✓ ${svc} deployed successfully${NC}"
}

# Deploy services
if [ "$SERVICE" = "all" ]; then
    # Deploy in order: core-java (backend), edge-go (gateway), frontend (UI)
    deploy_service "core-java" || exit 1
    deploy_service "edge-go" || exit 1
    deploy_service "frontend" || exit 1
else
    deploy_service "$SERVICE" || exit 1
fi

# Apply ConfigMap
echo -e "\n${BLUE}Applying ConfigMap...${NC}"
kubectl apply -f "$PROJECT_ROOT/k8s/base/configmap.yaml" -n "$NAMESPACE"

# Apply Ingress
echo -e "\n${BLUE}Applying Ingress...${NC}"
kubectl apply -f "$PROJECT_ROOT/k8s/base/ingress.yaml" -n "$NAMESPACE"

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
