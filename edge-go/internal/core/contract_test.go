package core

import (
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// TestEdgeCoreContract is the edge↔core contract gate (issue #337).
//
// It compares the Go types and endpoints this package actually puts on the
// wire against core-java's reviewed OpenAPI snapshot, and fails naming the
// exact field or path that diverged. See contract.go for the manifest and
// the rationale.
//
// Deliberately ONE top-level Test func with subtests: the subtests name the
// failure mode, while the single entry point keeps the wrapper script's
// "did the check actually run?" assertion simple and exact.
//
// Every failure here is fatal to the build. There is no skip path: a missing
// or unparseable snapshot, an empty manifest, or a source scan that finds
// nothing are all VOID, and VOID fails — "found nothing" is never "clean".
func TestEdgeCoreContract(t *testing.T) {
	spec := loadCoreSnapshot(t)
	calls := EdgeCoreCalls()
	if len(calls) == 0 {
		t.Fatal("VOID: EdgeCoreCalls() is empty — the manifest cannot vouch for anything")
	}

	t.Run("declared calls match core's OpenAPI snapshot", func(t *testing.T) {
		for _, call := range calls {
			if call.Unrouted {
				continue
			}
			checkCall(t, spec, call)
		}
	})

	t.Run("unrouted calls are documented and have no caller", func(t *testing.T) {
		for _, call := range calls {
			if !call.Unrouted {
				continue
			}
			if strings.TrimSpace(call.Note) == "" {
				t.Errorf("%s: Unrouted entries must carry a Note saying why", call.Name)
			}
			method := call.Name
			if i := strings.LastIndex(method, "."); i >= 0 {
				method = method[i+1:]
			}
			callers := productionCallersOf(t, method)
			if len(callers) > 0 {
				t.Errorf("%s targets %s %s, which core does NOT expose, but it is now called from %v.\n"+
					"Agree a core endpoint (or point the call at an existing one) before wiring this up.\nNote: %s",
					call.Name, strings.ToUpper(call.Method), call.Path, callers, call.Note)
			}
		}
	})

	// The completeness pair. Both directions run every time: source→manifest
	// catches an undeclared new call, manifest→source proves the scanner can
	// see at all, so a scanner that silently stops matching cannot read clean.
	t.Run("every endpoint literal is declared, and every declared path is found", func(t *testing.T) {
		found := endpointLiteralsInPackage(t)
		if len(found) == 0 {
			t.Fatal("VOID: the source scan found no endpoint literals in this package — " +
				"the scanner is broken, not the code")
		}

		prefixes := make(map[string]string, len(calls)) // literal prefix -> call name
		for _, call := range calls {
			prefixes[pathLiteralPrefix(call.Path)] = call.Name
		}

		for _, lit := range found {
			if matchDeclared(prefixes, lit) == "" {
				t.Errorf("endpoint %q is built in this package but is not declared in EdgeCoreCalls().\n"+
					"Add it to contract.go so its request/response shapes are checked against core's snapshot.", lit)
			}
		}

		for prefix, name := range prefixes {
			seen := false
			for _, lit := range found {
				if lit == prefix || strings.HasPrefix(lit, prefix) {
					seen = true
					break
				}
			}
			if !seen {
				t.Errorf("declared call %s claims path prefix %q, but no such literal exists in this package's source.\n"+
					"Either the call was removed (drop it from contract.go) or the scanner has stopped seeing it.",
					name, prefix)
			}
		}
	})
}

// --- core snapshot loading -------------------------------------------------

// repoRootFromHere walks up from this test file to the monorepo root (the
// directory holding docs/api/openapi-snapshot.json). Located by file position
// rather than the working directory so `go test ./...` from any cwd works.
func repoRootFromHere(t *testing.T) string {
	t.Helper()
	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("VOID: runtime.Caller failed; cannot locate the repo root")
	}
	// edge-go/internal/core/contract_test.go -> up 3 -> edge-go -> up 1 -> root
	return filepath.Clean(filepath.Join(filepath.Dir(thisFile), "..", "..", ".."))
}

func loadCoreSnapshot(t *testing.T) map[string]any {
	t.Helper()
	path := filepath.Join(repoRootFromHere(t), "docs", "api", "openapi-snapshot.json")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("VOID: cannot read core's reviewed OpenAPI snapshot at %s: %v\n"+
			"Regenerate with: ./gradlew :core-java:updateOpenApiSnapshot", path, err)
	}
	var spec map[string]any
	if err := json.Unmarshal(b, &spec); err != nil {
		t.Fatalf("VOID: %s is not parseable JSON: %v", path, err)
	}
	paths, _ := spec["paths"].(map[string]any)
	if len(paths) == 0 {
		t.Fatalf("VOID: %s declares no paths — an empty spec would make every check below vacuous", path)
	}
	return spec
}

