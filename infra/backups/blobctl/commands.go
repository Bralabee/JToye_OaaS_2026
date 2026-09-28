package main

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/streaming"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/to"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/bloberror"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blockblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/container"
)

// Exit codes: the contract k8s-backup.sh and the restore drill rely on.
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

const usageText = `usage: blobctl upload <file> <container> <blobname>
       blobctl list <container> <prefix>
       blobctl download <container> <blobname> <outfile>

env:   STORAGE_AUTH_MODE=workload-identity  STORAGE_ENDPOINT=https://<account>.blob.core.windows.net
                                            (+ AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_FEDERATED_TOKEN_FILE)
       STORAGE_AUTH_MODE=connection-string  STORAGE_CONNECTION_STRING=UseDevelopmentStorage=true[;DevelopmentStorageProxyUri=http://<host>]
                                            (the Azurite emulator only)
exit:  0 ok, 1 error, 2 usage, 3 already exists (upload never overwrites), 4 not found

blobctl never deletes: retention belongs to the container's immutability policy,
soft delete and lifecycle rule (D-01).
`

// store is everything blobctl can do to Blob storage. There is deliberately no
// delete and no overwrite: a backup identity that can do neither cannot destroy
// or replace an old dump, and retention is the container's job (D-01).
type store interface {
	// EnsurePrivateContainer creates the container with NO public access, and
	// treats "already exists" as success. Called in emulator mode only.
	EnsurePrivateContainer(ctx context.Context, container string) error
	// UploadNoOverwrite writes the file as a new blob; it returns errAlreadyExists
	// if the name is taken.
	UploadNoOverwrite(ctx context.Context, container, name string, file *os.File) error
	List(ctx context.Context, container, prefix string) ([]string, error)
	// Download streams the blob to w; it returns errNotFound for a missing blob or container.
	Download(ctx context.Context, container, name string, w io.Writer) error
}

// run is blobctl's whole CLI: parse, resolve config, dispatch, map to an exit
// code. Usage is decided BEFORE configuration is read, so a bare invocation
// exits 2 in any environment.
func run(args []string, env func(string) string, stdout, stderr io.Writer, newStore func(Config) (store, error)) int {
	arity := map[string]int{"upload": 3, "list": 2, "download": 3}
	if len(args) == 0 {
		fmt.Fprint(stderr, usageText)
		return exitUsage
	}
	cmd, rest := args[0], args[1:]
	want, known := arity[cmd]
	if !known || len(rest) != want {
		fmt.Fprint(stderr, usageText)
		return exitUsage
	}
	for i, a := range rest {
		if a == "" && !(cmd == "list" && i == 1) { // only list's prefix may be empty
			fmt.Fprint(stderr, usageText)
			return exitUsage
		}
	}

	cfg, err := LoadConfig(env)
	if err != nil {
		fmt.Fprintf(stderr, "blobctl: config: %v\n", err)
		return exitError
	}
	st, err := newStore(cfg)
	if err != nil {
		fmt.Fprintf(stderr, "blobctl: %s: client: %v\n", cfg, err)
		return exitError
	}
	ctx := context.Background()

	switch cmd {
	case "upload":
		return runUpload(ctx, cfg, st, rest[0], rest[1], rest[2], stderr)
	case "list":
		names, err := st.List(ctx, rest[0], rest[1])
		if err != nil {
			return report(stderr, "list", err)
		}
		sort.Strings(names)
		for _, n := range names {
			fmt.Fprintln(stdout, n)
		}
		return exitOK
	default: // download
		return runDownload(ctx, st, rest[0], rest[1], rest[2], stderr)
	}
}

func runUpload(ctx context.Context, cfg Config, st store, path, container, name string, stderr io.Writer) int {
	f, err := os.Open(path)
	if err != nil {
		fmt.Fprintf(stderr, "blobctl: upload: %v\n", err)
		return exitError
	}
	defer f.Close()
	// Only the emulator gets its container created on demand. In workload-identity
	// mode the container is provisioned (with its immutability policy) out of band
	// and the identity needs no container-level permission at all.
	if cfg.Mode == ModeConnectionString {
		if err := st.EnsurePrivateContainer(ctx, container); err != nil {
			return report(stderr, "upload: ensure container", err)
		}
	}
	if err := st.UploadNoOverwrite(ctx, container, name, f); err != nil {
		return report(stderr, "upload", err)
	}
	return exitOK
}

func runDownload(ctx context.Context, st store, container, name, outPath string, stderr io.Writer) int {
	// O_EXCL: never clobber a local file. 0600: a dump holds tenant PII.
	out, err := os.OpenFile(outPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		fmt.Fprintf(stderr, "blobctl: download: %v\n", err)
		return exitError
	}
	derr := st.Download(ctx, container, name, out)
	cerr := out.Close()
	if derr == nil && cerr != nil {
		derr = cerr
	}
	if derr != nil {
		os.Remove(outPath) // no half-written dump left behind to be mistaken for a good one
		return report(stderr, "download", derr)
	}
	return exitOK
}

func report(stderr io.Writer, what string, err error) int {
	fmt.Fprintf(stderr, "blobctl: %s: %v\n", what, err)
	switch {
	case errors.Is(err, errAlreadyExists):
		return exitExists
	case errors.Is(err, errNotFound):
		return exitNotFound
	default:
		return exitError
	}
}

