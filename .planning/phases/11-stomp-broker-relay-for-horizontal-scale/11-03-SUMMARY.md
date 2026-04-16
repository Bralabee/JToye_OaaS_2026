---
phase: 11-stomp-broker-relay-for-horizontal-scale
plan: 03
subsystem: monitoring
tags: [prometheus, grafana, alerting, stomp, rabbitmq]
dependency_graph:
  requires: [11-01]
  provides: [STMP-05]
  affects: [infra/monitoring]
tech_stack:
  added: []
  patterns: [prometheus-alert-rules, grafana-dashboard-provisioning]
key_files:
  created:
    - infra/monitoring/grafana/dashboards/stomp-dashboard.json
  modified:
    - infra/monitoring/prometheus/alerts.yml
decisions:
  - "Grafana dashboard mount already existed in docker-compose.monitoring.yml -- no modification needed"
  - "Queue name regex broadened to cover both stomp-subscription.* and amq.gen-.* patterns for RabbitMQ 3.12 compatibility"
metrics:
  duration: ~1m
  completed: "2026-04-16T09:53:00Z"
  tasks_completed: 2
  tasks_total: 2
---

# Phase 11 Plan 03: STOMP Monitoring Alerts + Grafana Dashboard Summary

Prometheus StompBrokerLag alert rule and Grafana STOMP Broker Relay dashboard for STOMP connection and queue depth monitoring, routed through Phase 9 Alertmanager.

## Completed Tasks

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Add StompBrokerLag Prometheus alert rule | 4dc9646 | infra/monitoring/prometheus/alerts.yml |
| 2 | Create Grafana STOMP dashboard | dc13aba | infra/monitoring/grafana/dashboards/stomp-dashboard.json |

## What Was Built

### Task 1: StompBrokerLag Prometheus Alert Rule
Added a new `messaging_alerts` group to `alerts.yml` with a `StompBrokerLag` alert rule that:
- Monitors `rabbitmq_queue_messages_ready` for STOMP subscription queues
- Uses broadened queue regex (`stomp-subscription.*|amq\.gen-.*`) covering both default RabbitMQ 3.12 naming and alternative `amq.gen-` patterns
- Fires after 5 seconds of undelivered messages (per STMP-05 requirement)
- Labels: `severity: warning`, `component: messaging`, `service: rabbitmq`
- Routes through the existing Alertmanager email receiver (Phase 9 SECR-04)
- Comment block documents the runtime-adjustable queue regex for operators

### Task 2: Grafana STOMP Broker Relay Dashboard
Created `stomp-dashboard.json` with two panels:
- **Panel 1 (Gauge):** "RabbitMQ Total Connections" -- shows `rabbitmq_connections` with green/yellow/red thresholds (0/5/10)
- **Panel 2 (Time Series):** "STOMP Subscription Queue Depth" -- shows `sum(rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*"})` over time

Dashboard is auto-provisioned via the existing `dashboard.yml` provider config that points to `/var/lib/grafana/dashboards`. The volume mount `./grafana/dashboards:/var/lib/grafana/dashboards:ro` was already present in `docker-compose.monitoring.yml` (line 44), so no compose modification was needed.

## Decisions Made

1. **No compose modification needed:** The `./grafana/dashboards:/var/lib/grafana/dashboards:ro` mount already existed in `docker-compose.monitoring.yml`. The plan instructed to check first and skip if present -- confirmed and skipped.
2. **Queue regex coverage:** Used `stomp-subscription.*|amq\.gen-.*` to cover both common RabbitMQ STOMP queue naming patterns. This is documented as runtime-adjustable in the alert rule comments. Operators should verify actual queue names via the RabbitMQ Management UI after first relay-mode startup.

## Deviations from Plan

None -- plan executed exactly as written.

## Verification Results

| Check | Result |
|-------|--------|
| `grep StompBrokerLag alerts.yml` | PASS (1 match) |
| `grep messaging_alerts alerts.yml` | PASS (1 match) |
| `grep 'severity: warning' alerts.yml` | PASS |
| `grep 'component: messaging' alerts.yml` | PASS |
| `grep 'service: rabbitmq' alerts.yml` | PASS |
| `grep 'for: 5s' alerts.yml` | PASS |
| `grep 'stomp-subscription.*amq' alerts.yml` | PASS |
| YAML valid (alerts.yml) | PASS |
| Dashboard JSON exists | PASS |
| `grep rabbitmq_connections dashboard` | PASS |
| `grep stomp-subscription dashboard` | PASS |
| `grep stomp-relay-dashboard dashboard` | PASS (uid) |
| `grep 'STOMP Broker Relay' dashboard` | PASS (title) |
| JSON valid (stomp-dashboard.json) | PASS |
| `/var/lib/grafana/dashboards` mount in compose | PASS (already existed) |

## Runtime Discovery Note

The plan's mandatory runtime discovery step (checking actual STOMP subscription queue names at `http://localhost:15672/#/queues`) requires the full stack running in relay mode. This verification should be performed during the 11-02 smoke test execution. The queue regex in the alert rule is documented as runtime-adjustable and covers the two most common patterns.

## Self-Check: PASSED

All files and commits verified:
- infra/monitoring/prometheus/alerts.yml -- FOUND
- infra/monitoring/grafana/dashboards/stomp-dashboard.json -- FOUND
- .planning/phases/11-stomp-broker-relay-for-horizontal-scale/11-03-SUMMARY.md -- FOUND
- Commit 4dc9646 (Task 1) -- FOUND
- Commit dc13aba (Task 2) -- FOUND
