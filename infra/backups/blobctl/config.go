package main

import (
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"strings"

	"github.com/Azure/azure-sdk-for-go/sdk/azidentity"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
)

// AuthMode is the value of STORAGE_AUTH_MODE, the same one switch core-java uses
// (storage.blob.auth-mode, D-02). Matched exactly, as core-java does.
type AuthMode string

const (
	// ModeConnectionString is the LOCAL path: Azurite through the emulator
	// connection-string form, and nothing else.
	ModeConnectionString AuthMode = "connection-string"
	// ModeWorkloadIdentity is the staging/production path: the AKS-injected
	// federated identity, no stored secret of any kind.
	ModeWorkloadIdentity AuthMode = "workload-identity"
)

// Env names blobctl reads. They are read ONLY through the env function handed to
// LoadConfig, never via os.Getenv, so the tests are hermetic.
const (
	envAuthMode      = "STORAGE_AUTH_MODE"
	envEndpoint      = "STORAGE_ENDPOINT"
	envConnString    = "STORAGE_CONNECTION_STRING"
	envClientID      = "AZURE_CLIENT_ID"
	envTenantID      = "AZURE_TENANT_ID"
	envFederatedFile = "AZURE_FEDERATED_TOKEN_FILE"
)

// A real account endpoint: https, a 3-24 char lower-case alphanumeric account
// name, the public-cloud Blob suffix, no port and no path.
var accountEndpoint = regexp.MustCompile(`^https://[a-z0-9]{3,24}\.blob\.core\.windows\.net/?$`)

// Config is the resolved auth path for one blobctl run. It deliberately never
// holds the connection string, a key or a token: in connection-string mode the
// only thing kept is the derived emulator endpoint, and in workload-identity mode
// the token file is kept as a PATH and never opened here.
type Config struct {
	Mode      AuthMode
	Endpoint  string // service URL without a trailing slash
	ClientID  string // workload identity only
	TenantID  string // workload identity only
	TokenFile string // workload identity only; a path, read by azidentity per token request
}

// String prints the mode and the endpoint HOST only (T-36-13).
func (c Config) String() string {
	host := "<none>"
	if u, err := url.Parse(c.Endpoint); err == nil && u.Hostname() != "" {
		host = u.Hostname()
	}
	return fmt.Sprintf("mode=%s endpointHost=%s", c.Mode, host)
}

// GoString keeps %#v from printing the identity fields; String() is the only
// rendering blobctl ever needs.
func (c Config) GoString() string { return c.String() }

// LoadConfig resolves exactly one auth path from the environment, or refuses.
func LoadConfig(env func(string) string) (Config, error) {
	mode := AuthMode(env(envAuthMode))
	switch mode {
	case ModeWorkloadIdentity:
		return loadWorkloadIdentity(env)
	case ModeConnectionString:
		return loadEmulator(env)
	default:
		return Config{}, fmt.Errorf("%s must be %q or %q, got %q",
			envAuthMode, ModeConnectionString, ModeWorkloadIdentity, string(mode))
	}
}

func loadWorkloadIdentity(env func(string) string) (Config, error) {
	// One auth path per run: a stored connection string next to workload identity
	// is a misconfiguration, never a fallback.
	if env(envConnString) != "" {
		return Config{}, fmt.Errorf("%s must not be set when %s=%s (one auth path per run, D-02)",
			envConnString, envAuthMode, ModeWorkloadIdentity)
	}
	endpoint := env(envEndpoint)
	if !accountEndpoint.MatchString(endpoint) {
		return Config{}, fmt.Errorf("%s must be https://<account>.blob.core.windows.net in %s mode, got %q",
			envEndpoint, ModeWorkloadIdentity, endpoint)
	}
	var missing []string
	for _, k := range []string{envClientID, envTenantID, envFederatedFile} {
		if env(k) == "" {
			missing = append(missing, k)
		}
	}
	if len(missing) > 0 {
		return Config{}, fmt.Errorf("%s mode needs %s (injected by the AKS workload-identity webhook); missing: %s",
			ModeWorkloadIdentity, strings.Join([]string{envClientID, envTenantID, envFederatedFile}, ", "),
			strings.Join(missing, ", "))
	}
	return Config{
		Mode:      ModeWorkloadIdentity,
		Endpoint:  strings.TrimSuffix(endpoint, "/"),
		ClientID:  env(envClientID),
		TenantID:  env(envTenantID),
		TokenFile: env(envFederatedFile),
	}, nil
}

