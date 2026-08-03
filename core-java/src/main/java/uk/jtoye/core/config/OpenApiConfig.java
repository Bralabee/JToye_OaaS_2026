package uk.jtoye.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.jtoye.core.security.TenantFilter;

import java.util.List;

/**
 * OpenAPI/Swagger configuration.
 * NOTE: Disabled in production profile for security reasons.
 * Swagger UI should only be available in dev/staging environments.
 */
@Configuration
@Profile("!prod") // Disable Swagger UI in production
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

                                                                ### Client Scopes (issue #206 [AI-4])
                                                                Least-privilege machine/integration access is granted via OAuth2 client
                                                                scopes (`catalog-scopes` security scheme, client-credentials grant):
                                                                - `catalog:read` — list/get products (read surface, authenticated-only)
                                                                - `catalog:write` — create/update/delete products + images (gates all nine product mutations)
                                                                - `orders:write` — create orders (enforced; gates `POST /orders`, Phase 25 [AI-02])
                                                                - `customers:write` — create customers (enforced; gates `POST /customers`, Phase 25 [AI-02])
                                                                - `orders:read`, `customers:read` — reserved taxonomy for the MCP model; defined, not yet enforced

                                                                A `catalog:read`-only token gets **200** on `GET /products` and **403** on
                                                                any product write. See `docs/security-scopes.md` for the client-credentials
                                                                recipe and realm re-import note.

                                                                ### Pagination
                                                                List endpoints support pagination via query parameters:
                                                                - `page` (default: 0)
                                                                - `size` (default: 20, max: 100)
                                                                - `sort` (e.g., `createdAt,desc`)
                                                                """)
                                                .version("1.2.0")
                                                .contact(new Contact()
                                                                .name("J'Toye Engineering")
                                                                .email("engineering@jtoye.uk"))
                                                .license(new License()
                                                                .name("Proprietary")
                                                                .url("https://jtoye.uk/license")))
                                .servers(List.of(
                                                new Server().url("http://localhost:8080")
                                                                .description("Local Development"),
                                                new Server().url("https://api.jtoye.uk").description("Production")))
                                .addSecurityItem(new SecurityRequirement()
                                                .addList("bearer-jwt"))
                                .addSecurityItem(new SecurityRequirement()
                                                .addList(TenantHeaderSchemeCustomizer.SCHEME_NAME))
                                .components(new Components()
                                                .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                                                .type(SecurityScheme.Type.HTTP)
                                                                .scheme("bearer")
                                                                .bearerFormat("JWT")
                                                                .description("JWT token from Keycloak. Must contain tenant claim (`tenant_id`, `tenantId`, or `tid`)."))
                                                // issue #440 [F-H2]: the header name comes from the filter that
                                                // honours it, never a copied literal, and TenantHeaderSchemeCustomizer
                                                // removes this scheme wherever that filter is not in the context —
                                                // so the document cannot advertise a mechanism the service lacks.
                                                .addSecuritySchemes(TenantHeaderSchemeCustomizer.SCHEME_NAME, new SecurityScheme()
                                                                .type(SecurityScheme.Type.APIKEY)
                                                                .in(SecurityScheme.In.HEADER)
                                                                .name(TenantFilter.TENANT_HEADER)
                                                                .description("Dev fallback: UUID of tenant (only used when JWT lacks tenant claim)"))
                                                // issue #206 [AI-4]: advertise the catalog capability scopes as an
                                                // OAuth2 client-credentials scheme. tokenUrl derives from issuerUri
                                                // (never hardcoded); catalog:read/catalog:write are enforced today,
                                                // and Phase 25 [AI-02] activates orders:write + customers:write as the
                                                // MCP write gates; orders:read/customers:read stay defined-but-unenforced.
                                                .addSecuritySchemes("catalog-scopes", new SecurityScheme()
                                                                .type(SecurityScheme.Type.OAUTH2)
                                                                .description("Least-privilege machine/integration access via OAuth2 "
                                                                                + "client-credentials scopes (issue #206 [AI-4]). A "
                                                                                + "catalog:read-only token lists products (200) but cannot "
                                                                                + "mutate them (403).")
                                                                .flows(new OAuthFlows()
                                                                                .clientCredentials(new OAuthFlow()
                                                                                                .tokenUrl(issuerUri + "/protocol/openid-connect/token")
                                                                                                .scopes(new Scopes()
                                                                                                                .addString("catalog:read", "Read the product catalog (list/get products)")
                                                                                                                .addString("catalog:write", "Create, update, and delete products and product images")
                                                                                                                .addString("orders:write", "Enforced; gates POST /orders (Phase 25 [AI-02])")
                                                                                                                .addString("customers:write", "Enforced; gates POST /customers (Phase 25 [AI-02])")
                                                                                                                .addString("orders:read", "Reserved for the MCP model — defined, not yet enforced")
                                                                                                                .addString("customers:read", "Reserved for the MCP model — defined-only, not enforced this phase"))))));
        }
}
