package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// envFrom turns a map into the injected env function LoadConfig reads through,
// so no test ever depends on the process environment.
func envFrom(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

// wiEnv is a complete, valid workload-identity environment; cases copy it and
// change one thing.
func wiEnv() map[string]string {
	return map[string]string{
		"STORAGE_AUTH_MODE":          "workload-identity",
		"STORAGE_ENDPOINT":           "https://jtoyestgbackup.blob.core.windows.net",
		"AZURE_CLIENT_ID":            "00000000-0000-0000-0000-000000000001",
		"AZURE_TENANT_ID":            "00000000-0000-0000-0000-000000000002",
		"AZURE_FEDERATED_TOKEN_FILE": "/var/run/secrets/azure/tokens/azure-identity-token",
	}
}

func TestLoadConfig_AuthModeSwitch(t *testing.T) {
	cases := []struct {
		name string
		mode string
	}{
		{"unset", ""},
		{"bogus", "bogus"},
		{"wrong case is not accepted (core-java matches exactly)", "Workload-Identity"},
		{"retired S3 vocabulary", "access-key"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := LoadConfig(envFrom(map[string]string{
				"STORAGE_AUTH_MODE":         tc.mode,
				"STORAGE_CONNECTION_STRING": "UseDevelopmentStorage=true",
			}))
			if err == nil {
				t.Fatalf("mode %q: expected an error, got nil", tc.mode)
			}
			for _, want := range []string{"STORAGE_AUTH_MODE", "connection-string", "workload-identity"} {
				if !strings.Contains(err.Error(), want) {
					t.Errorf("mode %q: error %q does not name %q", tc.mode, err, want)
				}
			}
		})
	}
}

func TestLoadConfig_WorkloadIdentity(t *testing.T) {
	t.Run("valid environment resolves to workload identity on that endpoint", func(t *testing.T) {
		cfg, err := LoadConfig(envFrom(wiEnv()))
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if cfg.Mode != ModeWorkloadIdentity {
			t.Errorf("Mode = %q, want %q", cfg.Mode, ModeWorkloadIdentity)
		}
		if cfg.Endpoint != "https://jtoyestgbackup.blob.core.windows.net" {
			t.Errorf("Endpoint = %q", cfg.Endpoint)
		}
		if cfg.ClientID != "00000000-0000-0000-0000-000000000001" ||
			cfg.TenantID != "00000000-0000-0000-0000-000000000002" ||
			cfg.TokenFile != "/var/run/secrets/azure/tokens/azure-identity-token" {
			t.Errorf("identity fields not carried: %+v", cfg)
		}
	})

	t.Run("a trailing slash on the endpoint is accepted and normalised away", func(t *testing.T) {
		env := wiEnv()
		env["STORAGE_ENDPOINT"] = "https://jtoyestgbackup.blob.core.windows.net/"
		cfg, err := LoadConfig(envFrom(env))
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if cfg.Endpoint != "https://jtoyestgbackup.blob.core.windows.net" {
			t.Errorf("Endpoint = %q", cfg.Endpoint)
		}
	})

	badEndpoints := []struct {
		name     string
		endpoint string
	}{
		{"missing", ""},
		{"plain http", "http://jtoyestgbackup.blob.core.windows.net"},
		{"not a blob host", "https://evil.example.com"},
		{"lookalike suffix", "https://jtoyestgbackup.blob.core.windows.net.evil.example.com"},
		{"account name too short", "https://ab.blob.core.windows.net"},
		{"account name upper case", "https://JtoyeStgBackup.blob.core.windows.net"},
		{"a path after the host", "https://jtoyestgbackup.blob.core.windows.net/jtoye-db-backups"},
		{"an explicit port", "https://jtoyestgbackup.blob.core.windows.net:443"},
		{"the emulator", "http://127.0.0.1:10000/devstoreaccount1"},
	}
	for _, tc := range badEndpoints {
		t.Run("endpoint refused: "+tc.name, func(t *testing.T) {
			env := wiEnv()
			env["STORAGE_ENDPOINT"] = tc.endpoint
			_, err := LoadConfig(envFrom(env))
			if err == nil {
				t.Fatalf("endpoint %q: expected an error, got nil", tc.endpoint)
			}
			if !strings.Contains(err.Error(), "STORAGE_ENDPOINT") {
				t.Errorf("error %q does not name STORAGE_ENDPOINT", err)
			}
		})
	}

	for _, missing := range []string{"AZURE_CLIENT_ID", "AZURE_TENANT_ID", "AZURE_FEDERATED_TOKEN_FILE"} {
		t.Run("missing "+missing, func(t *testing.T) {
			env := wiEnv()
			delete(env, missing)
			_, err := LoadConfig(envFrom(env))
			if err == nil {
				t.Fatalf("expected an error with %s unset, got nil", missing)
			}
			if !strings.Contains(err.Error(), missing) {
				t.Errorf("error %q does not name the missing %s", err, missing)
			}
		})
	}

	t.Run("a connection string alongside workload identity is refused (one auth path per run)", func(t *testing.T) {
		env := wiEnv()
		env["STORAGE_CONNECTION_STRING"] = "UseDevelopmentStorage=true"
		_, err := LoadConfig(envFrom(env))
		if err == nil {
			t.Fatal("expected an error, got nil")
		}
		if !strings.Contains(err.Error(), "STORAGE_CONNECTION_STRING") {
			t.Errorf("error %q does not name STORAGE_CONNECTION_STRING", err)
		}
	})
}

