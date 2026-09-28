package main

import (
	"errors"

	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
)

// AuthMode is the value of STORAGE_AUTH_MODE, the same switch core-java uses.
type AuthMode string

const (
	ModeConnectionString AuthMode = "connection-string"
	ModeWorkloadIdentity AuthMode = "workload-identity"
)

// Config is the resolved auth path for one blobctl run.
type Config struct {
	Mode      AuthMode
	Endpoint  string
	ClientID  string
	TenantID  string
	TokenFile string
}

var errNotImplemented = errors.New("not implemented")

// LoadConfig is a RED-phase stub.
func LoadConfig(env func(string) string) (Config, error) {
	return Config{}, errNotImplemented
}

// String is a RED-phase stub.
func (c Config) String() string { return "" }

// NewClient is a RED-phase stub.
func NewClient(cfg Config) (*azblob.Client, error) {
	return nil, errNotImplemented
}
