package uk.jtoye.core.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.config.DatabaseConfigurationValidator;

import java.util.Map;

/**
 * Health endpoint exposing database security configuration status.
 * Useful for monitoring and validating RLS is correctly configured.
 *
 * @author J'Toye Engineering Team
 * @since 0.7.1
 */
@RestController
@RequestMapping("/health/security")
@Tag(name = "Health", description = "Security health and monitoring endpoints")
public class SecurityHealthController {

    private final DatabaseConfigurationValidator validator;

    public SecurityHealthController(DatabaseConfigurationValidator validator) {
        this.validator = validator;
    }

    /**
     * Get database security configuration status.
     * Returns information about RLS configuration and database user.
     *
     * Example response:
     * {
     *   "username": "jtoye_app",
     *   "isSuperuser": false,
     *   "rlsEnabled": true,
     *   "tablesWithRls": 5,
     *   "status": "SECURE"
     * }
     */
    @GetMapping
    @Operation(
        summary = "Get security configuration status",
        description = "Returns database security status including RLS configuration. " +
                      "Used to verify multi-tenant isolation is correctly configured."
    )
    public ResponseEntity<Map<String, Object>> getSecurityStatus() {
        Map<String, Object> status = validator.getSecurityStatus();
        return ResponseEntity.ok(status);
    }
}