// ---------------------------------------------------------------------------
// The azblob-backed store.
// ---------------------------------------------------------------------------

// singleShotLimit is the largest file sent as one Put Blob; above it the file is
// staged in stageBlockSize blocks and committed with Put Block List. Variables so
// the tests can drive the staged path without a quarter-gigabyte fixture.
var (
	singleShotLimit int64 = blockblob.MaxUploadBlobBytes
	stageBlockSize  int64 = 8 << 20
)

type azureStore struct{ c *azblob.Client }

func newAzureStore(cfg Config) (store, error) {
	c, err := NewClient(cfg)
	if err != nil {
		return nil, err
	}
	return &azureStore{c: c}, nil
}

func (s *azureStore) EnsurePrivateContainer(ctx context.Context, name string) error {
	// A nil Access means no x-ms-blob-public-access header: a private container.
	_, err := s.c.CreateContainer(ctx, name, nil)
	if err != nil && !bloberror.HasCode(err, bloberror.ContainerAlreadyExists) {
		return err
	}
	return nil
}

// noOverwrite is If-None-Match: * — the service refuses the write if ANY blob
// already has this name, which keeps blobctl compatible with a WORM container and
// makes a name collision loud instead of silent.
func noOverwrite() *blob.AccessConditions {
	return &blob.AccessConditions{
		ModifiedAccessConditions: &blob.ModifiedAccessConditions{IfNoneMatch: to.Ptr(azcore.ETagAny)},
	}
}

// UploadNoOverwrite does NOT use azblob's UploadFile. Measured on azblob v1.8.1
// (blockblob/models.go getCommitBlockListOptions): above MaxUploadBlobBytes
// (256 MiB) UploadFile stages blocks and commits them WITHOUT forwarding
// AccessConditions, so a large dump would silently overwrite an existing blob.
// Both paths here carry the condition explicitly.
func (s *azureStore) UploadNoOverwrite(ctx context.Context, containerName, name string, file *os.File) error {
	info, err := file.Stat()
	if err != nil {
		return err
	}
	size := info.Size()
	bb := s.c.ServiceClient().NewContainerClient(containerName).NewBlockBlobClient(name)

	if size <= singleShotLimit {
		_, err = bb.Upload(ctx, streaming.NopCloser(io.NewSectionReader(file, 0, size)),
			&blockblob.UploadOptions{AccessConditions: noOverwrite()})
		return mapUploadErr(err, containerName, name)
	}

	blocks := (size + stageBlockSize - 1) / stageBlockSize
	if blocks > blockblob.MaxBlocks {
		return fmt.Errorf("%s is %d bytes: more than %d blocks of %d bytes", file.Name(), size, blockblob.MaxBlocks, stageBlockSize)
	}
	ids := make([]string, 0, blocks)
	for i := int64(0); i < blocks; i++ {
		off := i * stageBlockSize
		n := min(stageBlockSize, size-off)
		// Block IDs must all have the same length within a blob.
		id := base64.StdEncoding.EncodeToString(fmt.Appendf(nil, "blobctl-%08d", i))
		if _, err := bb.StageBlock(ctx, id, streaming.NopCloser(io.NewSectionReader(file, off, n)), nil); err != nil {
			return mapUploadErr(err, containerName, name)
		}
		ids = append(ids, id)
	}
	_, err = bb.CommitBlockList(ctx, ids, &blockblob.CommitBlockListOptions{AccessConditions: noOverwrite()})
	return mapUploadErr(err, containerName, name)
}

func mapUploadErr(err error, containerName, name string) error {
	if err == nil {
		return nil
	}
	// 409 BlobAlreadyExists is what If-None-Match: * returns for an existing name;
	// 412 ConditionNotMet is the generic precondition failure; BlobImmutableDueToPolicy
	// can only be raised against a blob that already exists under WORM.
	if bloberror.HasCode(err, bloberror.BlobAlreadyExists, bloberror.ConditionNotMet, bloberror.BlobImmutableDueToPolicy) {
		return fmt.Errorf("%w: %s/%s", errAlreadyExists, containerName, name)
	}
	return err
}

func mapReadErr(err error, containerName, name string) error {
	if bloberror.HasCode(err, bloberror.BlobNotFound, bloberror.ContainerNotFound) {
		return fmt.Errorf("%w: %s/%s", errNotFound, containerName, name)
	}
	return err
}

func (s *azureStore) List(ctx context.Context, containerName, prefix string) ([]string, error) {
	var names []string
	pager := s.c.NewListBlobsFlatPager(containerName, &container.ListBlobsFlatOptions{Prefix: &prefix})
	for pager.More() {
		page, err := pager.NextPage(ctx)
		if err != nil {
			return nil, mapReadErr(err, containerName, prefix)
		}
		for _, item := range page.Segment.BlobItems {
			if item.Name != nil {
				names = append(names, *item.Name)
			}
		}
	}
	return names, nil
}

func (s *azureStore) Download(ctx context.Context, containerName, name string, w io.Writer) error {
	resp, err := s.c.DownloadStream(ctx, containerName, name, nil)
	if err != nil {
		return mapReadErr(err, containerName, name)
	}
	body := resp.NewRetryReader(ctx, &blob.RetryReaderOptions{MaxRetries: 3})
	defer body.Close()
	_, err = io.Copy(w, body)
	return err
}