// loadEmulator accepts ONLY the emulator connection-string form core-java also
// uses: UseDevelopmentStorage=true with an optional DevelopmentStorageProxyUri.
// STORAGE_ENDPOINT is not consulted in this mode; the endpoint is derived the way
// the Java SDK derives it, so one string configures both programs.
func loadEmulator(env func(string) string) (Config, error) {
	raw := env(envConnString)
	if raw == "" {
		return Config{}, fmt.Errorf("%s is required when %s=%s", envConnString, envAuthMode, ModeConnectionString)
	}
	pairs := map[string]string{}
	for _, seg := range strings.Split(raw, ";") {
		seg = strings.TrimSpace(seg)
		if seg == "" {
			continue
		}
		k, v, ok := strings.Cut(seg, "=")
		if !ok {
			return Config{}, fmt.Errorf("%s has a segment that is not key=value", envConnString)
		}
		key := strings.ToLower(strings.TrimSpace(k))
		switch key {
		case "usedevelopmentstorage", "developmentstorageproxyuri":
		default:
			// Names the KEY (as written) but never its value.
			return Config{}, fmt.Errorf("%s is emulator-only (D-02): key %q is not allowed; "+
				"only UseDevelopmentStorage=true and DevelopmentStorageProxyUri are accepted, "+
				"and a real account is reached with workload identity, never a stored key or SAS",
				envConnString, strings.TrimSpace(k))
		}
		if _, dup := pairs[key]; dup {
			return Config{}, fmt.Errorf("%s repeats key %q", envConnString, strings.TrimSpace(k))
		}
		pairs[key] = strings.TrimSpace(v)
	}
	if !strings.EqualFold(pairs["usedevelopmentstorage"], "true") {
		return Config{}, fmt.Errorf("%s must contain UseDevelopmentStorage=true (emulator-only, D-02)", envConnString)
	}
	host := "127.0.0.1"
	if proxy, ok := pairs["developmentstorageproxyuri"]; ok {
		h, err := proxyHost(proxy)
		if err != nil {
			return Config{}, fmt.Errorf("%s DevelopmentStorageProxyUri %w", envConnString, err)
		}
		host = h
	}
	return Config{
		Mode:     ModeConnectionString,
		Endpoint: "http://" + host + ":" + emulatorBlobPort + "/" + emulatorAccountName,
	}, nil
}

// proxyHost validates DevelopmentStorageProxyUri: an http URL naming a host and
// nothing else. The SDK appends :10000/devstoreaccount1 itself, so a port or a
// path here would be silently discarded by core-java; refusing it keeps the two
// programs on the same endpoint.
func proxyHost(proxy string) (string, error) {
	u, err := url.Parse(proxy)
	if err != nil {
		return "", errors.New("is not a URL")
	}
	switch {
	case u.Scheme != "http":
		return "", errors.New("must use http (the emulator speaks plain http)")
	case u.Hostname() == "":
		return "", errors.New("must name a host")
	case u.Port() != "":
		return "", errors.New("must not carry a port (the emulator Blob port 10000 is implied)")
	case u.User != nil:
		return "", errors.New("must not carry userinfo")
	case u.Path != "" && u.Path != "/":
		return "", errors.New("must not carry a path")
	case u.RawQuery != "" || u.ForceQuery || u.Fragment != "":
		return "", errors.New("must not carry a query or fragment")
	}
	return u.Hostname(), nil
}

// NewClient builds the azblob client for the resolved auth path.
func NewClient(cfg Config) (*azblob.Client, error) {
	serviceURL := cfg.Endpoint + "/"
	switch cfg.Mode {
	case ModeWorkloadIdentity:
		cred, err := azidentity.NewWorkloadIdentityCredential(&azidentity.WorkloadIdentityCredentialOptions{
			ClientID:      cfg.ClientID,
			TenantID:      cfg.TenantID,
			TokenFilePath: cfg.TokenFile,
		})
		if err != nil {
			return nil, fmt.Errorf("workload identity credential: %w", err)
		}
		return azblob.NewClient(serviceURL, cred, nil)
	case ModeConnectionString:
		cred, err := azblob.NewSharedKeyCredential(emulatorAccountName, emulatorAccountKey)
		if err != nil {
			return nil, fmt.Errorf("emulator credential: %w", err)
		}
		return azblob.NewClientWithSharedKeyCredential(serviceURL, cred, nil)
	default:
		return nil, fmt.Errorf("unresolved config (mode %q); build it with LoadConfig", string(cfg.Mode))
	}
}