// --- per-call checks -------------------------------------------------------

func checkCall(t *testing.T, spec map[string]any, call EdgeCoreCall) {
	t.Helper()

	paths, _ := spec["paths"].(map[string]any)
	item, ok := paths[call.Path].(map[string]any)
	if !ok {
		t.Errorf("%s: core's snapshot has no path %q. Available near-matches: %v",
			call.Name, call.Path, nearbyPaths(paths, call.Path))
		return
	}
	op, ok := item[call.Method].(map[string]any)
	if !ok {
		t.Errorf("%s: core's snapshot has path %q but no %s operation (has: %v)",
			call.Name, call.Path, strings.ToUpper(call.Method), sortedKeys(item))
		return
	}

	checkQueryParams(t, call, op)
	checkAcceptedStatus(t, call, op)

	if call.Request != nil {
		schema := requestSchema(op)
		if schema == nil {
			t.Errorf("%s: sends a JSON body but core declares no application/json requestBody for %s %s",
				call.Name, strings.ToUpper(call.Method), call.Path)
		} else {
			compare(t, spec, call.Name+" request", reflect.TypeOf(call.Request), schema, true)
		}
	}

	if call.Response != nil {
		schema := responseSchema(op, call.AcceptedStatus)
		if schema == nil {
			t.Errorf("%s: decodes a response body but core declares no application/json response schema for %v",
				call.Name, call.AcceptedStatus)
		} else {
			compare(t, spec, call.Name+" response", reflect.TypeOf(call.Response), schema, false)
		}
	}
}

func checkQueryParams(t *testing.T, call EdgeCoreCall, op map[string]any) {
	t.Helper()
	declared := map[string]bool{} // name -> required
	if raw, ok := op["parameters"].([]any); ok {
		for _, p := range raw {
			pm, ok := p.(map[string]any)
			if !ok || pm["in"] != "query" {
				continue
			}
			name, _ := pm["name"].(string)
			req, _ := pm["required"].(bool)
			declared[name] = req
		}
	}
	sent := map[string]bool{}
	for _, q := range call.Query {
		sent[q] = true
		if _, ok := declared[q]; !ok {
			t.Errorf("%s: sends query param %q, which core does not declare on %s %s (declares: %v)",
				call.Name, q, strings.ToUpper(call.Method), call.Path, sortedBoolKeys(declared))
		}
	}
	for name, required := range declared {
		if required && !sent[name] {
			t.Errorf("%s: core marks query param %q REQUIRED on %s %s, but the edge never sends it",
				call.Name, name, strings.ToUpper(call.Method), call.Path)
		}
	}
}

func checkAcceptedStatus(t *testing.T, call EdgeCoreCall, op map[string]any) {
	t.Helper()
	responses, _ := op["responses"].(map[string]any)
	if len(responses) == 0 {
		t.Errorf("%s: core declares no responses at all for %s %s", call.Name, strings.ToUpper(call.Method), call.Path)
		return
	}
	for _, code := range call.AcceptedStatus {
		if _, ok := responses[strconv.Itoa(code)]; ok {
			return
		}
	}
	t.Errorf("%s: treats %v as success, but core declares none of them for %s %s (declares: %v)",
		call.Name, call.AcceptedStatus, strings.ToUpper(call.Method), call.Path, sortedKeys(responses))
}

func requestSchema(op map[string]any) map[string]any {
	body, ok := op["requestBody"].(map[string]any)
	if !ok {
		return nil
	}
	content, ok := body["content"].(map[string]any)
	if !ok {
		return nil
	}
	mt, ok := content["application/json"].(map[string]any)
	if !ok {
		return nil
	}
	s, _ := mt["schema"].(map[string]any)
	return s
}

func responseSchema(op map[string]any, accepted []int) map[string]any {
	responses, ok := op["responses"].(map[string]any)
	if !ok {
		return nil
	}
	for _, code := range accepted {
		r, ok := responses[strconv.Itoa(code)].(map[string]any)
		if !ok {
			continue
		}
		content, ok := r["content"].(map[string]any)
		if !ok {
			continue
		}
		mt, ok := content["application/json"].(map[string]any)
		if !ok {
			// springdoc emits */* for some operations.
			for _, v := range content {
				if m, ok := v.(map[string]any); ok {
					if s, ok := m["schema"].(map[string]any); ok {
						return s
					}
				}
			}
			continue
		}
		if s, ok := mt["schema"].(map[string]any); ok {
			return s
		}
	}
	return nil
}

// --- Go type vs OpenAPI schema --------------------------------------------