func TestLoadConfig_ConnectionString(t *testing.T) {
	accepted := []struct {
		name     string
		conn     string
		endpoint string
	}{
		{"bare emulator form", "UseDevelopmentStorage=true", "http://127.0.0.1:10000/devstoreaccount1"},
		{"compose proxy", "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite", "http://azurite:10000/devstoreaccount1"},
		{"minikube proxy", "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://host.minikube.internal", "http://host.minikube.internal:10000/devstoreaccount1"},
		{"keys are case-insensitive, trailing separator tolerated", "usedevelopmentstorage=TRUE;developmentstorageproxyuri=http://azurite;", "http://azurite:10000/devstoreaccount1"},
		{"proxy with a bare trailing slash", "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite/", "http://azurite:10000/devstoreaccount1"},
	}
	for _, tc := range accepted {
		t.Run("accepted: "+tc.name, func(t *testing.T) {
			cfg, err := LoadConfig(envFrom(map[string]string{
				"STORAGE_AUTH_MODE":         "connection-string",
				"STORAGE_CONNECTION_STRING": tc.conn,
			}))
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if cfg.Mode != ModeConnectionString {
				t.Errorf("Mode = %q, want %q", cfg.Mode, ModeConnectionString)
			}
			if cfg.Endpoint != tc.endpoint {
				t.Errorf("Endpoint = %q, want %q", cfg.Endpoint, tc.endpoint)
			}
		})
	}

	// Each of these carries a real-account credential or addressing key. The
	// value after '=' is a canary: it must never be echoed back in the error.
	const canary = "CANARYVALUE9f3a"
	refusedEmulatorOnly := []struct {
		name string
		conn string
	}{
		{"AccountName + AccountKey", "DefaultEndpointsProtocol=https;AccountName=realacct;AccountKey=" + canary + ";EndpointSuffix=core.windows.net"},
		{"AccountKey alone next to the emulator flag", "UseDevelopmentStorage=true;AccountKey=" + canary},
		{"AccountName alone next to the emulator flag", "UseDevelopmentStorage=true;AccountName=" + canary},
		{"SharedAccessSignature", "BlobEndpoint=https://realacct.blob.core.windows.net;SharedAccessSignature=sv=2026&sig=" + canary},
		{"SAS next to the emulator flag", "UseDevelopmentStorage=true;SharedAccessSignature=" + canary},
		{"BlobEndpoint", "UseDevelopmentStorage=true;BlobEndpoint=https://" + canary + ".blob.core.windows.net"},
		{"DefaultEndpointsProtocol", "UseDevelopmentStorage=true;DefaultEndpointsProtocol=" + canary},
		{"key case does not bypass the refusal", "UseDevelopmentStorage=true;ACCOUNTKEY=" + canary},
		{"an unknown key is refused too", "UseDevelopmentStorage=true;EndpointSuffix=" + canary},
	}
	for _, tc := range refusedEmulatorOnly {
		t.Run("refused emulator-only: "+tc.name, func(t *testing.T) {
			_, err := LoadConfig(envFrom(map[string]string{
				"STORAGE_AUTH_MODE":         "connection-string",
				"STORAGE_CONNECTION_STRING": tc.conn,
			}))
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			if !strings.Contains(err.Error(), "emulator-only (D-02)") {
				t.Errorf("error %q does not contain %q", err, "emulator-only (D-02)")
			}
			if strings.Contains(err.Error(), canary) {
				t.Errorf("error echoes the connection-string value: %q", err)
			}
		})
	}

	refusedOther := []struct {
		name string
		conn string
	}{
		{"empty", ""},
		{"emulator flag false", "UseDevelopmentStorage=false"},
		{"emulator flag absent", "DevelopmentStorageProxyUri=http://azurite"},
		{"segment without '='", "UseDevelopmentStorage=true;azurite"},
		{"duplicate key", "UseDevelopmentStorage=true;UseDevelopmentStorage=true"},
		{"proxy over https", "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=https://azurite"},
		{"proxy with a port", "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite:10000"},
		{"proxy with a path", "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite/devstoreaccount1"},
		{"proxy with userinfo", "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://u:p@azurite"},
		{"proxy with a query", "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite?x=1"},
		{"proxy not a URL", "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=azurite"},
	}
	for _, tc := range refusedOther {
		t.Run("refused: "+tc.name, func(t *testing.T) {
			_, err := LoadConfig(envFrom(map[string]string{
				"STORAGE_AUTH_MODE":         "connection-string",
				"STORAGE_CONNECTION_STRING": tc.conn,
			}))
			if err == nil {
				t.Fatalf("connection string %q: expected an error, got nil", tc.conn)
			}
			if !strings.Contains(err.Error(), "STORAGE_CONNECTION_STRING") {
				t.Errorf("error %q does not name STORAGE_CONNECTION_STRING", err)
			}
		})
	}
}

