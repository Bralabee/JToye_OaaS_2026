package uk.jtoye.core.gdpr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the subject collects the copy of their data that an Article 15 request produced (31.1-17,
 * D-01, #778). The emailed link opens the web page {@code /data-request/download#token=...}; the
 * page sends the token here only when the person presses "Show my data".
 *
 * <p>Anonymous by necessity, like {@link DsarVerificationController}: the subject has no account and
 * no credential, and the single-use token that reached only their verified mailbox IS the
 * authorisation. Mounted under {@code /public/gdpr}, served at {@code /api/v1/public/gdpr/...},
 * inside the existing anonymous allowance and the tenant-less public rate-limiting tier.
 *
 * <h2>POST only, and that is the security property, not a style choice</h2>
 *
 * Unlike the verification link, there is deliberately NO GET variant. The token is single-use and
 * spends a personal-data document: a GET would put it in a request line (access logs, APM spans,
 * proxies, history) and — worse — let a mail scanner or link prefetcher spend it before the person
 * ever saw the page (threat T-31.1-61). The link carries the token in the URL fragment, which no
 * browser sends to a server, and the page POSTs it in a body after an explicit press.
 *
 * <h2>One refusal for every failure</h2>
 *
 * Unknown, expired, purged and used tokens all raise {@code DsarExportUnavailableException}, which
 * {@code GlobalExceptionHandler} renders as one byte-identical 404 (T-31.1-62).
 *
 * <h2>A REQUEST thread that declares no system authority</h2>
 *
 * It touches only {@code dsar_access_export} through {@link DsarExportDownloadService};
 * {@code SystemPrincipalGuardTest} scans this file to keep that true. There is no TenantContext on
 * this thread, which is also why the shared idempotency store cannot serve it (the 31-05 precedent):
 * the consume is idempotent by construction instead — a replay can only ever be refused.
 */
@RestController
@RequestMapping("/public/gdpr")
@Tag(name = "Data Subject Requests",
        description = "Anonymous, cross-tenant UK GDPR data-subject request intake. No credential "
                + "required; responses are deliberately opaque.")
public class DsarExportController {

    private final DsarExportDownloadService downloadService;

    public DsarExportController(DsarExportDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @PostMapping(value = "/dsar/export",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Download the copy of your data (single use)",
            description = "Spends the single-use token from the link emailed when a data-subject "
                    + "access request was fulfilled, and returns the export document (format "
                    + "jtoye-dsar-export/1) exactly once. The payload is destroyed in the same "
                    + "statement that marks it used. The token must be sent in a JSON body; there "
                    + "is no GET variant, so a link prefetcher cannot spend it. Unknown, expired and "
                    + "already-used tokens all receive the same 404. Not idempotent-replayable by "
                    + "design: a repeat is always refused.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "The export document, once. Sent with Cache-Control: no-store.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(type = "object",
                                    description = "The jtoye-dsar-export/1 document."))),
            @ApiResponse(responseCode = "404",
                    description = "The link has been used, has expired or was not recognised "
                            + "(type https://jtoye.uk/errors/dsar-export-unavailable, code "
                            + "DSAR_EXPORT_UNAVAILABLE). One body for every cause. The RFC 7807 "
                            + "body is declared by ProblemDetailResponseCustomizer, as for every error.")
    })
    public ResponseEntity<String> download(@RequestBody ExportRequest body) {
        String document = downloadService.consume(body == null ? null : body.token());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(document);
    }

    /** JSON body: the token travels here, never in the request line. */
    @Schema(description = "The single-use token from the emailed download link.")
    public record ExportRequest(String token) {
    }
}
