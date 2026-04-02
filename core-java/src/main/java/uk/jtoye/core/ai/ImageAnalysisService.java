package uk.jtoye.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * AI-powered food and grocery image recognition.
 *
 * Supports two providers:
 * - "ollama" (default) — free, local, runs in Docker alongside the app
 * - "anthropic" — cloud-based Claude Vision, higher quality but costs per call
 *
 * Uses vision-capable models to identify dishes (including Nigerian, West African,
 * Caribbean cuisines), suggest ingredients, categories, and dietary information.
 */
@Service
public class ImageAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ImageAnalysisService.class);

    private final WebClient aiClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String provider;
    private final String model;

    static final String ANALYSIS_PROMPT = """
            You are a food and grocery product identification expert for a UK-based multi-vendor \
            retail platform. You have deep knowledge of cuisines from around the world, \
            especially Nigerian, West African, Caribbean, South Asian, and British cuisines.

            When shown an image, identify the food item or grocery product with high accuracy.

            For prepared meals/dishes:
            - Identify the specific dish name (e.g., "Jollof Rice", "Abula", "Egusi Soup")
            - List the visible and likely ingredients
            - Note the cuisine origin
            - Identify dietary properties (halal, vegan, gluten-free, etc.)
            - Flag likely allergens

            For grocery items (fruits, vegetables, packaged goods):
            - Identify the exact product
            - Note common uses or pairings
            - Identify any dietary/allergen info visible on packaging

            Cultural food examples you must recognize:
            - Abula = amala + ewedu + gbegiri + assorted meat (Nigerian)
            - Pounded Yam and Egusi = pounded yam with melon seed soup (Nigerian)
            - Suya = spicy grilled beef skewers with yaji spice (Nigerian)
            - Jerk Chicken = marinated grilled chicken (Caribbean)
            - Curry Goat = slow-cooked goat curry (Caribbean)
            - Eba and Okra = garri-based swallow with okra soup (Nigerian)
            - Moi Moi = steamed bean pudding (Nigerian)
            - Asun = spicy smoked goat meat (Nigerian)
            - Plantain — fried, boiled, or roasted (West African staple)
            - Chin Chin = crunchy fried dough snack (Nigerian)

            Respond ONLY with valid JSON matching this exact structure:
            {
              "identifiedName": "Dish or product name",
              "description": "2-3 sentence appetizing customer-facing description",
              "ingredients": "Comma-separated list of likely ingredients",
              "category": "One of: Mains, Sides, Snacks, Drinks, Desserts, Grocery, Fruits & Vegetables, Bakery",
              "dietaryTags": ["list", "of", "applicable", "tags"],
              "allergenWarnings": ["list", "of", "likely", "allergens"],
              "cuisineOrigin": "e.g. Nigerian, Caribbean, British, etc.",
              "confidence": 0.95
            }

            Set confidence between 0.0 and 1.0. If the image is not food/grocery related, \
            set confidence to 0.0 and identifiedName to "Unknown".
            Only return the JSON object, no markdown, no code blocks, no explanation.""";

    private static final String USER_MESSAGE = "Identify this food item or grocery product. Respond with JSON only.";

    public ImageAnalysisService(
            @Value("${ai.provider:ollama}") String provider,
            @Value("${ai.ollama.url:http://localhost:11434}") String ollamaUrl,
            @Value("${ai.ollama.model:llava:7b}") String ollamaModel,
            @Value("${ai.anthropic.api-key:}") String anthropicApiKey,
            @Value("${ai.anthropic.model:claude-sonnet-4-20250514}") String anthropicModel,
            @Value("${ai.enabled:true}") boolean enabled,
            ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;
        this.provider = provider;

        if ("anthropic".equals(provider)) {
            this.model = anthropicModel;
            this.enabled = enabled && anthropicApiKey != null && !anthropicApiKey.isBlank();
            this.aiClient = WebClient.builder()
                    .baseUrl("https://api.anthropic.com")
                    .defaultHeader("x-api-key", anthropicApiKey != null ? anthropicApiKey : "")
                    .defaultHeader("anthropic-version", "2023-06-01")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();

            if (this.enabled) {
                log.info("AI Image Analysis: provider=anthropic, model={}", this.model);
            } else {
                log.warn("AI Image Analysis disabled — provider=anthropic but ANTHROPIC_API_KEY not set");
            }
        } else {
            // Default: Ollama (local, free)
            this.model = ollamaModel;
            this.enabled = enabled;
            this.aiClient = WebClient.builder()
                    .baseUrl(ollamaUrl)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();
            log.info("AI Image Analysis: provider=ollama, model={}, url={}", this.model, ollamaUrl);
        }
    }

    /**
     * Analyze a food/grocery image.
     */
    public Optional<ImageAnalysisResult> analyze(byte[] imageBytes, String mediaType) {
        if (!enabled) {
            log.debug("AI analysis skipped — service disabled");
            return Optional.empty();
        }

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        try {
            String responseJson = "anthropic".equals(provider)
                    ? callAnthropic(base64Image, mediaType)
                    : callOllama(base64Image);

            return parseAnalysisJson(responseJson);
        } catch (Exception e) {
            log.error("AI analysis failed (provider={}): {}", provider, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getProvider() {
        return provider;
    }

    // ---- Ollama (local, free) ----

    private String callOllama(String base64Image) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", ANALYSIS_PROMPT + "\n\n" + USER_MESSAGE);
        body.put("images", List.of(base64Image));
        body.put("stream", false);

        String response = aiClient.post()
                .uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(120)) // Vision models can be slow locally
                .block();

        // Ollama returns { "response": "...", "done": true, ... }
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("response").asText("");
        } catch (Exception e) {
            log.error("Failed to parse Ollama response: {}", e.getMessage());
            return "";
        }
    }

    // ---- Anthropic Claude (cloud, paid) ----

    private String callAnthropic(String base64Image, String mediaType) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1024,
                "system", ANALYSIS_PROMPT,
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "image", "source", Map.of(
                                        "type", "base64",
                                        "media_type", mediaType,
                                        "data", base64Image
                                )),
                                Map.of("type", "text", "text", USER_MESSAGE)
                        ))
                )
        );

        String response = aiClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        // Anthropic returns { "content": [{ "text": "..." }] }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText("");
            }
        } catch (Exception e) {
            log.error("Failed to parse Anthropic response: {}", e.getMessage());
        }
        return "";
    }

    // ---- Shared response parsing ----

    private Optional<ImageAnalysisResult> parseAnalysisJson(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        // Clean up markdown code fences if present
        text = text.strip();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        // Some models wrap in extra text — extract the JSON object
        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            text = text.substring(jsonStart, jsonEnd + 1);
        }

        try {
            ImageAnalysisResult result = objectMapper.readValue(text, ImageAnalysisResult.class);
            log.info("AI identified: '{}' (confidence: {}, cuisine: {}, provider: {})",
                    result.getIdentifiedName(), result.getConfidence(), result.getCuisineOrigin(), provider);
            return Optional.of(result);
        } catch (Exception e) {
            log.error("Failed to parse AI response as JSON: {}", e.getMessage());
            log.debug("Raw AI response: {}", text);
            return Optional.empty();
        }
    }
}
