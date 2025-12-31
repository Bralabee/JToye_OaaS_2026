# JToye OaaS Monitoring Stack

## Overview

Production-ready monitoring stack using Prometheus for metrics collection and Grafana for visualization. Eliminates operational blind spots with comprehensive metrics, alerts, and dashboards.

## Architecture

```
┌─────────────────┐      ┌──────────────┐      ┌──────────────┐
│  Spring Boot    │─────>│  Prometheus  │─────>│   Grafana    │
│  (Actuator)     │      │  (Metrics)   │      │ (Dashboards) │
└─────────────────┘      └──────────────┘      └──────────────┘
         │                       │
         │                       │
    ┌────┴────┐           ┌──────┴──────┐
    │PostgreSQL│           │   Alerts    │
    │ Exporter │           │  (Rules)    │
    └─────────┘           └─────────────┘
```

## Components

### 1. **Prometheus** (Port 9091)
- **Purpose**: Time-series metrics database
- **Retention**: 30 days
- **Scrape Interval**: 15 seconds
- **Targets**:
  - core-java API (Spring Boot Actuator)
  - edge-go API
  - PostgreSQL (via exporter)
  - RabbitMQ, Redis (if exporters added)

### 2. **Grafana** (Port 3001)
- **Purpose**: Visualization and dashboards
- **Credentials**: admin / admin123 (CHANGE IN PRODUCTION!)
- **Features**:
  - Pre-configured Prometheus datasource
  - Auto-provisioned dashboards
  - Real-time metrics visualization

### 3. **postgres-exporter** (Port 9187)
- **Purpose**: PostgreSQL metrics collection
- **Metrics**: Connection pool, queries, locks, replication

## Quick Start

### 1. Start Monitoring Stack

```bash
cd infra/monitoring
docker-compose -f docker-compose.monitoring.yml up -d
```

### 2. Verify Services

```bash
# Check all services are running
docker-compose -f docker-compose.monitoring.yml ps

# Check Prometheus is scraping targets
curl http://localhost:9091/api/v1/targets

# Check Grafana is accessible
curl http://localhost:3001/api/health
```

### 3. Access Dashboards

- **Grafana**: http://localhost:3001 (admin/admin123)
- **Prometheus**: http://localhost:9091

### 4. Stop Monitoring Stack

```bash
docker-compose -f docker-compose.monitoring.yml down

# To remove data volumes
docker-compose -f docker-compose.monitoring.yml down -v
```

## Metrics Exposed

### Spring Boot Actuator (core-java)

**Endpoint**: `http://localhost:9090/actuator/prometheus`

**Key Metrics:**
- `http_server_requests_seconds_*` - API response times (P50, P95, P99)
- `jvm_memory_*` - JVM heap, non-heap memory usage
- `jvm_gc_*` - Garbage collection metrics
- `hikaricp_connections_*` - Database connection pool
- `system_cpu_*` - CPU usage
- `process_uptime_seconds` - Application uptime

### PostgreSQL Exporter

**Key Metrics:**
- `pg_stat_database_*` - Database statistics
- `pg_stat_bgwriter_*` - Background writer stats
- `pg_stat_activity_*` - Active connections
- `pg_locks_*` - Database locks
- `pg_stat_user_tables_*` - Table-level statistics

## Alerts Configuration

### Critical Alerts (Immediate Action Required)

1. **HighErrorRate** (>5% for 5 minutes)
   - Severity: Critical
   - Action: Check application logs, investigate errors

2. **ServiceDown** (any service unreachable for 2 minutes)
   - Severity: Critical
   - Action: Check service health, restart if needed

3. **DatabaseConnectionPoolExhausted** (>90% for 5 minutes)
   - Severity: Critical
   - Action: Investigate slow queries, increase pool size

4. **DatabaseDown** (PostgreSQL unreachable for 1 minute)
   - Severity: Critical
   - Action: Check database service, network connectivity

### Warning Alerts (Monitor Closely)

5. **HighResponseTime** (P95 > 1s for 5 minutes)
   - Severity: Warning
   - Action: Investigate slow endpoints, optimize queries

6. **HighMemoryUsage** (JVM heap >85% for 5 minutes)
   - Severity: Warning
   - Action: Monitor for memory leaks, consider heap increase

7. **FrequentGarbageCollection** (>10 GC/second for 5 minutes)
   - Severity: Warning
   - Action: Tune JVM GC settings, investigate memory pressure

### Info Alerts (Awareness)

8. **NoOrdersCreated** (no orders in 30 minutes)
   - Severity: Info
   - Action: Check if this is expected business behavior

## Dashboard Recommendations

### 1. Application Overview Dashboard
- Request rate (requests/sec)
- Error rate (% errors)
- Response time (P50, P95, P99)
- Active requests

### 2. JVM Dashboard
- Heap memory usage over time
- GC pause time
- Thread count
- CPU usage

