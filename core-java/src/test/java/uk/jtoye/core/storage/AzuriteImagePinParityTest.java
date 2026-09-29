package uk.jtoye.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.jtoye.core.testsupport.AzuriteTestSupport;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The Azurite the tests run against is the Azurite the stack runs (Phase 36).
 *
 * <p>{@link AzuriteTestSupport#AZURITE_IMAGE} pins the emulator every Blob integration test uses;
 * the compose files pin the one the full stack and the hybrid runtime start; and
 * {@code infra/dependency-horizons.yaml} records the same pin for the horizon gate. Nothing else
 * ties them together, so a bump in one place would leave the tests proving behaviour on an
 * emulator nobody runs. The compose default (after {@code :-}) is compared, because that is what
 * starts when {@code AZURITE_IMAGE_TAG} is unset.
 *
 * <p>Fails, never passes, when a file or its Azurite image line cannot be found:
 * {@link IntegrationTestSupport#locateRepoFile} throws on a missing file, and the parser below
 * fails unless it finds exactly one pin in the expected place.
 */
class AzuriteImagePinParityTest {

    private static final String REPO = "mcr.microsoft.com/azure-storage/azurite:";

    /** {@code image: mcr.microsoft.com/azure-storage/azurite:${AZURITE_IMAGE_TAG:-<default>}}. */
    private static final Pattern COMPOSE_IMAGE = Pattern.compile(
            "^\\s+image:\\s*" + Pattern.quote(REPO) + "\\$\\{AZURITE_IMAGE_TAG:-([^}]+)}\\s*$");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"docker-compose.full-stack.yml", "infra/docker-compose.yml"})
    @DisplayName("The compose azurite service's default image equals the Testcontainers fixture's")
    void composeAzuriteImageMatchesTheFixture(String composeFile) throws IOException {
        String pinned = REPO + azuriteServiceDefault(IntegrationTestSupport.locateRepoFile(composeFile));

        assertThat(pinned)
                .as("%s pins %s but AzuriteTestSupport.AZURITE_IMAGE is %s. Update both together, and "
                                + "infra/dependency-horizons.yaml (the azurite row's pin) as the third place",
                        composeFile, pinned, AzuriteTestSupport.AZURITE_IMAGE)
                .isEqualTo(AzuriteTestSupport.AZURITE_IMAGE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"infra/dependency-horizons.yaml"})
    @DisplayName("The dependency-horizons azurite row pins the same image as the fixture")
    void horizonsAzuriteRowMatchesTheFixture(String horizonsFile) throws IOException {
        List<String> lines = Files.readAllLines(IntegrationTestSupport.locateRepoFile(horizonsFile));
        int row = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("- id: azurite")) {
                if (row >= 0) {
                    fail("%s has more than one '- id: azurite' row", horizonsFile);
                }
                row = i;
            }
        }
        if (row < 0) {
            fail("%s has no '- id: azurite' row", horizonsFile);
        }
        Pattern pin = Pattern.compile("^\\s+pin:\\s*\"" + Pattern.quote(REPO)
                + "\\$\\{AZURITE_IMAGE_TAG:-([^}]+)}\"\\s*$");
        String found = null;
        for (int i = row + 1; i < lines.size() && !lines.get(i).trim().startsWith("- id:"); i++) {
            Matcher m = pin.matcher(lines.get(i));
            if (m.matches()) {
                found = REPO + m.group(1);
                break;
            }
        }
        if (found == null) {
            fail("%s: the azurite row has no pin line of the form pin: \"%s${AZURITE_IMAGE_TAG:-...}\"",
                    horizonsFile, REPO);
        }
        assertThat(found)
                .as("%s pins %s but AzuriteTestSupport.AZURITE_IMAGE is %s", horizonsFile, found,
                        AzuriteTestSupport.AZURITE_IMAGE)
                .isEqualTo(AzuriteTestSupport.AZURITE_IMAGE);
    }

    /**
     * The default tag of the {@code image:} line inside the top-level {@code azurite:} service
     * (two-space indent under {@code services:}), up to the next service. Exactly one such service
     * and one such line, or the test fails.
     */
    private static String azuriteServiceDefault(Path compose) throws IOException {
        List<String> lines = Files.readAllLines(compose);
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("  azurite:")) {
                starts.add(i);
            }
        }
        if (starts.size() != 1) {
            fail("%s: expected exactly one '  azurite:' service, found %d", compose, starts.size());
        }
        List<String> defaults = new ArrayList<>();
        for (int i = starts.get(0) + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches("^ {2}\\S.*") || line.matches("^\\S.*")) {
                break;   // the next service, or the next top-level key
            }
            Matcher m = COMPOSE_IMAGE.matcher(line);
            if (m.matches()) {
                defaults.add(m.group(1));
            }
        }
        if (defaults.size() != 1) {
            fail("%s: expected exactly one image line of the form image: %s${AZURITE_IMAGE_TAG:-...} "
                    + "in the azurite service, found %d", compose, REPO, defaults.size());
        }
        return defaults.get(0);
    }
}