// compare walks a Go type against a core schema, recursing through nested
// structs and slices. Direction matters:
//
//	requestSide=true  — every Go field must exist in core (else core ignores
//	                    what we send) AND every core-required property must
//	                    exist in Go (else core rejects the call).
//	requestSide=false — every Go field must exist in core (else we decode a
//	                    zero value and never notice). Core returning MORE than
//	                    the edge reads is fine and expected.
func compare(t *testing.T, spec map[string]any, where string, goType reflect.Type, schema map[string]any, requestSide bool) {
	t.Helper()

	for goType.Kind() == reflect.Pointer {
		goType = goType.Elem()
	}
	schema = resolveRef(t, spec, where, schema)
	if schema == nil {
		return
	}

	if goType.Kind() == reflect.Slice || goType.Kind() == reflect.Array {
		items, ok := schema["items"].(map[string]any)
		if !ok {
			t.Errorf("%s: the edge expects a JSON array, core's schema is not an array (%v)", where, sortedKeys(schema))
			return
		}
		compare(t, spec, where+"[]", goType.Elem(), items, requestSide)
		return
	}
	if goType.Kind() != reflect.Struct {
		return // scalars and maps carry no field names to compare
	}
	if _, isArray := schema["items"]; isArray {
		if st, _ := schema["type"].(string); st == "array" {
			t.Errorf("%s: core returns an array here, the edge decodes a single %s", where, goType.Name())
			return
		}
	}

	props, _ := schema["properties"].(map[string]any)
	if len(props) == 0 {
		t.Errorf("VOID: %s: core's schema declares no properties, so no field could ever be checked (%v)",
			where, sortedKeys(schema))
		return
	}

	goFields := map[string]reflect.StructField{}
	for i := 0; i < goType.NumField(); i++ {
		f := goType.Field(i)
		name, skip := jsonName(f)
		if skip {
			continue
		}
		goFields[name] = f
	}

	for name, f := range goFields {
		sub, ok := props[name].(map[string]any)
		if !ok {
			t.Errorf("%s: Go field %s.%s is tagged json:%q, which core's schema does not declare.\n"+
				"  core declares: %v\n"+
				"  A field core does not know is silently dropped on send and decodes to a zero value on receive.",
				where, goType.Name(), f.Name, name, sortedKeys(props))
			continue
		}
		ft := f.Type
		for ft.Kind() == reflect.Pointer {
			ft = ft.Elem()
		}
		if ft.Kind() == reflect.Struct || ft.Kind() == reflect.Slice || ft.Kind() == reflect.Array {
			if ft.Kind() != reflect.Struct && ft.Elem().Kind() != reflect.Struct {
				continue // []string, []byte, ... — nothing named to recurse into
			}
			compare(t, spec, where+"."+name, ft, sub, requestSide)
		}
	}

	if requestSide {
		if required, ok := schema["required"].([]any); ok {
			for _, r := range required {
				name, _ := r.(string)
				if _, ok := goFields[name]; !ok {
					t.Errorf("%s: core marks property %q REQUIRED, but %s has no field tagged json:%q",
						where, name, goType.Name(), name)
				}
			}
		}
	}
}

// resolveRef follows a single $ref into components/schemas. Returns nil after
// reporting when the ref dangles, so a broken snapshot fails rather than
// quietly checking an empty schema.
func resolveRef(t *testing.T, spec map[string]any, where string, schema map[string]any) map[string]any {
	t.Helper()
	seen := 0
	for {
		ref, ok := schema["$ref"].(string)
		if !ok {
			return schema
		}
		seen++
		if seen > 10 {
			t.Errorf("VOID: %s: $ref chain in core's snapshot does not terminate (%s)", where, ref)
			return nil
		}
		const prefix = "#/components/schemas/"
		if !strings.HasPrefix(ref, prefix) {
			t.Errorf("VOID: %s: unsupported $ref %q in core's snapshot", where, ref)
			return nil
		}
		components, _ := spec["components"].(map[string]any)
		schemas, _ := components["schemas"].(map[string]any)
		next, ok := schemas[strings.TrimPrefix(ref, prefix)].(map[string]any)
		if !ok {
			t.Errorf("VOID: %s: core's snapshot has a dangling $ref %q", where, ref)
			return nil
		}
		schema = next
	}
}

// jsonName returns the wire name of a struct field and whether it is skipped.
func jsonName(f reflect.StructField) (string, bool) {
	if f.PkgPath != "" {
		return "", true // unexported
	}
	tag := f.Tag.Get("json")
	if tag == "-" {
		return "", true
	}
	name := strings.Split(tag, ",")[0]
	if name == "" {
		name = f.Name
	}
	return name, false
}

// --- source scanning -------------------------------------------------------

// packageDir is the directory holding this package's sources.
func packageDir(t *testing.T) string {
	t.Helper()
	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("VOID: runtime.Caller failed; cannot locate the package source")
	}
	return filepath.Dir(thisFile)
}

