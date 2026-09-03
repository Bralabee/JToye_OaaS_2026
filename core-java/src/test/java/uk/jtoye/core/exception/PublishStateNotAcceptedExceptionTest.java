package uk.jtoye.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INT-2 / INT-3 (QA council 20260902-134741, adjudication A16): the RFC 7807 {@code detail}
 * of the 409 is a machine-readable contract and must name only remedies a caller can
 * reach. It used to send a LIVE vendor to "the onboarding suspend/reinstate transitions to
 * unpublish" — both are declared in the state machine but nothing fires them (no
 * controller, no service method, no UI), so the promised remedy did not exist.
 */
class PublishStateNotAcceptedExceptionTest {

    private static final Pattern API_PATH = Pattern.compile("/api/v1/[A-Za-z0-9/_-]+");

    @Test
    @DisplayName("an unpublish request (LIVE shop) is told the truth: no self-service unpublish, no suspend/reinstate promise")
    void unpublishRequest_namesOnlyReachableRemedies() {
        String detail = new PublishStateNotAcceptedException(false, true).getMessage();

        assertThat(detail).containsIgnoringCase("no self-service unpublish");
        assertThat(detail).doesNotContainIgnoringCase("suspend");
        assertThat(detail).doesNotContainIgnoringCase("reinstate");
        // The no-op re-send is still spelled out with the CURRENT value.
        assertThat(detail).contains("published=true");
    }

    @Test
    @DisplayName("a publish request (unpublished shop) is pointed at the one reachable path: POST /api/v1/onboarding/go-live")
    void publishRequest_pointsAtGoLive() {
        String detail = new PublishStateNotAcceptedException(true, false).getMessage();

        assertThat(detail).contains("POST /api/v1/onboarding/go-live");
        assertThat(detail).contains("published=false");
    }

    @Test
    @DisplayName("every API path the detail names actually exists (go-live is the only one)")
    void everyNamedPathExists() {
        for (boolean requested : new boolean[] {true, false}) {
            String detail = new PublishStateNotAcceptedException(requested, !requested).getMessage();
            Matcher m = API_PATH.matcher(detail);
            List<String> named = new ArrayList<>();
            while (m.find()) {
                named.add(m.group());
            }
            assertThat(named).as("paths named for requested=" + requested)
                    .isNotEmpty()
                    .containsOnly("/api/v1/onboarding/go-live");
        }
    }

    @Test
    @DisplayName("the typed fields the ProblemDetail properties are built from are carried verbatim")
    void typedFieldsCarried() {
        PublishStateNotAcceptedException ex = new PublishStateNotAcceptedException(false, true);
        assertThat(ex.getRequestedPublished()).isFalse();
        assertThat(ex.getCurrentPublished()).isTrue();
    }
}
