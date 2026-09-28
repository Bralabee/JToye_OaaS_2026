package main

import (
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
)

// ---------------------------------------------------------------------------
// A fake store: exercises run()'s dispatch, exit codes and mode policy without
// any HTTP. It can neither overwrite nor delete, mirroring the interface.
// ---------------------------------------------------------------------------

type fakeStore struct {
	blobs       map[string][]byte // "<container>/<name>"
	ensureCalls []string
	uploads     int
}

func newFakeStore() *fakeStore { return &fakeStore{blobs: map[string][]byte{}} }

func (f *fakeStore) EnsurePrivateContainer(_ context.Context, container string) error {
	f.ensureCalls = append(f.ensureCalls, container)
	return nil
}

func (f *fakeStore) UploadNoOverwrite(_ context.Context, container, name string, file *os.File) error {
	f.uploads++
	k := container + "/" + name
	if _, ok := f.blobs[k]; ok {
		return errAlreadyExists
	}
	b, err := io.ReadAll(file)
	if err != nil {
		return err
	}
	f.blobs[k] = b
	return nil
}

func (f *fakeStore) List(_ context.Context, container, prefix string) ([]string, error) {
	var out []string
	for k := range f.blobs {
		c, n, _ := strings.Cut(k, "/")
		if c == container && strings.HasPrefix(n, prefix) {
			out = append(out, n)
		}
	}
	return out, nil // deliberately unsorted: run() owns the ordering
}

func (f *fakeStore) Download(_ context.Context, container, name string, w io.Writer) error {
	b, ok := f.blobs[container+"/"+name]
	if !ok {
		return errNotFound
	}
	_, err := w.Write(b)
	return err
}

var emulatorEnv = envFrom(map[string]string{
	"STORAGE_AUTH_MODE":         "connection-string",
	"STORAGE_CONNECTION_STRING": "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite",
})

var workloadEnv = envFrom(wiEnv())

func runWith(t *testing.T, fs *fakeStore, env func(string) string, args ...string) (int, string, string) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	code := run(args, env, &stdout, &stderr, func(Config) (store, error) { return fs, nil })
	return code, stdout.String(), stderr.String()
}

func writeTemp(t *testing.T, content string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "jtoye-backup-20260928-020000.dump")
	if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestRun_UsageExits2(t *testing.T) {
	cases := [][]string{
		nil,
		{"frobnicate"},
		{"upload"},
		{"upload", "a", "b"},
		{"upload", "a", "b", "c", "d"},
		{"list", "c"},
		{"list", "c", "p", "extra"},
		{"download", "c", "b"},
		{"delete", "c", "b"},
	}
	for _, args := range cases {
		t.Run(fmt.Sprintf("%q", args), func(t *testing.T) {
			fs := newFakeStore()
			// No env at all: usage must be decided before configuration is read.
			code, stdout, stderr := runWith(t, fs, envFrom(nil), args...)
			if code != exitUsage {
				t.Errorf("exit = %d, want %d", code, exitUsage)
			}
			if !strings.Contains(stderr, "usage: blobctl") {
				t.Errorf("stderr %q carries no usage", stderr)
			}
			if stdout != "" {
				t.Errorf("stdout = %q, want empty", stdout)
			}
		})
	}
}

func TestRun_ConfigErrorExits1WithoutEchoingSecrets(t *testing.T) {
	const canary = "CANARYVALUE9f3a"
	env := envFrom(map[string]string{
		"STORAGE_AUTH_MODE":         "connection-string",
		"STORAGE_CONNECTION_STRING": "DefaultEndpointsProtocol=https;AccountName=realacct;AccountKey=" + canary,
	})
	code, _, stderr := runWith(t, newFakeStore(), env, "list", "jtoye-db-backups", "backups/")
	if code != exitError {
		t.Errorf("exit = %d, want %d", code, exitError)
	}
	if !strings.Contains(stderr, "emulator-only") {
		t.Errorf("stderr %q does not explain the refusal", stderr)
	}
	if strings.Contains(stderr, canary) {
		t.Errorf("stderr echoes the connection string: %q", stderr)
	}
}

func TestRun_UploadNeverOverwrites(t *testing.T) {
	fs := newFakeStore()
	src := writeTemp(t, "PGDMP first")
	code, _, stderr := runWith(t, fs, emulatorEnv, "upload", src, "jtoye-db-backups", "backups/a.dump")
	if code != exitOK {
		t.Fatalf("first upload exit = %d (%s)", code, stderr)
	}
	second := writeTemp(t, "PGDMP second")
	code, _, stderr = runWith(t, fs, emulatorEnv, "upload", second, "jtoye-db-backups", "backups/a.dump")
	if code != exitExists {
		t.Errorf("second upload exit = %d, want %d", code, exitExists)
	}
	if !strings.Contains(stderr, "already exists") {
		t.Errorf("stderr %q does not say already exists", stderr)
	}
	if got := string(fs.blobs["jtoye-db-backups/backups/a.dump"]); got != "PGDMP first" {
		t.Errorf("stored bytes = %q, the first upload was overwritten", got)
	}
}

