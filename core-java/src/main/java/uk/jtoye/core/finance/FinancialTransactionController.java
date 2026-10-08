package uk.jtoye.core.finance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.exception.MissingTenantContextException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.finance.dto.FinanceTransactionPage;
import uk.jtoye.core.finance.dto.FinancialSummaryDto;
import uk.jtoye.core.finance.dto.FinancialTransactionDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * REST controller for financial transaction management.
 * All endpoints require JWT authentication and are automatically tenant-scoped.
 *
 * <p><b>Access control (Phase 37-11, RWO-027 / UXT-027; replaces the issue #83 P1-1 class-level
 * {@code hasRole('admin')} gate).</b> Each shop has its own figures, and the tenant ledger stays closed to
 * non-admins:
 * <ul>
 *   <li>Summary and list WITH {@code shopId}: {@code ShopAccessService.require(shopId, SHOP_MANAGER)}, and
 *       only that shop's rows are read. A Group admin passes for any shop of their own tenant.</li>
 *   <li>Summary and list WITHOUT {@code shopId}: {@code requireGroupAdmin()} (a realm admin, or a tenant-wide
 *       GROUP_ADMIN grant), and every row of the tenant is read, rows with no shop (MANUAL entries)
 *       included.</li>
 *   <li>A single row ({@code GET /{id}}) and a manual entry ({@code POST}) stay Group-admin.</li>
 * </ul>
 * STAFF is refused on every endpoint (goods P2-TUN-15: money endpoints refuse a non-admin). Every refusal
 * is the typed 403 {@code https://jtoye.uk/errors/shop-access-denied}. RLS still scopes every read to the
 * caller's tenant; the shop gate and RLS are complementary, not a replacement for one another.
 *
 * <p>Every summary and list response carries the {@code scope} it filtered on, so the dashboard's banner
 * reads what the API did rather than the shop switcher's own value. The CSV export
 * ({@code GET /export.csv}) has the same gate and scope, names the scope in its filename and echoes it in
 * {@code X-Finance-Scope}.
 *
 * Financial transactions are IMMUTABLE - no update or delete endpoints.
 */
@RestController
@RequestMapping("/financial-transactions")
@Tag(name = "Financial Transactions", description = "Financial transaction management endpoints (audit-compliant)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class FinancialTransactionController {

    private final FinancialTransactionService financialTransactionService;
    private final ShopAccessService shopAccessService;
    private final ShopRepository shopRepository;

    public FinancialTransactionController(FinancialTransactionService financialTransactionService,
                                          ShopAccessService shopAccessService,
                                          ShopRepository shopRepository) {
        this.financialTransactionService = financialTransactionService;
        this.shopAccessService = shopAccessService;
        this.shopRepository = shopRepository;
    }

    @GetMapping
    @Operation(summary = "List financial transactions",
            description = "Returns a paginated list of financial transactions, newest first. With shopId, only that "
                    + "shop's rows (the caller needs SHOP_MANAGER on it); without, every row of the tenant, rows "
                    + "with no shop included (Group admin only). from/to are inclusive UK days. The page carries "
                    + "the scope it was filtered on.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved transactions"),
            @ApiResponse(responseCode = "400", description = "from is after to, or a parameter is malformed"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "shop-access-denied: not a SHOP_MANAGER of shopId, or no "
                    + "shopId and not a Group admin"),
            @ApiResponse(responseCode = "404", description = "shopId is not a shop of the caller's tenant")
    })
    public FinanceTransactionPage getAllTransactions(
            @Parameter(description = "Only this shop's rows (needs SHOP_MANAGER on it). Omit for the whole tenant (Group admin).")
            @RequestParam(required = false) UUID shopId,
            @Parameter(description = "First UK day included (ISO date, e.g. 2026-10-01)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last UK day included (ISO date)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        FinanceScope scope = authorise(shopId, from, to);
        return new FinanceTransactionPage(financialTransactionService.getTransactions(scope, pageable), scope.toDto());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get transaction by ID", description = "Returns a single financial transaction by ID for the authenticated tenant (Group admin only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transaction found"),
            @ApiResponse(responseCode = "403", description = "shop-access-denied: not a Group admin"),
            @ApiResponse(responseCode = "404", description = "Transaction not found")
    })
    public ResponseEntity<FinancialTransactionDto> getTransactionById(
            @Parameter(description = "Transaction ID") @PathVariable UUID id) {
        shopAccessService.requireGroupAdmin();
        return financialTransactionService.getTransactionById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Financial transaction not found: " + id));
    }

    @PostMapping
    @Operation(summary = "Create financial transaction", description = "Creates a new financial transaction (Group admin only). Requires amount and VAT rate. Transactions are immutable after creation; a row with no order is MANUAL and has no shop.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Transaction created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing required fields"),
            @ApiResponse(responseCode = "403", description = "shop-access-denied: not a Group admin")
    })
    public ResponseEntity<FinancialTransactionDto> createTransaction(
            @Parameter(description = "Transaction creation request") @Valid @RequestBody CreateTransactionRequest request) {
        shopAccessService.requireGroupAdmin();
        FinancialTransactionDto dto = financialTransactionService.createTransaction(request);
        // issue #97 [P2-6]: inherit the WebConfig /api/v1-prefixed request path so Location resolves
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(dto.id())
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @GetMapping("/summary")
    @Operation(summary = "Get financial summary",
            description = "Returns aggregated revenue, expenses, net amount, and VAT breakdown. With shopId, that "
                    + "shop's figures only (the caller needs SHOP_MANAGER on it); without, the tenant's, rows with "
                    + "no shop included (Group admin only). from/to are inclusive UK days. The response carries "
                    + "the scope it was filtered on.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Summary generated successfully"),
            @ApiResponse(responseCode = "400", description = "from is after to, or a parameter is malformed"),
            @ApiResponse(responseCode = "403", description = "shop-access-denied: not a SHOP_MANAGER of shopId, or no "
                    + "shopId and not a Group admin"),
            @ApiResponse(responseCode = "404", description = "shopId is not a shop of the caller's tenant")
    })
    public FinancialSummaryDto getSummary(
            @Parameter(description = "Only this shop's figures (needs SHOP_MANAGER on it). Omit for the whole tenant (Group admin).")
            @RequestParam(required = false) UUID shopId,
            @Parameter(description = "First UK day included (ISO date, e.g. 2026-10-01)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last UK day included (ISO date)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return financialTransactionService.getSummary(authorise(shopId, from, to));
    }

    /** The scope echo header on the CSV export; exposed to cross-origin script by the CORS floor. */
    static final String SCOPE_HEADER = "X-Finance-Scope";

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Operation(summary = "Export financial transactions as CSV",
            description = "The same rows the summary and list count for the same scope, oldest first, as RFC 4180 "
                    + "CSV (header: date, reference, order_number, entry_kind, amount, vat_rate, vat_amount, shop). "
                    + "Dates are UK local time (dd/MM/yyyy HH:mm); amounts and VAT are signed pounds, so a voided "
                    + "order's SALE and REVERSAL net to zero. Text cells starting = + - @ are written with a "
                    + "leading apostrophe. Same gate as the summary: shopId needs SHOP_MANAGER on it, no shopId "
                    + "needs Group admin. The filename is jtoye-finance-{shop-slug|all-shops}-{from}-{to}.csv and "
                    + "X-Finance-Scope echoes the scope. A range with no rows is a header-only file. Read-only, "
                    + "so no Idempotency-Key is needed.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "CSV of the scope (header only when it has no rows)"),
            @ApiResponse(responseCode = "400", description = "from is after to, or a parameter is malformed"),
            @ApiResponse(responseCode = "403", description = "shop-access-denied: not a SHOP_MANAGER of shopId, or no "
                    + "shopId and not a Group admin"),
            @ApiResponse(responseCode = "404", description = "shopId is not a shop of the caller's tenant")
    })
    public ResponseEntity<byte[]> exportCsv(
            @Parameter(description = "Only this shop's rows (needs SHOP_MANAGER on it). Omit for the whole tenant (Group admin).")
            @RequestParam(required = false) UUID shopId,
            @Parameter(description = "First UK day included (ISO date, e.g. 2026-10-01)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last UK day included (ISO date)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        FinanceScope scope = authorise(shopId, from, to);
        byte[] body = financialTransactionService.exportCsv(scope).getBytes(StandardCharsets.UTF_8);
        String filename = scope.exportFilename(LocalDate.now(FinanceScope.UK));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .header(SCOPE_HEADER, scope.headerEcho())
                .body(body);
    }

    /**
     * The gate and the scope in one place (Phase 37-11), so the summary, the list and the export cannot
     * disagree about who may read what. The gate runs FIRST: a refused caller learns nothing from a range
     * error. With a shop: SHOP_MANAGER on it, and its name resolved under the caller's own tenant for the
     * scope echo. Without: Group admin.
     */
    FinanceScope authorise(UUID shopId, LocalDate from, LocalDate to) {
        if (shopId == null) {
            shopAccessService.requireGroupAdmin();
            return new FinanceScope(null, null, null, from, to);
        }
        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new MissingTenantContextException("Tenant context not set"));
        Shop shop = shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));
        return new FinanceScope(shopId, shop.getName(), shop.getSlug(), from, to);
    }

    // NOTE: No PUT or DELETE endpoints - financial transactions are IMMUTABLE
    // This maintains audit trail integrity per compliance requirements
}