func TestConfig_StringRedacts(t *testing.T) {
	// A token file whose CONTENTS are a canary: LoadConfig must never read it,
	// and String() must never print it.
	dir := t.TempDir()
	tokenPath := filepath.Join(dir, "azure-identity-token")
	const tokenCanary = "TOKENCANARY-eyJhbGciOi"
	if err := os.WriteFile(tokenPath, []byte(tokenCanary), 0o600); err != nil {
		t.Fatal(err)
	}
	env := wiEnv()
	env["AZURE_FEDERATED_TOKEN_FILE"] = tokenPath
	wi, err := LoadConfig(envFrom(env))
	if err != nil {
		t.Fatalf("workload identity: %v", err)
	}
	s := wi.String()
	for _, want := range []string{"workload-identity", "jtoyestgbackup.blob.core.windows.net"} {
		if !strings.Contains(s, want) {
			t.Errorf("String() = %q, want it to contain %q", s, want)
		}
	}
	for _, forbidden := range []string{tokenCanary, tokenPath, "00000000-0000-0000-0000-000000000001"} {
		if strings.Contains(s, forbidden) {
			t.Errorf("String() = %q leaks %q", s, forbidden)
		}
	}

	const conn = "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite"
	em, err := LoadConfig(envFrom(map[string]string{
		"STORAGE_AUTH_MODE":         "connection-string",
		"STORAGE_CONNECTION_STRING": conn,
	}))
	if err != nil {
		t.Fatalf("connection-string: %v", err)
	}
	s = em.String()
	for _, want := range []string{"connection-string", "azurite"} {
		if !strings.Contains(s, want) {
			t.Errorf("String() = %q, want it to contain %q", s, want)
		}
	}
	for _, forbidden := range []string{emulatorAccountKey, conn, "UseDevelopmentStorage", "AccountKey"} {
		if strings.Contains(s, forbidden) {
			t.Errorf("String() = %q leaks %q", s, forbidden)
		}
	}
}

func TestNewClient_BuildsOneClientPerMode(t *testing.T) {
	t.Run("emulator builds a shared-key client on the derived endpoint", func(t *testing.T) {
		cfg, err := LoadConfig(envFrom(map[string]string{
			"STORAGE_AUTH_MODE":         "connection-string",
			"STORAGE_CONNECTION_STRING": "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite",
		}))
		if err != nil {
			t.Fatal(err)
		}
		c, err := NewClient(cfg)
		if err != nil {
			t.Fatalf("NewClient: %v", err)
		}
		if got := c.URL(); got != "http://azurite:10000/devstoreaccount1/" {
			t.Errorf("client URL = %q", got)
		}
	})

	t.Run("workload identity builds a token client on the account endpoint", func(t *testing.T) {
		env := wiEnv()
		env["AZURE_FEDERATED_TOKEN_FILE"] = filepath.Join(t.TempDir(), "token")
		cfg, err := LoadConfig(envFrom(env))
		if err != nil {
			t.Fatal(err)
		}
		c, err := NewClient(cfg)
		if err != nil {
			t.Fatalf("NewClient: %v", err)
		}
		if got := c.URL(); got != "https://jtoyestgbackup.blob.core.windows.net/" {
			t.Errorf("client URL = %q", got)
		}
	})

	t.Run("a zero Config is refused, not defaulted", func(t *testing.T) {
		if _, err := NewClient(Config{}); err == nil {
			t.Fatal("expected an error for an unresolved Config, got nil")
		}
	})
}
