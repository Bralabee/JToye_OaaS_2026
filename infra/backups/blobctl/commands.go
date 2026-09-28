package main

import (
	"context"
	"errors"
	"io"
	"os"
)

// Exit codes (the contract the backup script and the restore drill rely on).
const (
	exitOK       = 0
	exitError    = 1
	exitUsage    = 2
	exitExists   = 3
	exitNotFound = 4
)

var (
	errAlreadyExists = errors.New("already exists")
	errNotFound      = errors.New("not found")
)

// store is everything blobctl can do to Blob storage.
type store interface {
	EnsurePrivateContainer(ctx context.Context, container string) error
	UploadNoOverwrite(ctx context.Context, container, name string, file *os.File) error
	List(ctx context.Context, container, prefix string) ([]string, error)
	Download(ctx context.Context, container, name string, w io.Writer) error
}

// run is a RED-phase stub.
func run(args []string, env func(string) string, stdout, stderr io.Writer, newStore func(Config) (store, error)) int {
	return -1
}

// newAzureStore is a RED-phase stub.
func newAzureStore(cfg Config) (store, error) {
	return nil, errNotImplemented
}

var errNotImplemented = errors.New("not implemented")
