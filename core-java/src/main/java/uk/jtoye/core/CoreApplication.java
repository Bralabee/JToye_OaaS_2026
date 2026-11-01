package uk.jtoye.core;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@RestController
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }

    /**
     * The API root points at the docs with a RELATIVE {@code Location} (SW-CLR-10, swarm epic #964).
     *
     * <p>A {@code RedirectView} goes through {@code sendRedirect}, which Tomcat absolutises from its
     * own connector scheme: behind the TLS-terminating ingress that is {@code http}, so the browser
     * was sent from https to {@code http://…/swagger-ui.html}. The header written here is not
     * rewritten by the container, and a relative reference resolves against whatever scheme and host
     * the client actually used (RFC 9110 10.2.2), with no forwarded header believed at all.
     */
    @Operation(summary = "Redirect to the API documentation")
    @ApiResponse(responseCode = "302", description = "Found: Location is /swagger-ui.html, a relative reference",
            headers = @Header(name = HttpHeaders.LOCATION, description = "/swagger-ui.html"))
    @GetMapping("/")
    public ResponseEntity<Void> redirectToSwagger() {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/swagger-ui.html").build();
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
