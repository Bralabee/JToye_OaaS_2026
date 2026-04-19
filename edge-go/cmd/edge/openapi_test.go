package main

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// --- OpenAPI spec tests ---
//
// These tests enforce the three Phase 16 success criteria that are
// checkable from inside the Go test suite:
//   1. /openapi.json (the committed docs/swagger.json) is valid JSON
//      with the required top-level keys for a Swagger 2.0 document
//      (`openapi-spec-validator` npm accepts both 2.0 and 3.0; the
//      ROADMAP + REQUIREMENTS call for "OpenAPI 3.0" but swaggo v1
//      emits 2.0 — see .planning/phases/16-go-edge-openapi/16-RESEARCH.md
//      for why that tradeoff is acceptable).
//   2. Every Gin route registered in main.go has a corresponding
//      path entry in the spec (route count == annotation count).
//      The assertion is the stricter "path set equality" rather than
//      just count, so a renamed route + a missed annotation would
//      still be caught.
//   3. Freshness: regenerating the spec from source produces a file
//      byte-identical to the committed one. If a developer edits a
//      handler annotation and forgets to re-run `swag init`, this
//      test fails loudly.
//
// Test #3 needs the `swag` binary on PATH. CI installs it; local runs
// skip the freshness check if swag is missing (developer convenience).

// repoRoot walks up from the test file location to find the edge-go
// repo root (the dir containing go.mod).
func repoRoot(t *testing.T) string {
	t.Helper()
	// runtime.Caller(0) gives us the path of this test file.
	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	dir := filepath.Dir(thisFile)
	// cmd/edge/openapi_test.go → walk up twice → edge-go/
	return filepath.Clean(filepath.Join(dir, "..", ".."))
}

// readSpec loads the committed docs/swagger.json as an untyped map.
func readSpec(t *testing.T) map[string]interface{} {
	t.Helper()
	specPath := filepath.Join(repoRoot(t), "docs", "swagger.json")
	b, err := os.ReadFile(specPath)
	if err != nil {
		t.Fatalf("read spec %s: %v", specPath, err)
	}
	var spec map[string]interface{}
	if err := json.Unmarshal(b, &spec); err != nil {
		t.Fatalf("parse spec JSON: %v", err)
	}
	return spec
}

// TestOpenAPISpec_IsValidJSON confirms the committed spec parses and
// carries the top-level keys any OpenAPI / Swagger consumer relies on.
// This is the lightweight in-process substitute for the npm
// `openapi-spec-validator` call — the CI job runs that tool too, but
// we want a fast-feedback check in `go test` as well.
func TestOpenAPISpec_IsValidJSON(t *testing.T) {
	spec := readSpec(t)

	// Swagger 2.0 identifier (swaggo v1 output).
	if v, ok := spec["swagger"].(string); !ok || v != "2.0" {
		// Tolerate future OpenAPI 3.x migration.
		if v, ok := spec["openapi"].(string); !ok || !strings.HasPrefix(v, "3.") {
			t.Errorf("spec must identify as swagger 2.0 or openapi 3.x, got swagger=%v openapi=%v",
				spec["swagger"], spec["openapi"])
		}
	}

	info, ok := spec["info"].(map[string]interface{})
	if !ok {
		t.Fatal("spec.info missing or wrong type")
	}
	if title, ok := info["title"].(string); !ok || title == "" {
		t.Error("spec.info.title missing or empty")
	}
	if version, ok := info["version"].(string); !ok || version == "" {
		t.Error("spec.info.version missing or empty")
	}

	if _, ok := spec["paths"].(map[string]interface{}); !ok {
		t.Error("spec.paths missing or wrong type")
	}
}

// expectedRoutes is the authoritative list of business routes the edge
// gateway exposes. Keeping this in sync with main.go is the whole
// point of swaggo — changing this list without also updating the
// handler annotations is exactly the drift the freshness test
// protects against.
//
// /openapi.json and /docs are documentation endpoints, not business
// surface, and are deliberately excluded. If we ever want them in the
// spec we'd annotate registerDocRoutes and add them here.
var expectedRoutes = map[string]string{
	"/health":                     "get",
	"/ready":                      "get",
	"/api/v1/sync/batch":          "post",
	"/api/v1/webhooks/whatsapp":   "post",
}

