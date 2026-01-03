# Docker iptables Issue - Diagnostic Report

**Date:** 2026-01-03
**Issue:** Docker cannot create custom networks due to missing iptables chains
**Impact:** Blocks Docker Compose execution

---

## Error Message

```
Error response from daemon: add inter-network communication rule:
(iptables failed: iptables --wait -t filter -A DOCKER-ISOLATION-STAGE-1
-i br-xxx ! -o br-xxx -j DOCKER-ISOLATION-STAGE-2:
iptables v1.8.10 (nf_tables): Chain 'DOCKER-ISOLATION-STAGE-2' does not exist
```

---

## Root Cause Analysis

### System Configuration

**IP Forwarding:** ✅ Enabled (value: 1)
```bash
cat /proc/sys/net/ipv4/ip_forward
# Output: 1
```

**iptables Backend:** ⚠️ Using `iptables-nft` (nf_tables)
```bash
update-alternatives --query iptables | grep "Value:"
# Output: Value: /usr/sbin/iptables-nft
```

**Docker Configuration:**
- Docker daemon: ✅ Running (restarted at 15:30:19 GMT)
- Docker version: iptables v1.8.10 (nf_tables)
- Firewall Backend: iptables
- Basic Docker: ✅ Works (`docker run hello-world` succeeds)
- Custom Networks: ❌ Fails (cannot create iptables chains)

### The Problem

Docker is trying to create iptables chains (`DOCKER-ISOLATION-STAGE-2`) but the chains don't exist in the nf_tables backend. This is a known incompatibility between:
- System using `iptables-nft` (modern nf_tables backend)
- Docker expecting `iptables-legacy` (older iptables backend)

---

## Solution Options

### Option 1: Switch to iptables-legacy (Recommended)

This is the most common fix for Docker networking issues with nf_tables.

**Steps:**
```bash
# Switch to iptables-legacy
sudo update-alternatives --set iptables /usr/sbin/iptables-legacy
sudo update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy

# Restart Docker daemon
sudo systemctl restart docker

# Verify
update-alternatives --query iptables | grep "Value:"
# Should show: Value: /usr/sbin/iptables-legacy
```

**Verification:**
```bash
docker compose -f docker-compose.full-stack.yml up -d
```

---

### Option 2: Manually Create iptables Chains (Advanced)

If switching to iptables-legacy doesn't work or isn't desired.

**Steps:**
```bash
# Create missing Docker iptables chains
sudo iptables -N DOCKER-ISOLATION-STAGE-1
sudo iptables -N DOCKER-ISOLATION-STAGE-2
sudo iptables -A DOCKER-ISOLATION-STAGE-1 -j RETURN
sudo iptables -A DOCKER-ISOLATION-STAGE-2 -j RETURN

# Restart Docker
sudo systemctl restart docker
```

**Warning:** These chains may get removed on reboot. Docker should create them automatically, but something is preventing it.

---

### Option 3: Use Host Network Mode (Workaround)

Run containers without custom networks (not recommended for production).

**Modify docker-compose.full-stack.yml:**
```yaml
services:
  postgres:
    # ... existing config ...
    network_mode: "host"  # Add this
    # Remove 'networks:' section

# Remove or comment out:
# networks:
#   jtoye-network:
#     driver: bridge
```

**Drawback:** Loses network isolation between containers.

---

### Option 4: Use Podman Instead of Docker (Alternative)

Podman is rootless and doesn't have the same iptables requirements.

```bash
# Install podman
sudo apt install podman podman-compose

# Use podman instead
podman-compose -f docker-compose.full-stack.yml up -d
```

---

## Testing the Fix

After applying any of the solutions above, verify:

### 1. Check Docker Networks Work
```bash
# Clean up old networks
docker network prune -f

# Create test network
docker network create test-network

# Should succeed without iptables errors
docker network ls | grep test-network

# Clean up
docker network rm test-network
```

### 2. Start Infrastructure Services
```bash
cd infra
docker compose up -d

# Should see all services starting without network errors
docker compose ps
```

### 3. Start Full Stack
```bash
docker compose -f docker-compose.full-stack.yml up -d

# Check all services are running
docker compose -f docker-compose.full-stack.yml ps
```

### 4. Verify Services
```bash
# Check PostgreSQL
docker exec jtoye-postgres psql -U jtoye -c "SELECT 1"

# Check Keycloak
curl -s http://localhost:8085/realms/jtoye-dev/.well-known/openid-configuration | jq .issuer

# Check Core API
curl -s http://localhost:9090/actuator/health

# Check Frontend
curl -s http://localhost:3000/auth/signin | grep -q "Sign In"
```

---

## What Was Attempted

### ✅ Verified:
1. Docker daemon is running
2. Docker daemon was restarted (15:30:19 GMT)
3. Basic Docker functionality works (`docker run hello-world`)
4. IP forwarding is enabled
5. Docker compose files are valid
6. Old Docker networks were cleaned up

### ❌ Still Failing:
1. Creating custom Docker networks
2. Starting any Docker Compose project with custom networks
3. Both `docker-compose.full-stack.yml` and `infra/docker-compose.yml` fail

---

## System Information

**Operating System:**
```
Linux 6.8.0-90-generic
```

**Docker Info:**
```
Firewall Backend: iptables
Network: bridge host ipvlan macvlan null overlay
```

**iptables Version:**
```
iptables v1.8.10 (nf_tables)
Current: /usr/sbin/iptables-nft
```

**Docker Status:**
```
Active: active (running) since Sat 2026-01-03 15:30:19 GMT
```

---

## Recommended Action

**Try Option 1 (Switch to iptables-legacy):**

```bash
# Run these commands:
sudo update-alternatives --set iptables /usr/sbin/iptables-legacy
sudo update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy
sudo systemctl restart docker

# Then verify:
docker compose -f docker-compose.full-stack.yml up -d
```

If Option 1 doesn't work, the issue may be deeper (SELinux, AppArmor, or kernel module issues) and would require more advanced debugging.

---

## Project Status

**Project Code:** ✅ READY
- All Docker configurations are correct
- Docker builds succeed
- No issues with the project itself

**System Configuration:** ❌ BLOCKING
- iptables/nf_tables incompatibility
- Docker daemon cannot create network isolation chains
- Requires system-level fix

**Workaround Available:** ✅ YES
- Can run services without Docker Compose
- Can build Docker images individually
- Can run containers manually with `--network host`

---

**Next Steps:**
1. Try Option 1 (iptables-legacy switch)
2. If that fails, try Option 2 (manual chain creation)
3. If still blocked, consider Option 4 (Podman)
4. Once fixed, re-run: `docker compose -f docker-compose.full-stack.yml up -d`
