package main

import (
	"github.com/gin-gonic/gin"
)

// registerDocRoutes wires up the OpenAPI spec endpoint (/openapi.json) and
// the Swagger UI (/docs). Task 16-02 ships the handler annotations; task
// 16-03 replaces this stub with real swaggo + gin-swagger wiring and
// imports the generated `docs` package.
//
// Keeping it as a no-op in this intermediate commit means:
//   - The edge builds and tests pass between tasks.
//   - main.go doesn't have to be edited twice (once to add the call, again
//     to re-wire it).
func registerDocRoutes(r *gin.Engine) {
	// stub — real implementation lands in task 16-03 along with the
	// generated docs package and Swagger UI middleware.
	_ = r
}