func TestRun_UploadMissingLocalFileExits1(t *testing.T) {
	fs := newFakeStore()
	code, _, _ := runWith(t, fs, emulatorEnv, "upload", filepath.Join(t.TempDir(), "absent.dump"), "c", "b")
	if code != exitError {
		t.Errorf("exit = %d, want %d", code, exitError)
	}
	if fs.uploads != 0 {
		t.Errorf("uploads = %d, want 0", fs.uploads)
	}
}

func TestRun_ContainerCreationOnlyInEmulatorMode(t *testing.T) {
	t.Run("emulator ensures the container before uploading", func(t *testing.T) {
		fs := newFakeStore()
		code, _, stderr := runWith(t, fs, emulatorEnv, "upload", writeTemp(t, "x"), "jtoye-db-backups", "b")
		if code != exitOK {
			t.Fatalf("exit = %d (%s)", code, stderr)
		}
		if len(fs.ensureCalls) != 1 || fs.ensureCalls[0] != "jtoye-db-backups" {
			t.Errorf("ensureCalls = %v, want [jtoye-db-backups]", fs.ensureCalls)
		}
	})
	t.Run("workload identity never creates a container", func(t *testing.T) {
		fs := newFakeStore()
		code, _, stderr := runWith(t, fs, workloadEnv, "upload", writeTemp(t, "x"), "jtoye-db-backups", "b")
		if code != exitOK {
			t.Fatalf("exit = %d (%s)", code, stderr)
		}
		if len(fs.ensureCalls) != 0 {
			t.Errorf("ensureCalls = %v, want none", fs.ensureCalls)
		}
	})
	t.Run("list and download never create a container in either mode", func(t *testing.T) {
		for _, env := range []func(string) string{emulatorEnv, workloadEnv} {
			fs := newFakeStore()
			runWith(t, fs, env, "list", "jtoye-db-backups", "")
			runWith(t, fs, env, "download", "jtoye-db-backups", "b", filepath.Join(t.TempDir(), "out"))
			if len(fs.ensureCalls) != 0 {
				t.Errorf("ensureCalls = %v, want none", fs.ensureCalls)
			}
		}
	})
}

func TestRun_ListPrintsSortedNames(t *testing.T) {
	fs := newFakeStore()
	for _, n := range []string{"backups/c.dump", "backups/a.dump", "other/z.dump", "backups/b.dump"} {
		fs.blobs["jtoye-db-backups/"+n] = []byte("x")
	}
	code, stdout, _ := runWith(t, fs, emulatorEnv, "list", "jtoye-db-backups", "backups/")
	if code != exitOK {
		t.Fatalf("exit = %d", code)
	}
	if want := "backups/a.dump\nbackups/b.dump\nbackups/c.dump\n"; stdout != want {
		t.Errorf("stdout = %q, want %q", stdout, want)
	}

	code, stdout, _ = runWith(t, fs, emulatorEnv, "list", "jtoye-db-backups", "nothing-here/")
	if code != exitOK || stdout != "" {
		t.Errorf("empty listing: exit = %d stdout = %q, want 0 and nothing", code, stdout)
	}
}

func TestRun_DownloadWritesBytesOrExits4(t *testing.T) {
	fs := newFakeStore()
	fs.blobs["jtoye-db-backups/backups/a.dump"] = []byte("PGDMP bytes")
	dir := t.TempDir()

	out := filepath.Join(dir, "a.dump")
	code, _, stderr := runWith(t, fs, emulatorEnv, "download", "jtoye-db-backups", "backups/a.dump", out)
	if code != exitOK {
		t.Fatalf("exit = %d (%s)", code, stderr)
	}
	if b, err := os.ReadFile(out); err != nil || string(b) != "PGDMP bytes" {
		t.Errorf("downloaded %q, %v", b, err)
	}
	if st, err := os.Stat(out); err == nil && st.Mode().Perm() != 0o600 {
		t.Errorf("outfile mode = %v, want 0600 (a dump holds tenant PII)", st.Mode().Perm())
	}

	missing := filepath.Join(dir, "missing.dump")
	code, _, stderr = runWith(t, fs, emulatorEnv, "download", "jtoye-db-backups", "backups/none.dump", missing)
	if code != exitNotFound {
		t.Errorf("missing blob exit = %d, want %d (%s)", code, exitNotFound, stderr)
	}
	if _, err := os.Stat(missing); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("a failed download left %s behind", missing)
	}

	// An existing local file is never clobbered.
	if err := os.WriteFile(filepath.Join(dir, "keep.dump"), []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	code, _, _ = runWith(t, fs, emulatorEnv, "download", "jtoye-db-backups", "backups/a.dump", filepath.Join(dir, "keep.dump"))
	if code != exitError {
		t.Errorf("download onto an existing file exit = %d, want %d", code, exitError)
	}
	if b, _ := os.ReadFile(filepath.Join(dir, "keep.dump")); string(b) != "keep" {
		t.Errorf("existing local file was overwritten: %q", b)
	}
}

