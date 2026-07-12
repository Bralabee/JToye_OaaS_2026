package main

import (
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Prometheus instrumentation for the edge gateway (issue #98 item 3). The Gin
// edge previously carried scrape annotations but exposed no /metrics endpoint,
// so its Prometheus scrape target sat permanently DOWN. These series turn that
// dead target into honest coverage.
var (
	httpRequestsTotal = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "Total HTTP requests handled by the edge gateway, labelled by method, matched route template, and status.",
		},
		[]string{"method", "route", "status"},
	)

	httpRequestDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "HTTP request latency in seconds for the edge gateway, labelled by method, matched route template, and status.",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"method", "route", "status"},
	)
)

// prometheusMiddleware records request count + latency for every request that
// flows through the Gin engine.
//
// CRITICAL (multi-tenancy + cardinality): the `route` label uses the matched
// route TEMPLATE (c.FullPath(), e.g. "/api/v1/sync/batch"), never the raw
// request path (c.Request.URL.Path). A raw path can carry IDs or
// tenant-identifying segments — using it would both explode label cardinality
// and leak tenant-scoped paths into the metrics surface. Unmatched routes
// (404s, scanner traffic) collapse to the constant "unmatched" for the same
// reason.
func prometheusMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()

		route := c.FullPath()
		if route == "" {
			route = "unmatched"
		}
		status := strconv.Itoa(c.Writer.Status())
		method := c.Request.Method

		httpRequestsTotal.WithLabelValues(method, route, status).Inc()
		httpRequestDuration.WithLabelValues(method, route, status).Observe(time.Since(start).Seconds())
	}
}

// metricsHandler serves the Prometheus exposition text over the default
// registry. Kept public (no JWT) so Prometheus can scrape it; the 15s scrape
// interval sits well under the DoS rate-limiter ceiling, so no exclusion is
// needed.
func metricsHandler() gin.HandlerFunc {
	return gin.WrapH(promhttp.Handler())
}
