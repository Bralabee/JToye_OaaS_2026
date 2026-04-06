package uk.jtoye.core.product.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a bulk product import (CSV or image-based).
 */
public class BulkImportResult {
    private int totalRows;
    private int successCount;
    private int errorCount;
    private List<ProductDto> created = new ArrayList<>();
    private List<RowError> errors = new ArrayList<>();

    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }
    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
    public List<ProductDto> getCreated() { return created; }
    public void setCreated(List<ProductDto> created) { this.created = created; }
    public List<RowError> getErrors() { return errors; }
    public void setErrors(List<RowError> errors) { this.errors = errors; }

    public static class RowError {
        private int row;
        private String field;
        private String message;

        public RowError(int row, String field, String message) {
            this.row = row;
            this.field = field;
            this.message = message;
        }

        public int getRow() { return row; }
        public String getField() { return field; }
        public String getMessage() { return message; }
    }
}
