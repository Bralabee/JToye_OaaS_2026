package uk.jtoye.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * Uses Claude Vision to analyze food/grocery product images.
 * Identifies dishes (including cultural cuisines like Nigerian, Caribbean, etc.),
 * suggests ingredients, categories, and dietary information.
 */
@Service
public class ImageAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ImageAnalysisService.class);

    private final WebClient claudeClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String model;

    private static final String SYSTEM_PROMPT = """
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
            - Suya = spicy grilled beef skewers (Nigerian)
            - Jerk Chicken = marinated grilled chicken (Caribbean)
            - Curry Goat = slow-cooked goat curry (Caribbean)

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

            Set confidence between 0.0 and 1.0 based on how certain you are of the identification. \
            If the image is not food or grocery related, set confidence to 0.0 and identifiedName to "Unknown".
            Only return the JSON object, no markdown formatting, no code blocks.""";

    public ImageAnalysisService(
            @Value("${ai.anthropic.api-key:}") String apiKey,
            @Value("${ai.anthropic.model:claude-sonnet-4-20250514}") String model,
            @Value("${ai.anthropic.enabled:true}") boolean enabled,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.enabled = enabled && apiKey != null && !apiKey.isBlank();
        this.model = model;

        this.claudeClient = WebClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey != null ? apiKey : "")
                .defaultHeader("anthropic-version", "2023-06-01")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        if (this.enabled) {
            log.info("AI Image Analysis enabled with model: {}", model);
        } else {
            log.warn("AI Image Analysis disabled — set ANTHROPIC_API_KEY to enable");
        }
    }

    /**
     * Analyze a food/grocery image using Claude Vision.
     *
     * @param imageBytes the raw image bytes
     * @param mediaType  the image MIME type (e.g., "image/jpeg")
     * @return analysis result with identified name, ingredients, etc.
     */
    public Optional<ImageAnalysisResult> analyze(byte[] imageBytes, String mediaType) {
        if (!enabled) {
            log.debug("AI analysis skipped — service disabled");
            return Optional.empty();
        }

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // Build Claude Messages API request body
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 1024,
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(
                                Map.of(
                                        "type", "image",
                                        "source", Map.of(
                                                "type", "base64",
                                                "media_type", mediaType,
                                                "data", base64Image
                                        )
                                ),
                                Map.of(
                                        "type", "text",
                                        "text", "Identify this food item or grocery product. Respond with JSON only."
                                )
                        ))
                ),
                "system", SYSTEM_PROMPT
        );

        try {
            String responseJson = claudeClient.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseResponse(responseJson);
        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Check if the AI analysis service is available.
     */
    public boolean isEnabled() {
        return enabled;
    }

    private Optional<ImageAnalysisResult> parseResponse(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode content = root.path("content");

            if (!content.isArray() || content.isEmpty()) {
                log.warn("Empty content in Claude response");
                return Optional.empty();
            }

            // Extract the text content from the first content block
            String text = content.get(0).path("text").asText("");

            // Clean up — remove any markdown code block markers
            text = text.strip();
            if (text.startsWith("```")) {
                text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            ImageAnalysisResult result = objectMapper.readValue(text, ImageAnalysisResult.class);

            log.info("AI identified: '{}' (confidence: {}, cuisine: {})",
                    result.getIdentifiedName(), result.getConfidence(), result.getCuisineOrigin());

            return Optional.of(result);
        } catch (Exception e) {
            log.error("Failed to parse Claude response: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
