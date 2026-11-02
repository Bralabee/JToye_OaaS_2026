package uk.jtoye.core.storefront;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.storefront.dto.PublicShopDto;

import java.util.List;

/**
 * The featured kitchens (quick 261102-r6u, D-02, design D17). Its own controller, so the three
 * {@code @WebMvcTest} slices of {@link PublicStorefrontController} are untouched, and mapped outside
 * {@code /shops/} on purpose, so no vendor slug can ever shadow the route.
 */
@RestController
@RequestMapping({"/public", "/api/v1/public"})
@Tag(name = "Public Storefront")
public class FeaturedShopsController {

    private final FeaturedShopsService featuredShopsService;

    public FeaturedShopsController(FeaturedShopsService featuredShopsService) {
        this.featuredShopsService = featuredShopsService;
    }

    @GetMapping("/featured-shops")
    @Operation(summary = "List featured shops",
            description = "An editorial, per-environment ordering hint for the landing page, never paid placement. "
                    + "Published shops only, in configured order. Configured slugs that are unknown or unpublished "
                    + "are skipped. An empty array when none are configured.")
    @ApiResponse(responseCode = "200", description = "The featured published shops, in configured order.")
    public List<PublicShopDto> featuredShops() {
        return featuredShopsService.featuredShops();
    }
}