// endpointLiteralsInPackage parses this package's non-test sources with go/ast
// and returns every string literal that looks like a core endpoint path,
// normalised (leading format verb and query string stripped).
//
// Deliberately an AST parse, not a text search: in this environment `grep`
// and `rg` are shell functions that do not exist inside a script or under
// exec, where they return rc=127 and zero output — indistinguishable from a
// clean result. go/parser cannot fail that way, and a parse error is fatal.
func endpointLiteralsInPackage(t *testing.T) []string {
	t.Helper()
	dir := packageDir(t)
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("VOID: cannot read package dir %s: %v", dir, err)
	}

	fset := token.NewFileSet()
	set := map[string]bool{}
	parsed := 0
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		f, err := parser.ParseFile(fset, filepath.Join(dir, name), nil, 0)
		if err != nil {
			t.Fatalf("VOID: cannot parse %s: %v", name, err)
		}
		parsed++
		ast.Inspect(f, func(n ast.Node) bool {
			lit, ok := n.(*ast.BasicLit)
			if !ok || lit.Kind != token.STRING {
				return true
			}
			v, err := strconv.Unquote(lit.Value)
			if err != nil {
				return true
			}
			if p, ok := normaliseEndpointLiteral(v); ok {
				set[p] = true
			}
			return true
		})
	}
	if parsed == 0 {
		t.Fatalf("VOID: no non-test .go files found under %s — nothing was scanned", dir)
	}

	out := make([]string, 0, len(set))
	for k := range set {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// normaliseEndpointLiteral turns a source literal into a comparable path.
// "%s/api/v1/products/search?q=%s" -> "/api/v1/products/search".
func normaliseEndpointLiteral(v string) (string, bool) {
	v = strings.TrimPrefix(v, "%s")
	if i := strings.IndexByte(v, '?'); i >= 0 {
		v = v[:i]
	}
	if !strings.HasPrefix(v, "/") {
		return "", false
	}
	if v == "/" {
		return "", false
	}
	return v, true
}

// pathLiteralPrefix reduces an OpenAPI path template to the literal prefix a
// source string would contain: "/api/v1/webhooks/{source}" -> "/api/v1/webhooks/".
func pathLiteralPrefix(p string) string {
	if i := strings.IndexByte(p, '{'); i >= 0 {
		return p[:i]
	}
	return p
}

func matchDeclared(prefixes map[string]string, lit string) string {
	if name, ok := prefixes[lit]; ok {
		return name
	}
	for prefix, name := range prefixes {
		if strings.HasSuffix(prefix, "/") && strings.HasPrefix(lit, prefix) {
			return name
		}
	}
	return ""
}

// productionCallersOf walks every non-test .go file in the edge-go module and
// returns the files containing a `.<method>(` selector call. Used to prove an
// Unrouted client method stays uncalled.
func productionCallersOf(t *testing.T, method string) []string {
	t.Helper()
	root := filepath.Join(repoRootFromHere(t), "edge-go")
	fset := token.NewFileSet()
	var hits []string
	walked := 0

	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			if d.Name() == "vendor" || strings.HasPrefix(d.Name(), ".") {
				return fs.SkipDir
			}
			return nil
		}
		if !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		f, perr := parser.ParseFile(fset, path, nil, 0)
		if perr != nil {
			return fmt.Errorf("parse %s: %w", path, perr)
		}
		walked++
		ast.Inspect(f, func(n ast.Node) bool {
			ce, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			if sel, ok := ce.Fun.(*ast.SelectorExpr); ok && sel.Sel.Name == method {
				rel, rerr := filepath.Rel(root, path)
				if rerr != nil {
					rel = path
				}
				hits = append(hits, rel)
			}
			return true
		})
		return nil
	})
	if err != nil {
		t.Fatalf("VOID: caller scan failed: %v", err)
	}
	if walked == 0 {
		t.Fatalf("VOID: caller scan walked 0 files under %s — it cannot vouch for an absence", root)
	}
	sort.Strings(hits)
	return hits
}

// --- small helpers ---------------------------------------------------------

func sortedKeys(m map[string]any) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func sortedBoolKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// nearbyPaths gives a red gate something actionable: the core paths sharing
// the longest prefix with the one that was not found.
func nearbyPaths(paths map[string]any, want string) []string {
	segs := strings.Split(strings.Trim(want, "/"), "/")
	for n := len(segs); n > 0; n-- {
		prefix := "/" + strings.Join(segs[:n], "/")
		var hits []string
		for p := range paths {
			if strings.HasPrefix(p, prefix) {
				hits = append(hits, p)
			}
		}
		if len(hits) > 0 {
			sort.Strings(hits)
			if len(hits) > 8 {
				hits = hits[:8]
			}
			return hits
		}
	}
	return nil
}
