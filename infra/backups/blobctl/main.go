// Command blobctl moves Postgres backup dumps in and out of Azure Blob Storage
// for the pg-backup image (Phase 36, BLOB-06). See usage in commands.go.
package main

import "os"

func main() {
	os.Exit(run(os.Args[1:], os.Getenv, os.Stdout, os.Stderr, newAzureStore))
}
