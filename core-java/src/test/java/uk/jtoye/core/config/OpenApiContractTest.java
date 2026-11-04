package uk.jtoye.core.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #879 / UXT-123 (P2-RAV-15, P2-RAV-17): the published OpenAPI contract matches the live responses.
 *
 * <p>Reads the REVIEWED snapshot ({@code docs/api/openapi-snapshot.json}), which
 * {@code OpenApiSnapshotTest} holds byte-equal to what {@code /v3/api-docs} serves, so no Spring
 * context or Docker is needed and the assertions run in the unit lane. Each assertion first proves
 * it can see its subject (a control), because "no violations" over an empty set is not evidence.
 *
 * <p>Persona run measured, before: {@code *}{@code /*} on 187 of 207 operations, a 429 on 7 of 207, a
 * {@code pageable} object query parameter on 14, and the edge's {@code processed_count} against core's
 * {@code processedCount} on the same batch.
 */
class OpenApiContractTest {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    /** The paths RateLimitInterceptor.isExcludedPath skips: it never answers 429 there. */
    private static final Set<String> NOT_RATE_LIMITED = Set.of("/health");

    private static JsonNode spec() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repo root (settings.gradle.kts) above the test working dir").isNotNull();
        return JsonMapper.builder().build().readTree(Files.readString(dir.resolve("docs/api/openapi-snapshot.json")));
    }

    private static List<String[]> operations(JsonNode spec) {
        List<String[]> ops = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : spec.get("paths").properties()) {
            for (String method : path.getValue().propertyNames()) {
                if (METHODS.contains(method)) {
                    ops.add(new String[] {path.getKey(), method});
                }
            }
        }
        return ops;
    }

    private static JsonNode op(JsonNode spec, String[] pm) {
        return spec.get("paths").get(pm[0]).get(pm[1]);
    }

    @Test
    void noResponseIsDeclaredAsAnyMediaType() throws IOException {
        JsonNode spec = spec();
        List<String[]> ops = operations(spec);
        assertThat(ops.size()).as("control: the snapshot has operations").isGreaterThan(100);
        long withBody = ops.stream().filter(pm -> {
            for (JsonNode r : op(spec, pm).get("responses")) {
                if (r.has("content") && r.get("content").size() > 0) {
                    return true;
                }
            }
            return false;
        }).count();
        assertThat(withBody).as("control: response bodies are visible to this scan").isGreaterThan(100);

        List<String> offenders = new ArrayList<>();
        for (String[] pm : ops) {
            for (Map.Entry<String, JsonNode> resp : op(spec, pm).get("responses").properties()) {
                if (resp.getValue().path("content").has("*/*")) {
                    offenders.add(pm[1].toUpperCase() + " " + pm[0] + " " + resp.getKey());
                }
            }
        }
        assertThat(offenders)
                .as("a generated client cannot pick a deserialiser from */*; declare the real media type")
                .isEmpty();
    }

    @Test
    void theNonJsonDownloadsNameTheirRealMediaType() throws IOException {
        JsonNode spec = spec();
        assertThat(spec.at("/paths/~1api~1v1~1products~1template/get/responses/200/content").has("text/csv"))
                .as("the CSV import template is text/csv").isTrue();
        assertThat(spec.at("/paths/~1api~1v1~1products~1{id}~1label/get/responses/200/content").has("application/pdf"))
                .as("the allergen label is application/pdf").isTrue();
        assertThat(spec.at("/paths/~1health/get/responses/200/content").has("text/plain"))
                .as("/health answers plain text").isTrue();
    }

    @Test
    void everyRateLimitedOperationDocumentsA429WithRetryAfter() throws IOException {
        JsonNode spec = spec();
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String[] pm : operations(spec)) {
            if (NOT_RATE_LIMITED.contains(pm[0])) {
                continue;
            }
            checked++;
            JsonNode r429 = op(spec, pm).path("responses").path("429");
            boolean ok = r429.has("description")
                    && r429.path("headers").has("Retry-After")
                    && r429.path("content").has("application/problem+json");
            if (!ok) {
                offenders.add(pm[1].toUpperCase() + " " + pm[0]);
            }
        }
        assertThat(checked).as("control: rate-limited operations were checked").isGreaterThan(100);
        assertThat(offenders)
                .as("RateLimitInterceptor answers 429 with Retry-After on every path but %s", NOT_RATE_LIMITED)
                .isEmpty();
        assertThat(operations(spec).stream().filter(pm -> NOT_RATE_LIMITED.contains(pm[0]))
                .filter(pm -> op(spec, pm).path("responses").has("429")).count())
                .as("the excluded /health path is not told it can 429").isZero();
    }

    @Test
    void pagedListsDocumentPageSizeAndSortNotAPageableObject() throws IOException {
        JsonNode spec = spec();
        List<String> pagedOps = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (String[] pm : operations(spec)) {
            JsonNode op = op(spec, pm);
            boolean paged = false;
            for (JsonNode resp : op.get("responses")) {
                for (JsonNode media : resp.path("content")) {
                    String ref = media.path("schema").path("$ref").asString("");
                    if (ref.startsWith("#/components/schemas/Page") && !ref.endsWith("Pageable")
                            && !ref.endsWith("PageableObject")) {
                        paged = true;
                    }
                }
            }
            if (!paged) {
                continue;
            }
            pagedOps.add(pm[0]);
            List<String> names = new ArrayList<>();
            for (JsonNode param : op.path("parameters")) {
                names.add(param.get("name").asString());
            }
            if (names.contains("pageable") || !names.containsAll(List.of("page", "size", "sort"))) {
                offenders.add(pm[1].toUpperCase() + " " + pm[0] + " params=" + names);
            }
        }
        assertThat(pagedOps.size()).as("control: paged operations exist").isGreaterThanOrEqualTo(15);
        assertThat(offenders).isEmpty();
        assertThat(spec.at("/components/schemas").has("Pageable"))
                .as("the Pageable object is not a request schema any more").isFalse();
    }

    @Test
    void placingAGuestOrderDocumentsBothTypedTooManyRequestsProblemsDistinctly() throws IOException {
        JsonNode spec = spec();
        for (String path : new String[] {"/api/v1/public/shops/{slug}/orders", "/public/shops/{slug}/orders"}) {
            String d429 = spec.at("/paths/" + path.replace("/", "~1") + "/post/responses/429/description").asString();
            assertThat(d429).as(path + " 429 description (control: present)").isNotBlank();
            assertThat(d429).as(path).contains("errors/rate-limited").contains("Retry-After");
            assertThat(d429).as(path).contains("errors/order-cap-exceeded")
                    .contains("NO Retry-After")
                    .contains("Do not retry the same request")
                    .contains("axis");
        }
    }

    @Test
    void theBatchResponseCarriesBothSpellingsOfProcessedCount() throws IOException {
        JsonNode props = spec().at("/components/schemas/BatchSyncResponse/properties");
        assertThat(props.has("processedCount")).as("core's existing name stays").isTrue();
        assertThat(props.has("processed_count"))
                .as("the edge publishes processed_count for the same batch; core answers it too").isTrue();
    }

    @Test
    void theOrdersWriteScopeIsNotDescribedAsGatingOnlyPostOrders() throws IOException {
        String scope = spec().at("/components/securitySchemes/catalog-scopes/flows/clientCredentials/scopes/orders:write")
                .asString();
        assertThat(scope).as("control: the scope is described").isNotBlank();
        assertThat(scope).doesNotContain("gates POST /orders");
        assertThat(scope).contains("every");
    }
}
