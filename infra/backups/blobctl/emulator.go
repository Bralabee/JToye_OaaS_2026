package main

// The Azurite / Azure Storage Emulator development account.
//
// emulatorAccountKey is Microsoft's PUBLISHED key for the "devstoreaccount1"
// development account, documented in the Azurite README under
// "Default Storage Account" (https://github.com/Azure/Azurite#default-storage-account)
// and embedded in every Azure Storage SDK (the Java SDK carries the same bytes in
// com.azure.storage.common.implementation.Constants). It is PUBLIC and EMULATOR-ONLY:
// it authenticates to nothing but a local Azurite, and a real storage account can
// never be reached with it.
//
// It lives here, in exactly one source file, because the Go SDK does not understand
// the UseDevelopmentStorage=true connection-string form (azblob v1.8.1
// internal/shared/shared.go), so blobctl supplies the emulator credential itself the
// way the Java SDK does. .gitleaks.toml allows exactly this string and nothing else.
//
// Do not copy this constant anywhere else, and never pass a real account key to
// blobctl: config.go refuses every connection string that names an account, a key
// or a SAS (D-02).
const (
	emulatorAccountName = "devstoreaccount1"
	emulatorAccountKey  = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="
	// emulatorBlobPort is the Blob service port Azurite listens on; the Java SDK
	// appends it unconditionally to the DevelopmentStorageProxyUri host.
	emulatorBlobPort = "10000"
)
