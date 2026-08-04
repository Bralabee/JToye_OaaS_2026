package core

// The edge↔core contract manifest.
//
// Every HTTP call this package makes to core-java is declared here, in the
// terms core's OWN OpenAPI document uses (docs/api/openapi-snapshot.json —
// the reviewed snapshot the `OpenAPI Breaking-Change Gate` already guards).
// contract_test.go turns the manifest into an executable gate:
//
//  1. every declared call resolves to a real operation in core's snapshot;
//  2. every json-tagged field of the Go types on the wire exists as a
//     property of core's schema for that operation, recursively;
//  3. every property core marks `required` is present in the Go request type;
//  4. every query param core marks `required` is one the edge sends;
//  5. every endpoint literal in this package's source is declared here
//     (so a new core call cannot be added without declaring its contract),
//     AND every declared path is found in the source (so the scanner is
//     proven able to see — a scan that finds nothing must never read clean).
//
// Why a manifest rather than generated client code: the edge deliberately
// hand-writes these five calls so it can own its own timeouts, breaker and
// error mapping. The cost of hand-writing is drift, and drift here is silent —
// Go's encoding/json ignores unknown fields on decode, so a renamed core field
// yields a zero value, not an error. This manifest is the price of that
// choice, paid in one place.
//
// NOTE: these are paths on CORE. The edge's own published surface is a
// different document (edge-go/docs/swagger.json, guarded by openapi_test.go).
// `/api/v1/webhooks/whatsapp` appears in both and means different things:
// on the edge it is Meta's inbound webhook, on core it would be an outbound
// forward. Do not conflate them.
type EdgeCoreCall struct {
	// Name is the client method that makes this call. Used in failure
	// messages so a red gate names the function to open.
	Name string

	// Method and Path address the operation in core's OpenAPI snapshot.
	// Path is core's path TEMPLATE (e.g. "/api/v1/webhooks/{source}"),
	// not the concrete URL the edge builds.
	Method string
	Path   string

	// Query lists the query-string parameters the edge sends.
	Query []string

	// Request is a zero value of the type marshalled into the request body,
	// or nil when the call sends no JSON body.
	Request any

	// Response is a zero value of the type the response body is decoded
	// INTO, or nil when the body is not decoded. A slice means core returns
	// an array; the gate unwraps both sides.
	Response any

	// AcceptedStatus lists the status codes this client treats as success.
	// The gate asserts core declares at least one of them.
	AcceptedStatus []int

	// Unrouted marks a client method that targets an endpoint core does NOT
	// expose. Such a method must have no production caller — the gate proves
	// that separately (TestEdgeCoreContract/unrouted_calls_have_no_caller)
	// rather than waiving the contract, so wiring one up turns the gate red
	// and forces the contract question before the 404 reaches production.
	Unrouted bool

	// Note records why an entry is unusual. Required when Unrouted is set.
	Note string
}

// EdgeCoreCalls returns the declared edge→core dependency surface.
//
// Adding a call to client.go/orders.go without adding it here fails
// TestEdgeCoreContract/every_endpoint_literal_is_declared.
func EdgeCoreCalls() []EdgeCoreCall {
	return []EdgeCoreCall{
		{
			Name:           "Client.SyncBatch",
			Method:         "post",
			Path:           "/api/v1/sync/batch",
			Request:        BatchSyncRequest{},
			Response:       coreBatchSyncResponse{},
			AcceptedStatus: []int{200, 202},
		},
		{
			Name:           "Client.SearchProducts",
			Method:         "get",
			Path:           "/api/v1/products/search",
			Query:          []string{"q"},
			Response:       []ProductSearchResult{},
			AcceptedStatus: []int{200},
		},
		{
			Name:           "Client.CreateOrder",
			Method:         "post",
			Path:           "/api/v1/orders",
			Request:        CreateOrderRequest{},
			Response:       CreateOrderResponse{},
			AcceptedStatus: []int{200, 201},
		},
		{
			Name:           "Client.HealthCheck",
			Method:         "get",
			Path:           "/health",
			AcceptedStatus: []int{200},
		},
		{
			Name:     "Client.ForwardWebhook",
			Method:   "post",
			Path:     "/api/v1/webhooks/{source}",
			Unrouted: true,
			Note: "core exposes POST /api/v1/webhooks (create a webhook SUBSCRIPTION) and " +
				"GET /api/v1/webhooks/{id}; it has no generic inbound forward at " +
				"/api/v1/webhooks/{source}. Stripe's inbound hook is " +
				"POST /api/v1/public/payments/webhook. This helper therefore has no " +
				"production caller and must not acquire one without first agreeing a " +
				"core endpoint — see issue #337.",
		},
	}
}
