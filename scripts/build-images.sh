#!/bin/bash
# Build all Docker images locally
# Usage: ./scripts/build-images.sh [tag]

set -e

TAG="${1:-latest}"
REGISTRY="${REGISTRY:-ghcr.io/jtoye}"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== Building J'Toye OaaS Docker Images ===${NC}"
echo "Tag: $TAG"
echo "Registry: $REGISTRY"
echo ""

# Build core-java
echo -e "${BLUE}Building core-java...${NC}"
docker build -t "${REGISTRY}/core-java:${TAG}" -f core-java/Dockerfile .
echo -e "${GREEN}✓ core-java built${NC}\n"

# Build edge-go
echo -e "${BLUE}Building edge-go...${NC}"
docker build -t "${REGISTRY}/edge-go:${TAG}" -f edge-go/Dockerfile ./edge-go
echo -e "${GREEN}✓ edge-go built${NC}\n"

# Build frontend
echo -e "${BLUE}Building frontend...${NC}"
docker build -t "${REGISTRY}/frontend:${TAG}" -f frontend/Dockerfile ./frontend
echo -e "${GREEN}✓ frontend built${NC}\n"

# Display image sizes
echo -e "${BLUE}Image Sizes:${NC}"
docker images "${REGISTRY}/*:${TAG}" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}"

echo -e "\n${GREEN}✓ All images built successfully!${NC}"
echo -e "\nTo push images to registry:"
echo -e "  docker push ${REGISTRY}/core-java:${TAG}"
echo -e "  docker push ${REGISTRY}/edge-go:${TAG}"
echo -e "  docker push ${REGISTRY}/frontend:${TAG}"
echo -e "\nOr use docker-compose:"
echo -e "  docker-compose -f docker-compose.full-stack.yml up"
