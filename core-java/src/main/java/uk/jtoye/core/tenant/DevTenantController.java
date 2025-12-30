package uk.jtoye.core.tenant;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
@RequestMapping("/dev/tenants")
@Profile({"dev", "local", "default"})  // Only active in non-production profiles
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