// ---------------------------------------------------------------------------
// The REAL azblob adapter, driven through run() against an in-process fake of
// the Blob REST service. This is what proves the SDK request itself carries
// If-None-Match: *, creates containers private, and never issues a DELETE.
// ---------------------------------------------------------------------------

type blobServiceFake struct {
	mu            sync.Mutex
	blobs         map[string][]byte // "<container>/<name>"
	containers    map[string]string // container -> x-ms-blob-public-access ("" = private)
	methods       []string
	unauthorised  int
	createHeaders []http.Header
}

func newBlobServiceFake() *blobServiceFake {
	return &blobServiceFake{blobs: map[string][]byte{}, containers: map[string]string{}}
}

func (f *blobServiceFake) fail(w http.ResponseWriter, status int, code string) {
	w.Header().Set("x-ms-error-code", code)
	w.Header().Set("Content-Type", "application/xml")
	w.WriteHeader(status)
	fmt.Fprintf(w, `<?xml version="1.0" encoding="utf-8"?><Error><Code>%s</Code><Message>%s</Message></Error>`, code, code)
}

func (f *blobServiceFake) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.methods = append(f.methods, r.Method)
	if !strings.HasPrefix(r.Header.Get("Authorization"), "SharedKey devstoreaccount1:") {
		f.unauthorised++
		f.fail(w, http.StatusForbidden, "AuthenticationFailed")
		return
	}
	// Path: /devstoreaccount1/<container>[/<blob...>]
	parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/devstoreaccount1/"), "/", 2)
	container := parts[0]
	name := ""
	if len(parts) == 2 {
		name = parts[1]
	}
	q := r.URL.Query()
	w.Header().Set("x-ms-request-id", "fake")
	w.Header().Set("x-ms-version", r.Header.Get("x-ms-version"))

	switch {
	case r.Method == http.MethodDelete:
		f.fail(w, http.StatusMethodNotAllowed, "FakeRefusesDelete")

	case r.Method == http.MethodPut && q.Get("restype") == "container" && name == "":
		f.createHeaders = append(f.createHeaders, r.Header.Clone())
		if _, ok := f.containers[container]; ok {
			f.fail(w, http.StatusConflict, "ContainerAlreadyExists")
			return
		}
		f.containers[container] = r.Header.Get("x-ms-blob-public-access")
		w.WriteHeader(http.StatusCreated)

	case r.Method == http.MethodPut && name != "" && q.Get("comp") == "":
		if _, ok := f.containers[container]; !ok {
			f.fail(w, http.StatusNotFound, "ContainerNotFound")
			return
		}
		k := container + "/" + name
		if _, exists := f.blobs[k]; exists && r.Header.Get("If-None-Match") == "*" {
			f.fail(w, http.StatusConflict, "BlobAlreadyExists")
			return
		}
		b, _ := io.ReadAll(r.Body)
		f.blobs[k] = b
		w.Header().Set("ETag", `"0x1"`)
		w.WriteHeader(http.StatusCreated)

	case r.Method == http.MethodGet && q.Get("comp") == "list" && name == "":
		if _, ok := f.containers[container]; !ok {
			f.fail(w, http.StatusNotFound, "ContainerNotFound")
			return
		}
		prefix := q.Get("prefix")
		var names []string
		for k := range f.blobs {
			c, n, _ := strings.Cut(k, "/")
			if c == container && strings.HasPrefix(n, prefix) {
				names = append(names, n)
			}
		}
		sort.Sort(sort.Reverse(sort.StringSlice(names))) // hostile order: run() must sort
		var body strings.Builder
		fmt.Fprintf(&body, `<?xml version="1.0" encoding="utf-8"?><EnumerationResults ContainerName="%s"><Prefix>%s</Prefix><Blobs>`, container, prefix)
		for _, n := range names {
			body.WriteString("<Blob><Name>")
			_ = xml.EscapeText(&body, []byte(n))
			body.WriteString("</Name><Properties></Properties></Blob>")
		}
		body.WriteString("</Blobs><NextMarker /></EnumerationResults>")
		w.Header().Set("Content-Type", "application/xml")
		w.WriteHeader(http.StatusOK)
		io.WriteString(w, body.String())

	case r.Method == http.MethodGet && name != "":
		b, ok := f.blobs[container+"/"+name]
		if !ok {
			f.fail(w, http.StatusNotFound, "BlobNotFound")
			return
		}
		w.Header().Set("Content-Length", fmt.Sprint(len(b)))
		w.Header().Set("ETag", `"0x1"`)
		w.WriteHeader(http.StatusOK)
		w.Write(b)

	default:
		f.fail(w, http.StatusBadRequest, "FakeUnsupported")
	}
}