// TestOpenAPISpec_AllRoutesDocumented is the route-count assertion from
// ROADMAP success criterion #2: every Gin route has a matching
// @Summary / @Router annotation. We assert path-set equality (not just
// count) so a typo in a @Router tag gets caught.
func TestOpenAPISpec_AllRoutesDocumented(t *testing.T) {
	spec := readSpec(t)
	paths, _ := spec["paths"].(map[string]interface{})

	if len(paths) != len(expectedRoutes) {
		t.Errorf("expected %d documented paths, got %d: spec paths = %v",
			len(expectedRoutes), len(paths), pathKeys(paths))
	}

	for route, method := range expectedRoutes {
		ops, ok := paths[route].(map[string]interface{})
		if !ok {
			t.Errorf("route %s missing from spec", route)
			continue
		}
		if _, ok := ops[method]; !ok {
			t.Errorf("route %s missing %s operation; found operations = %v",
				route, method, opKeys(ops))
		}
	}
}

// TestOpenAPISpec_HasSecurityDefinition ensures the BearerAuth scheme is
// declared (the protected endpoints reference it in their @Security
// annotations).
func TestOpenAPISpec_HasSecurityDefinition(t *testing.T) {
	spec := readSpec(t)
	secDefs, ok := spec["securityDefinitions"].(map[string]interface{})
	if !ok {
		t.Fatal("spec.securityDefinitions missing")
	}
	if _, ok := secDefs["BearerAuth"]; !ok {
		t.Errorf("BearerAuth security definition missing; got keys = %v",
			defKeys(secDefs))
	}
}

// TestOpenAPISpec_Fresh regenerates the spec into a tempdir and diffs it
// against the committed one. If a developer edits a handler annotation
// and forgets to re-run `swag init`, this test fails with a clear
// pointer to what to run.
//
// Skips when `swag` is not on PATH so `go test` stays convenient on
// developer laptops. CI installs swag before running tests.
func TestOpenAPISpec_Fresh(t *testing.T) {
	swagBin, err := exec.LookPath("swag")
	if err != nil {
		// Fall back to ~/go/bin/swag which `go install` uses by default.
		home, herr := os.UserHomeDir()
		if herr == nil {
			candidate := filepath.Join(home, "go", "bin", "swag")
			if _, serr := os.Stat(candidate); serr == nil {
				swagBin = candidate
			}
		}
	}
	if swagBin == "" {
		t.Skip("swag CLI not installed; skipping freshness check. Install with: go install github.com/swaggo/swag/cmd/swag@v1.16.3")
	}

	tmpDir := t.TempDir()
	root := repoRoot(t)

	cmd := exec.Command(swagBin, "init", "-g", "cmd/edge/main.go", "-o", tmpDir, "--quiet")
	cmd.Dir = root
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("swag init failed: %v\noutput:\n%s", err, out)
	}

	committed := readNormalizedSpec(t, filepath.Join(root, "docs", "swagger.json"))
	regenerated := readNormalizedSpec(t, filepath.Join(tmpDir, "swagger.json"))

	if committed != regenerated {
		t.Errorf("docs/swagger.json is stale. Re-run:\n  cd edge-go && swag init -g cmd/edge/main.go -o ./docs\n"+
			"and commit the result.\n\n(committed spec differs from what `swag init` produces right now.)")
	}
}

// readNormalizedSpec reads a swagger.json and re-encodes it with sorted
// keys so a diff isn't polluted by Go map iteration order. swaggo emits
// deterministic key order already, but this is belt-and-braces.
func readNormalizedSpec(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	var v interface{}
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatalf("parse %s: %v", path, err)
	}
	out, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("re-marshal %s: %v", path, err)
	}
	return string(out)
}

// --- test helpers ---

func pathKeys(m map[string]interface{}) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	return keys
}

func opKeys(m map[string]interface{}) []string {
	return pathKeys(m)
}

func defKeys(m map[string]interface{}) []string {
	return pathKeys(m)
}
