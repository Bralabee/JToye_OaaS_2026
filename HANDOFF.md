# Session Handoff — J'Toye OaaS v1.3.0

**Date**: 2026-04-01  
**Branch**: `main`  
**Tag**: `v1.3.0`

---

## What Was Completed (11 PRs)

- **#2**: Version alignment (OpenAPI 1.2.0, README badge)
- **#3**: Order detail dialog with line items
- **#4**: RabbitMQ order state change consumer
- **#5**: Financial reporting — summary endpoint + Finance dashboard
- **#6**: Product price field + order total NaN fix
- **#7**: Product price column + docs freshness audit
- **#8**: Dashboard charts, customer orders endpoint, backend search
- **#9**: Server-side search wiring, customer order filtering
- **#10**: Removed 18 unused Java imports/variables
- **#11**: Real-time SSE, WhatsApp message parser, allergen label PDFs

## Remaining Work

- **Email notifications**: RabbitMQ consumer has extension points but no SMTP integration
- **Customer storefront**: No public shop pages — back-office only
- **Self-service signup**: Manual tenant provisioning (SQL + Keycloak admin)
- **Payments**: No Stripe/payment processing
- **Delivery management**: No driver tracking or route planning
- **WhatsApp order creation**: Parser exists but not wired to Core API order creation

## Environment

- **Docker**: 7 containers (postgres, keycloak, redis, rabbitmq, core-java, edge-go, frontend)
- **Java**: `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- **Tests**: 120+ Java unit, 43 Jest, 6+ Go tests
- **Keycloak**: `tenant-a-user` / `password123`, client: `test-client`