// adapterRun runs a command through run() with the REAL azblob store, pointed
// at the fake service. The mode stays connection-string (the emulator shared key).
func adapterRun(t *testing.T, srv *httptest.Server, args ...string) (int, string, string) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	code := run(args, emulatorEnv, &stdout, &stderr, func(cfg Config) (store, error) {
		cfg.Endpoint = srv.URL + "/devstoreaccount1"
		return newAzureStore(cfg)
	})
	return code, stdout.String(), stderr.String()
}

func TestAzureStore_EndToEndAgainstFakeBlobService(t *testing.T) {
	fake := newBlobServiceFake()
	srv := httptest.NewServer(fake)
	defer srv.Close()

	src := writeTemp(t, "PGDMP original")
	code, _, stderr := adapterRun(t, srv, "upload", src, "jtoye-db-backups", "backups/a.dump")
	if code != exitOK {
		t.Fatalf("upload exit = %d (%s)", code, stderr)
	}
	if access, ok := fake.containers["jtoye-db-backups"]; !ok || access != "" {
		t.Errorf("container created=%v with public access %q, want private (no x-ms-blob-public-access)", ok, access)
	}

	// Second upload of the same name: the SDK request must carry If-None-Match: *
	// so the service refuses it, and blobctl must map that to exit 3.
	again := writeTemp(t, "PGDMP replacement")
	code, _, stderr = adapterRun(t, srv, "upload", again, "jtoye-db-backups", "backups/a.dump")
	if code != exitExists {
		t.Errorf("re-upload exit = %d, want %d (%s)", code, exitExists, stderr)
	}
	if !strings.Contains(stderr, "already exists") {
		t.Errorf("stderr %q does not say already exists", stderr)
	}
	if got := string(fake.blobs["jtoye-db-backups/backups/a.dump"]); got != "PGDMP original" {
		t.Errorf("stored bytes = %q: the existing blob was overwritten", got)
	}

	// A second upload of a NEW name reuses the existing container
	// (ContainerAlreadyExists is success, not an error).
	code, _, stderr = adapterRun(t, srv, "upload", writeTemp(t, "PGDMP b"), "jtoye-db-backups", "backups/b.dump")
	if code != exitOK {
		t.Fatalf("upload b exit = %d (%s)", code, stderr)
	}

	code, stdout, stderr := adapterRun(t, srv, "list", "jtoye-db-backups", "backups/")
	if code != exitOK || stdout != "backups/a.dump\nbackups/b.dump\n" {
		t.Errorf("list exit = %d stdout = %q (%s)", code, stdout, stderr)
	}

	out := filepath.Join(t.TempDir(), "restored.dump")
	code, _, stderr = adapterRun(t, srv, "download", "jtoye-db-backups", "backups/a.dump", out)
	if code != exitOK {
		t.Fatalf("download exit = %d (%s)", code, stderr)
	}
	if b, _ := os.ReadFile(out); string(b) != "PGDMP original" {
		t.Errorf("downloaded %q", b)
	}

	code, _, _ = adapterRun(t, srv, "download", "jtoye-db-backups", "backups/none.dump", filepath.Join(t.TempDir(), "x"))
	if code != exitNotFound {
		t.Errorf("missing blob exit = %d, want %d", code, exitNotFound)
	}

	for _, m := range fake.methods {
		if m == http.MethodDelete {
			t.Errorf("blobctl issued a DELETE request")
		}
	}
	if fake.unauthorised != 0 {
		t.Errorf("%d requests were not signed with the emulator shared key", fake.unauthorised)
	}
	for _, h := range fake.createHeaders {
		if v := h.Get("x-ms-blob-public-access"); v != "" {
			t.Errorf("container create asked for public access %q", v)
		}
	}
}
