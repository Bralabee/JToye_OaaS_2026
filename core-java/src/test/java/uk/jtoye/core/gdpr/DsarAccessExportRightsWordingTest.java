package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SW-SAM-09 (swarm epic #964): the access export tells the subject how to ask for erasure, and the
 * route it names must exist.
 *
 * <p>It said erasure is requested "through the same request page you used for this copy", but no
 * such page exists: a copy is requested from My account ({@code /shop/account}, "Download my data")
 * and erasure from the same page ("Delete my account"), or, without an account, through the
 * contact routes in the privacy notice. The document is built from the service alone, with no
 * database: {@code buildDocument} reads only its arguments and the app origin.
 */
class DsarAccessExportRightsWordingTest {

    private static final String APP = "https://app.example.test";

    private JsonNode erasureRight() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        DsarAccessExportService service = new DsarAccessExportService(null, null, null, null, null, null, null,
                null, mapper);
        ReflectionTestUtils.setField(service, "appBaseUrl", APP);
        byte[] doc = service.buildDocument("subject@example.test", List.of(), null, OffsetDateTime.now());
        for (JsonNode right : mapper.readTree(doc).path("about").path("rights")) {
            if ("erasure".equals(right.path("right").asString())) {
                return right;
            }
        }
        throw new AssertionError("the export names no erasure right");
    }

    @Test
    @DisplayName("SW-SAM-09: the erasure right names the real routes, never a 'same request page' that does not exist")
    void erasureRightNamesTheRealRoute() throws Exception {
        String how = erasureRight().path("how").asString();

        assertThat(how)
                .doesNotContain("same request page")
                .contains("\"Delete my account\"")
                .contains(APP + "/shop/account")
                .contains(APP + "/legal/privacy");
    }

    @Test
    @DisplayName("SW-SAM-09: the routes the export names are the ones the web app really has")
    void theNamedRoutesExistInTheWebApp() throws Exception {
        assertThat(IntegrationTestSupport.locateRepoFile("frontend/app/shop/account/page.tsx")).exists();
        assertThat(IntegrationTestSupport.locateRepoFile("frontend/app/legal/privacy/page.tsx")).exists();
        String account = Files.readString(IntegrationTestSupport.locateRepoFile("frontend/app/shop/account/account-client.tsx"));
        assertThat(account).as("the button the export tells the subject to press")
                .contains("button: \"Delete my account\"");
    }
}
