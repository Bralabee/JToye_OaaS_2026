package uk.jtoye.core.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.testsupport.BootJsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImageAnalysisService.
 * Tests the analyze() method and JSON parsing logic with various provider configs.
 * The constructor creates a real WebClient (no actual HTTP calls are made in disabled mode).
 */
class ImageAnalysisServiceTest {

    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        jsonMapper = BootJsonMapper.get();
    }

    private ImageAnalysisService createDisabledService() {
        return new ImageAnalysisService(
                "ollama",
                "http://localhost:11434",
                "llava:7b",
                "",
                "claude-sonnet-4-20250514",
                false, // disabled
                jsonMapper
        );
    }

    private ImageAnalysisService createEnabledOllamaService() {
        return new ImageAnalysisService(
                "ollama",
                "http://localhost:11434",
                "llava:7b",
                "",
                "claude-sonnet-4-20250514",
                true,
                jsonMapper
        );
    }

    private ImageAnalysisService createAnthropicServiceWithoutKey() {
        return new ImageAnalysisService(
                "anthropic",
                "http://localhost:11434",
                "llava:7b",
                "", // empty API key => disabled
                "claude-sonnet-4-20250514",
                true,
                jsonMapper
        );
    }

    private ImageAnalysisService createAnthropicServiceWithKey() {
        return new ImageAnalysisService(
                "anthropic",
                "http://localhost:11434",
                "llava:7b",
                "sk-ant-test-key-12345",
                "claude-sonnet-4-20250514",
                true,
                jsonMapper
        );
    }

    // ---- Disabled AI ----

    @Test
    @DisplayName("analyze - Returns empty when AI is disabled")
    void testAnalyze_DisabledReturnsEmpty() {
        ImageAnalysisService service = createDisabledService();
        byte[] fakeImage = new byte[]{1, 2, 3};

        Optional<ImageAnalysisResult> result = service.analyze(fakeImage, "image/jpeg");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("isEnabled - Returns false when disabled")
    void testIsEnabled_WhenDisabled() {
        ImageAnalysisService service = createDisabledService();
        assertFalse(service.isEnabled());
    }

    @Test
    @DisplayName("isEnabled - Returns true for enabled Ollama")
    void testIsEnabled_OllamaEnabled() {
        ImageAnalysisService service = createEnabledOllamaService();
        assertTrue(service.isEnabled());
    }

    @Test
    @DisplayName("getProvider - Returns ollama for default provider")
    void testGetProvider_Ollama() {
        ImageAnalysisService service = createEnabledOllamaService();
        assertEquals("ollama", service.getProvider());
    }

    @Test
    @DisplayName("getProvider - Returns anthropic when configured")
    void testGetProvider_Anthropic() {
        ImageAnalysisService service = createAnthropicServiceWithKey();
        assertEquals("anthropic", service.getProvider());
    }

    // ---- Anthropic without API key ----

    @Test
    @DisplayName("analyze - Anthropic disabled when API key is blank")
    void testAnalyze_AnthropicDisabledWithoutKey() {
        ImageAnalysisService service = createAnthropicServiceWithoutKey();
        assertFalse(service.isEnabled());

        Optional<ImageAnalysisResult> result = service.analyze(new byte[]{1, 2, 3}, "image/jpeg");
        assertTrue(result.isEmpty());
    }

    // ---- Anthropic with API key ----

    @Test
    @DisplayName("analyze - Anthropic enabled when API key is present")
    void testAnalyze_AnthropicEnabledWithKey() {
        ImageAnalysisService service = createAnthropicServiceWithKey();
        assertTrue(service.isEnabled());
        assertEquals("anthropic", service.getProvider());
    }

    // ---- Provider timeout/error (Ollama) ----

    @Test
    @DisplayName("analyze - Returns empty on provider error (connection refused)")
    void testAnalyze_ReturnsEmptyOnProviderError() {
        // Use an unreachable URL so the WebClient call fails immediately
        ImageAnalysisService service = new ImageAnalysisService(
                "ollama",
                "http://localhost:1", // unreachable port
                "llava:7b",
                "",
                "claude-sonnet-4-20250514",
                true,
                jsonMapper
        );

        byte[] fakeImage = new byte[]{1, 2, 3};
        Optional<ImageAnalysisResult> result = service.analyze(fakeImage, "image/jpeg");

        // Should catch the error and return empty, not throw
        assertTrue(result.isEmpty());
    }

    // ---- parseAnalysisJson via analyze() — test indirectly through the public API ----
    // The JSON parsing is private, but we can verify it via constructor + reflection if needed.
    // For now, we test the disabled path thoroughly since the enabled path requires live providers.

    @Test
    @DisplayName("ANALYSIS_PROMPT - Contains expected cuisine references")
    void testAnalysisPrompt_ContainsCuisineReferences() {
        // Verify the prompt covers key cuisines the system is designed for
        String prompt = ImageAnalysisService.ANALYSIS_PROMPT;
        assertTrue(prompt.contains("Nigerian"));
        assertTrue(prompt.contains("Caribbean"));
        assertTrue(prompt.contains("Jollof Rice"));
        assertTrue(prompt.contains("Suya"));
        assertTrue(prompt.contains("JSON"));
    }

    @Test
    @DisplayName("ANALYSIS_PROMPT - Requires JSON-only response")
    void testAnalysisPrompt_RequiresJsonResponse() {
        String prompt = ImageAnalysisService.ANALYSIS_PROMPT;
        assertTrue(prompt.contains("identifiedName"));
        assertTrue(prompt.contains("confidence"));
        assertTrue(prompt.contains("Respond ONLY with valid JSON"));
    }

    // ---- Model-output tolerance on Jackson 3 (38-07, T-38-18) ----
    //
    // The model's text is untrusted and loosely shaped. On Boot 3.5 the Jackson-2 mapper read it
    // leniently; on Boot 4 the injected mapper is Jackson 3, whose defaults (and the 38-05
    // jackson3-defaults decision) REJECT trailing content. The expected values below are the
    // Jackson-2 baseline: each text was run through this very class with Boot 3.5's Jackson-2
    // mapper on the plan base (evidence/38-07-jackson-request-response.txt, Task 2).
    //
    // Each case runs twice: with Boot's JsonMapper, and with a deliberately strict mapper. The
    // tolerance must come from the service's own reader, not from whatever the injected bean
    // happens to allow, so it holds "whatever 38-05 decides for Boot's defaults".
    //
    // The path is the real one: analyze() -> a local HTTP stand-in for Ollama's /api/generate
    // (JDK HttpServer, no new dependency) -> the envelope parse -> the model-text parse.

    /** Strictest reading of every feature the tolerance cases touch. */
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private HttpServer ollama;

    @AfterEach
    void stopOllama() {
        if (ollama != null) {
            ollama.stop(0);
        }
    }

    static Stream<Arguments> injectedMappers() {
        return Stream.of(
                Arguments.of(Named.of("Boot's JsonMapper", BootJsonMapper.get())),
                Arguments.of(Named.of("a strict JsonMapper", STRICT)));
    }

    /** An enabled Ollama-provider service whose /api/generate returns {@code modelText}. */
    private ImageAnalysisService ollamaReturning(String modelText, JsonMapper injected) throws IOException {
        byte[] envelope = JsonMapper.builder().build()
                .writeValueAsBytes(Map.of("response", modelText, "done", true));
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/generate", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, envelope.length);
            exchange.getResponseBody().write(envelope);
            exchange.close();
        });
        ollama.start();
        return new ImageAnalysisService("ollama", "http://127.0.0.1:" + ollama.getAddress().getPort(),
                "llava:7b", "", "claude-sonnet-4-20250514", true, injected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("injectedMappers")
    @DisplayName("tolerance - prose containing braces after the JSON object is ignored, as on Jackson 2")
    void trailingProseWithBraces_isIgnored_asOnJackson2(JsonMapper injected) throws Exception {
        // The service keeps the text from the first '{' to the LAST '}', so a brace in the
        // trailing prose leaves "...}\nNote: the {sauce}" for the JSON reader to cope with.
        String text = "{\"identifiedName\":\"Jollof Rice\",\"description\":\"Smoky party rice.\","
                + "\"ingredients\":\"rice, tomato\",\"category\":\"Mains\",\"dietaryTags\":[\"halal\"],"
                + "\"allergenWarnings\":[],\"cuisineOrigin\":\"Nigerian\",\"confidence\":0.92}"
                + "\nNote: the {sauce} may contain scotch bonnet.";

        Optional<ImageAnalysisResult> result = ollamaReturning(text, injected).analyze(new byte[]{1}, "image/jpeg");

        assertTrue(result.isPresent(), "trailing prose must not lose the analysis");
        ImageAnalysisResult r = result.get();
        assertEquals("Jollof Rice", r.getIdentifiedName());
        assertEquals("Smoky party rice.", r.getDescription());
        assertEquals("Mains", r.getCategory());
        assertEquals(List.of("halal"), r.getDietaryTags());
        assertEquals(List.of(), r.getAllergenWarnings());
        assertEquals("Nigerian", r.getCuisineOrigin());
        assertEquals(0.92, r.getConfidence());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("injectedMappers")
    @DisplayName("tolerance - prose without braces after the JSON object is ignored, as on Jackson 2")
    void trailingProseWithoutBraces_isIgnored_asOnJackson2(JsonMapper injected) throws Exception {
        Optional<ImageAnalysisResult> result = ollamaReturning(
                "{\"identifiedName\":\"Jollof Rice\",\"confidence\":0.92} Hope this helps!", injected)
                .analyze(new byte[]{1}, "image/jpeg");

        assertTrue(result.isPresent());
        assertEquals("Jollof Rice", result.get().getIdentifiedName());
        assertEquals(0.92, result.get().getConfidence());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("injectedMappers")
    @DisplayName("tolerance - unknown fields are ignored, as on Jackson 2")
    void unknownFields_areIgnored_asOnJackson2(JsonMapper injected) throws Exception {
        Optional<ImageAnalysisResult> result = ollamaReturning(
                "{\"identifiedName\":\"Suya\",\"confidence\":0.8,\"spiceLevel\":\"hot\",\"nested\":{\"x\":1}}",
                injected).analyze(new byte[]{1}, "image/jpeg");

        assertTrue(result.isPresent(), "an unknown field must not lose the analysis");
        assertEquals("Suya", result.get().getIdentifiedName());
        assertEquals(0.8, result.get().getConfidence());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("injectedMappers")
    @DisplayName("tolerance - a null confidence stays null, as on Jackson 2")
    void nullConfidence_staysNull_asOnJackson2(JsonMapper injected) throws Exception {
        Optional<ImageAnalysisResult> result = ollamaReturning(
                "{\"identifiedName\":\"Moi Moi\",\"cuisineOrigin\":\"Nigerian\",\"confidence\":null}",
                injected).analyze(new byte[]{1}, "image/jpeg");

        assertTrue(result.isPresent(), "a null confidence must not lose the analysis");
        assertEquals("Moi Moi", result.get().getIdentifiedName());
        assertEquals("Nigerian", result.get().getCuisineOrigin());
        assertNull(result.get().getConfidence());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("injectedMappers")
    @DisplayName("tolerance has a limit - prose with no JSON object, or a truncated object, is still no result")
    void noObjectOrTruncatedObject_isStillEmpty(JsonMapper injected) throws Exception {
        assertTrue(ollamaReturning("I cannot identify this image.", injected)
                .analyze(new byte[]{1}, "image/jpeg").isEmpty());
        stopOllama();
        assertTrue(ollamaReturning("{\"identifiedName\":\"Suya\",\"confidence\":", injected)
                .analyze(new byte[]{1}, "image/jpeg").isEmpty());
    }
}
