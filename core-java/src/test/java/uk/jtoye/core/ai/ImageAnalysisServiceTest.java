package uk.jtoye.core.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImageAnalysisService.
 * Tests the analyze() method and JSON parsing logic with various provider configs.
 * The constructor creates a real WebClient (no actual HTTP calls are made in disabled mode).
 */
class ImageAnalysisServiceTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    private ImageAnalysisService createDisabledService() {
        return new ImageAnalysisService(
                "ollama",
                "http://localhost:11434",
                "llava:7b",
                "",
                "claude-sonnet-4-20250514",
                false, // disabled
                objectMapper
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
                objectMapper
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
                objectMapper
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
                objectMapper
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
                objectMapper
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
}
