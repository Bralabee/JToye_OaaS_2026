package uk.jtoye.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:8081/realms/jtoye-dev}")
    private String issuerUri;

    @Bean
    public OpenAPI jtoyeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("J'Toye OaaS Core API")
                        .description("""
                                ## Multi-Tenant UK Retail SaaS Platform
                                
                                This API provides the core system-of-record functionality for J'Toye OaaS, 
                                a multi-tenant SaaS platform designed for UK retail operations.
                                
                                ### Security
                                All endpoints (except `/health`) require JWT authentication from Keycloak.
                                
                                ### Multi-Tenancy
                                - Tenant isolation is enforced via PostgreSQL Row-Level Security (RLS)
                                - JWT must contain `tenant_id`, `tenantId`, or `tid` claim
                                - Dev fallback: Use `X-Tenant-Id` header when JWT lacks tenant claim
                                
                                ### Compliance
                                - **Natasha's Law**: All products require `ingredients_text` and `allergen_mask`
                                - **HMRC VAT**: All financial transactions require `vat_rate`
                                
                                ### Pagination
                                List endpoints support pagination via query parameters:
                                - `page` (default: 0)
                                - `size` (default: 20, max: 100)
                                - `sort` (e.g., `createdAt,desc`)
                                """)
                        .version("0.1.0-SNAPSHOT")
                        .contact(new Contact()
                                .name("J'Toye Engineering")
                                .email("engineering@jtoye.uk"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://jtoye.uk/license")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development"),
                        new Server().url("https://api.jtoye.uk").description("Production")
                ))
                .addSecurityItem(new SecurityRequirement()
                        .addList("bearer-jwt"))
                .addSecurityItem(new SecurityRequirement()
                        .addList("tenant-header"))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT token from Keycloak. Must contain tenant claim (`tenant_id`, `tenantId`, or `tid`)."))
                        .addSecuritySchemes("tenant-header", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Tenant-Id")
                                .description("Dev fallback: UUID of tenant (only used when JWT lacks tenant claim)")));
    }
}