### 3. Database Dashboard
- Active connections
- Query execution time
- Lock wait time
- Cache hit ratio

### 4. Business Metrics Dashboard
- Orders created per hour
- Average order value
- Active tenants
- API usage by tenant

## Integration with Full Stack

### Option 1: Standalone Monitoring
```bash
# Start main application
docker-compose -f docker-compose.full-stack.yml up -d

# Start monitoring (separate network)
cd infra/monitoring
docker-compose -f docker-compose.monitoring.yml up -d
```

### Option 2: Integrated Monitoring (Recommended)
Merge monitoring services into `docker-compose.full-stack.yml` for unified deployment.

## Production Considerations

### Security

1. **Change default credentials**:
   ```yaml
   # In docker-compose.monitoring.yml
   GF_SECURITY_ADMIN_PASSWORD: <strong-password>
   ```

2. **Enable authentication on Prometheus**:
   - Add basic auth
   - Use reverse proxy (nginx) with TLS

3. **Restrict network access**:
   - Only expose Grafana externally
   - Keep Prometheus internal

### High Availability

1. **Prometheus HA**:
   - Run 2+ Prometheus instances
   - Use Thanos for long-term storage

2. **Grafana HA**:
   - Use external database (not SQLite)
   - Run multiple Grafana instances behind load balancer

### Data Retention

1. **Prometheus**:
   - Current: 30 days
   - Adjust via `--storage.tsdb.retention.time`

2. **Long-term storage**:
   - Export to S3/GCS via Thanos
   - Or use Cortex for centralized storage

## Troubleshooting

### Prometheus not scraping targets

**Problem**: Targets show as "Down" in Prometheus UI

**Solutions**:
1. Check network connectivity:
   ```bash
   docker exec jtoye-prometheus wget -O- http://jtoye-core-java:9090/actuator/prometheus
   ```

2. Verify actuator is enabled in Spring Boot:
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: prometheus
   ```

3. Check Prometheus logs:
   ```bash
   docker logs jtoye-prometheus
   ```

### Grafana can't connect to Prometheus

**Problem**: "Bad Gateway" or connection errors in Grafana

**Solutions**:
1. Verify both services are on same network:
   ```bash
   docker network inspect monitoring
   ```

2. Test connectivity from Grafana container:
   ```bash
   docker exec jtoye-grafana wget -O- http://prometheus:9090/-/healthy
   ```

3. Check datasource configuration in Grafana UI

### High cardinality metrics

**Problem**: Prometheus using too much memory/disk

**Solutions**:
1. Reduce retention time
2. Drop high-cardinality labels:
   ```yaml
   # In prometheus.yml
   metric_relabel_configs:
     - regex: 'request_id|trace_id'
       action: labeldrop
   ```

3. Use recording rules to pre-aggregate

## Capacity Planning

### Resource Requirements

**Development/Testing:**
- Prometheus: 512MB RAM, 10GB disk
- Grafana: 256MB RAM, 1GB disk
- PostgreSQL Exporter: 128MB RAM

**Production:**
- Prometheus: 2-4GB RAM, 50-100GB disk (for 30 days retention)
- Grafana: 512MB-1GB RAM, 5GB disk
- PostgreSQL Exporter: 256MB RAM

### Scaling Considerations

Monitor these metrics to know when to scale:
- **Prometheus ingestion rate**: <100k samples/sec per instance
- **Prometheus query latency**: <1s for dashboards
- **Disk usage**: Plan for 80% retention utilization max

## Backup and Recovery

### Prometheus Data

**Backup**:
```bash
# Prometheus stores data in TSDB format
docker run --rm -v prometheus_data:/data -v $(pwd):/backup \
  alpine tar czf /backup/prometheus-backup-$(date +%Y%m%d).tar.gz /data
```

**Restore**:
```bash
docker run --rm -v prometheus_data:/data -v $(pwd):/backup \
  alpine tar xzf /backup/prometheus-backup-YYYYMMDD.tar.gz -C /
```

### Grafana Dashboards

**Export all dashboards** via UI or API:
```bash
curl -u admin:admin123 http://localhost:3001/api/search?query=& | \
  jq -r '.[] | .uid' | \
  xargs -I {} curl -u admin:admin123 \
  http://localhost:3001/api/dashboards/uid/{} > dashboard-{}.json
```

## Related Documentation

- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [PostgreSQL Exporter](https://github.com/prometheus-community/postgres_exporter)

## Next Steps

1. ✅ Deploy monitoring stack
2. ⏭️ Create custom Grafana dashboards
3. ⏭️ Configure Alertmanager for notifications
4. ⏭️ Set up log aggregation (Loki + Promtail)
5. ⏭️ Implement distributed tracing (Jaeger/Tempo)

---

**Version**: 1.0.0
**Last Updated**: 2025-12-31
**Maintainer**: JToye DevOps Team
