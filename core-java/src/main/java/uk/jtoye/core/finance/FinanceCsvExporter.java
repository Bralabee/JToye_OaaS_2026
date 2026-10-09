package uk.jtoye.core.finance;

import uk.jtoye.core.finance.dto.FinanceExportRow;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes a finance scope's ledger rows as CSV (Phase 37-11, RWO-027, roadmap SC-2: figures can be exported).
 *
 * <p><strong>Format.</strong> RFC 4180: a header row, CRLF line endings, and a field quoted (with inner quotes
 * doubled) only when it holds a comma, a quote, CR or LF. Columns:
 * {@code date, reference, order_number, entry_kind, amount, vat_rate, vat_amount, shop}.
 * <ul>
 *   <li>{@code date}: the row's time in UK local time (Europe/London, BST included), {@code dd/MM/yyyy HH:mm},
 *       the order a UK spreadsheet reads.</li>
 *   <li>{@code amount} and {@code vat_amount}: signed pounds with two decimals, e.g. {@code -15.00}. A REVERSAL
 *       is negative, so a column sum nets a void exactly as the summary does. VAT is the row's own
 *       {@link VatCalculator#vatFromGross} (the summary's arithmetic).</li>
 *   <li>{@code entry_kind}: SALE, REVERSAL or MANUAL, so a voided order shows as its sale and its reversal.</li>
 *   <li>{@code order_number} and {@code shop}: empty for a row with none (a MANUAL entry).</li>
 * </ul>
 *
 * <p><strong>Spreadsheet formula injection (T-37-31).</strong> A text cell that begins with {@code =},
 * {@code +}, {@code -} or {@code @} is executed as a formula by Excel, LibreOffice and Google Sheets, and the
 * reference is vendor-controlled text ({@code =HYPERLINK(...)} exfiltrates on a click). Such a cell is written
 * with a leading apostrophe, which every one of them reads as "this is text". Tab and carriage return are
 * neutralised the same way (OWASP's list). Only TEXT cells are neutralised: the amounts this class writes are
 * numbers, and a negative amount must stay a number, not become the text {@code '-15.00}.
 *
 * <p>Pure: no Spring, no I/O, so the format is a function of its input.
 */
public final class FinanceCsvExporter {

    /** The header row, in column order. */
    public static final String HEADER = "date,reference,order_number,entry_kind,amount,vat_rate,vat_amount,shop";

    private static final String CRLF = "\r\n";
    private static final DateTimeFormatter UK_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private FinanceCsvExporter() {
    }

    /** The whole document: the header row, then one line per row, each ending CRLF. No rows, header only. */
    public static String write(List<FinanceExportRow> rows) {
        StringBuilder csv = new StringBuilder(HEADER).append(CRLF);
        for (FinanceExportRow row : rows) {
            csv.append(UK_DATE_TIME.format(row.createdAt().atZoneSameInstant(FinanceScope.UK))).append(',')
                    .append(text(row.reference())).append(',')
                    .append(text(row.orderNumber())).append(',')
                    .append(row.entryKind() == null ? "" : row.entryKind().name()).append(',')
                    .append(pounds(row.amountPennies())).append(',')
                    .append(row.vatRate().name()).append(',')
                    .append(pounds(VatCalculator.vatFromGross(row.amountPennies(), row.vatRate()))).append(',')
                    .append(text(row.shopName()))
                    .append(CRLF);
        }
        return csv.toString();
    }

    /** Signed pounds with two decimals: 1500 -> 15.00, -250 -> -2.50. */
    static String pounds(long pennies) {
        return BigDecimal.valueOf(pennies, 2).toPlainString();
    }

    /** A free-text cell: formula-neutralised, then RFC 4180-escaped. Null is an empty cell. */
    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return escape(neutralise(value));
    }

    /** Prefix an apostrophe when the cell would otherwise be read as a formula. */
    static String neutralise(String value) {
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    /** RFC 4180: quote a field holding a comma, quote, CR or LF, and double every quote inside it. */
    static String escape(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
