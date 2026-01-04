# Docker iptables & nftables Issue - Final Report

**Date:** 2026-01-04
**Issue:** Docker bridge networking blocked by host nftables policy and conflicting Docker daemons.
**Status:** ✅ RESOLVED

---

## Root Cause Analysis

### 1. nftables FORWARD Chain Policy
The host system uses `nftables` as the firewall backend. The default policy for the `FORWARD` chain was set to `drop`, which blocked all inter-container traffic on Docker bridge networks. While Docker attempts to manage `iptables`, these rules were being superseded or ignored by the native `nftables` configuration.

### 2. Conflicting Docker Daemons
Two versions of Docker were found running:
- Standard `docker.service` (Systemd)
- Snap-installed Docker

The Snap version was starting automatically and "squatting" on critical ports (e.g., RabbitMQ 5672, Redis 6379), preventing the primary Docker Compose stack from starting correctly or causing confusing connection errors.

---

## The Permanent Fix

### 1. Fix nftables FORWARD Chain
To allow Docker to manage its own container traffic while keeping the host's overall security policy, we added an `accept` rule to the `DOCKER-USER` chain in `nftables`. This chain is specifically designed for user-defined rules that should be processed before Docker's own rules.

```bash
sudo nft add rule inet filter DOCKER-USER accept
```

### 2. Resolve Daemon Conflicts
We stopped and disabled the Snap-installed Docker daemon to ensure only the primary system Docker is used.

```bash
sudo snap stop docker
sudo snap disable docker
sudo systemctl restart docker
```

---

## Maintenance & Recovery

### Helper Script
A script has been provided to re-apply the network fix if it is lost after a system reboot:
`scripts/fix-bridge-network.sh`

### Verifying the Stack
To verify everything is working correctly:
```bash
docker compose -f docker-compose.full-stack.yml up -d
docker compose -f docker-compose.full-stack.yml ps
```

All services should show `healthy` status and be able to communicate with each other via their container names (e.g., `core-java` connecting to `postgres`).

---

## System Information (At time of fix)
- **OS:** Linux 6.8.0-90-generic
- **Docker:** 24.0.7 (Standard)
- **Firewall:** nftables v1.0.9
- **Network Mode:** Bridge (Standard)
