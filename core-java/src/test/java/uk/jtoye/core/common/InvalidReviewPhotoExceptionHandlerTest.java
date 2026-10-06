package uk.jtoye.core.common;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.exception.InvalidReviewPhotoException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc test pinning the #771 contract: a review whose {@code photoUrls} name anything
 * but the review's own photos is refused with a typed RFC 7807 400, so a machine client can tell
 * this refusal apart from the generic {@code invalid-argument} one. No Spring context.
 */
class InvalidReviewPhotoExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JacksonJsonHttpMessageConverter jackson =
                new JacksonJsonHttpMessageConverter(JsonMapper.builder().build());
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jackson)
                .build();
    }

    @Test
    void invalidReviewPhotoReturnsTyped400ProblemDetail() throws Exception {
        mockMvc.perform(get("/test-throw"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid Review Photo"))
                .andExpect(jsonPath("$.detail").value("test-detail"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/invalid-review-photo"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/test-throw")
        public String throwIt() {
            throw new InvalidReviewPhotoException("test-detail");
        }
    }
}
