package uk.jtoye.core.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

/**
 * The transaction list's page plus the scope it was filtered on (Phase 37-11, RWO-027). Additive to the
 * page shape the dashboard already reads ({@code content}, {@code totalElements}, {@code totalPages}, ...):
 * the only new member is {@code scope}.
 *
 * <p>Built from a repository page with its own content, pageable and total, so PageImpl's total
 * recomputation (it rewrites the total when offset + size exceeds it) can only reproduce the repository's
 * own consistent total.
 */
public class FinanceTransactionPage extends PageImpl<FinancialTransactionDto> {

    private final FinanceScopeDto scope;

    public FinanceTransactionPage(Page<FinancialTransactionDto> page, FinanceScopeDto scope) {
        super(page.getContent(), page.getPageable(), page.getTotalElements());
        this.scope = scope;
    }

    @Schema(description = "The scope this page was filtered on (Phase 37-11).")
    public FinanceScopeDto getScope() {
        return scope;
    }
}
