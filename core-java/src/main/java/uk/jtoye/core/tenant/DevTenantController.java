package uk.jtoye.core.tenant;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.common.CurrentTenant;

import java.util.UUID;

/**
 * Development-only endpoint for creating tenants.
 * NOT available in production for security.
 *
 * <p><b>Access control (issue #83 P1-1):</b> the class-level
 * {@code @PreAuthorize("hasRole('admin')")} gate is defense-in-depth on top of
 * the existing {@code @Profile({"dev","local"})} restriction — even in dev/local
 * the admin surface now requires the {@code admin} realm role.
 */
@RestController
@RequestMapping("/dev/tenants")
@PreAuthorize("hasRole('admin')")  // issue #83 P1-1: admin surface requires the admin realm role (defense-in-depth)
@Profile({"dev", "local"})  // Only active in non-production profiles; "default" removed so a
                            // missing SPRING_PROFILES_ACTIVE cannot expose tenant creation in prod
public class DevTenantController {
    private final DevTenantService service;

    public DevTenantController(DevTenantService service) {
        this.service = service;
    }

    @PostMapping("/ensure")
    @Transactional
    public ResponseEntity<String> ensureTenant(@RequestParam(name = "name", required = false) String name) {
        UUID tenant = CurrentTenant.require();
        String effectiveName = (name == null || name.isBlank()) ? ("tenant-" + tenant) : name;
        service.ensureTenantExists(tenant, effectiveName);
        return ResponseEntity.ok("ensured:" + tenant);
    }
}
