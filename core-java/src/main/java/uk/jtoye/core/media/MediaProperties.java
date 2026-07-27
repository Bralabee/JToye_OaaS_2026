package uk.jtoye.core.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config-declared budget for the Phase 24 image pipeline (IMG-02, decision
 * D-02a). Mirrors {@link uk.jtoye.core.storage.StorageProperties}: hand-written
 * getters/setters (no Lombok), sensible defaults, env-overridable under the
 * {@code jtoye.media.*} key.
 *
 * <p>The defaults here are the D-02a starting budget — WebP derivative at
 * 1600px max dimension / quality 80 / 400px thumbnail. They are the single
 * source of the numbers: {@code MediaNormalizer} reads every tunable from this
 * bean and carries NO numeric image literal of its own (GLOBAL_RULE_6 /
 * ARCHITECTURE_RULE_8).
 */
@ConfigurationProperties(prefix = "jtoye.media")
public class MediaProperties {

    /** Longest-edge cap for the stored WebP derivative, in pixels. */
    private int maxDimension = 1600;

    /** cwebp quality (0-100) for the derivative and thumbnail. */
    private int quality = 80;

    /** Longest-edge cap for the WebP thumbnail, in pixels. */
    private int thumbnail = 400;

    /**
     * Decompression-bomb guard: the maximum decoded megapixels
     * (width*height / 1_000_000) allowed. Enforced at the ImageIO header read
     * BEFORE any pixel buffer is allocated (RESEARCH Pitfall 2).
     */
    private int maxMegapixels = 40;

    /**
     * Reject-early upload byte cap. Kept in sync with
     * {@code spring.servlet.multipart.max-file-size} and the client
     * {@code SERVER_MAX_BYTES}. Defaults to 5MB (mirrors storage).
     */
    private long maxUploadBytes = 5_242_880;

    /**
     * Whole-request byte budget for the reject-early Content-Length gate (WR-04). The
     * declared {@code Content-Length} covers the ENTIRE multipart envelope (boundaries +
     * form fields + the file), which legitimately exceeds {@link #maxUploadBytes} for a
     * near-limit file — so the reject-early gate must compare against THIS request budget,
     * NOT the per-file cap, or a valid near-limit upload is spuriously 413'd. Kept in sync
     * with {@code spring.servlet.multipart.max-request-size} (6MB), the authoritative
     * post-parse backstop that maps to 413 via {@code handleMaxUploadSizeExceeded}.
     */
    private long maxRequestBytes = 6_291_456;

    /**
     * How often the PENDING-row reaper sweeps for orphaned quarantine uploads
     * from crashed workers, in milliseconds. Consumed by {@link MediaPendingReaper}.
     */
    private long reaperIntervalMs = 600_000;

    /**
     * How old a {@code PENDING} {@code media_asset} must be before the reaper
     * treats it as a crashed-worker orphan (in milliseconds). Must comfortably
     * exceed the worker's normal processing time so a legitimately in-flight
     * upload is never reaped. Defaults to 15 minutes.
     */
    private long reaperGraceMs = 900_000;

    /**
     * How long a {@link MediaProcessingWorker} waits to acquire the {@code SELECT … FOR UPDATE}
     * claim on an asset row before giving up, in milliseconds (27-01 / D-04a). Applied as
     * {@code SET LOCAL lock_timeout} on the worker transaction's own connection.
     *
     * <p>Bounds the resources a BLOCKED loser holds — an AMQP consumer thread and a Hikari
     * connection (prod {@code maximum-pool-size: 10}) — for the duration of the winner's whole
     * pipeline. A timed-out loser dead-letters a no-op message, because the winner's committed row
     * makes it a {@code not_pending} skip anyway.
     *
     * <p>Not {@code NOWAIT} (0): that would convert every benign same-asset redelivery race into an
     * immediate exception, removing the small legitimate window in which a loser would have
     * acquired and cleanly skipped. 10 s absorbs that window; 0 s does not.
     */
    private long claimLockTimeoutMs = 10_000;

    /**
     * Maximum number of HUMAN-INITIATED re-drives of one asset (27-01 / D-04, T-27-03). Bounds
     * {@code media_asset.process_attempts} so a vendor cannot loop a permanently-broken upload
     * through the pipeline indefinitely.
     */
    private int maxProcessAttempts = 3;

    /**
     * How long the raw quarantine bytes are retained before {@link MediaQuarantineRetentionSweep}
     * may reclaim them, in milliseconds (27-01 / D-08). Default 72 h.
     *
     * <p>This number IS the plan's central trade: it converts <em>unbounded loss at 15 minutes</em>
     * (the old reaper deleted the vendor's only copy the moment a broker outage outlasted the
     * grace) into <em>bounded loss at 72 hours</em>. 72 h covers any realistic broker outage plus a
     * weekend, while bounding the F-3 exposure window — the {@code jtoye-images} bucket is
     * {@code mc anonymous set download}, so a quarantine object is anonymously readable by key for
     * as long as it is retained.
     */
    private long quarantineRetentionMs = 259_200_000L;

    /** How often {@link MediaQuarantineRetentionSweep} runs, in milliseconds. Default 1 h. */
    private long retentionIntervalMs = 3_600_000L;

    /**
     * Advisory vision (content-relevance) stage config (IMG-03). Disabled by
     * default — the Ollama provider is unreliable, so the pipeline ships behind
     * this advisory flag (SPEC / CONTEXT D-04, stage 6).
     */
    private Vision vision = new Vision();

    public int getMaxDimension() { return maxDimension; }
    public void setMaxDimension(int maxDimension) { this.maxDimension = maxDimension; }

    public int getQuality() { return quality; }
    public void setQuality(int quality) { this.quality = quality; }

    public int getThumbnail() { return thumbnail; }
    public void setThumbnail(int thumbnail) { this.thumbnail = thumbnail; }

    public int getMaxMegapixels() { return maxMegapixels; }
    public void setMaxMegapixels(int maxMegapixels) { this.maxMegapixels = maxMegapixels; }

    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }

    public long getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(long maxRequestBytes) { this.maxRequestBytes = maxRequestBytes; }

    public long getReaperIntervalMs() { return reaperIntervalMs; }
    public void setReaperIntervalMs(long reaperIntervalMs) { this.reaperIntervalMs = reaperIntervalMs; }

    public long getReaperGraceMs() { return reaperGraceMs; }
    public void setReaperGraceMs(long reaperGraceMs) { this.reaperGraceMs = reaperGraceMs; }

    public long getClaimLockTimeoutMs() { return claimLockTimeoutMs; }
    public void setClaimLockTimeoutMs(long claimLockTimeoutMs) { this.claimLockTimeoutMs = claimLockTimeoutMs; }

    public int getMaxProcessAttempts() { return maxProcessAttempts; }
    public void setMaxProcessAttempts(int maxProcessAttempts) { this.maxProcessAttempts = maxProcessAttempts; }

    public long getQuarantineRetentionMs() { return quarantineRetentionMs; }
    public void setQuarantineRetentionMs(long quarantineRetentionMs) { this.quarantineRetentionMs = quarantineRetentionMs; }

    public long getRetentionIntervalMs() { return retentionIntervalMs; }
    public void setRetentionIntervalMs(long retentionIntervalMs) { this.retentionIntervalMs = retentionIntervalMs; }

    public Vision getVision() { return vision; }
    public void setVision(Vision vision) { this.vision = vision; }

    /**
     * Advisory content-relevance stage (IMG-03). {@code enabled=false} keeps the
     * vision check off; when on, an analysis confidence below
     * {@code minConfidence} FLAGS the ACTIVE asset for the vendor review queue
     * (never a hard reject — SPEC D3).
     */
    public static class Vision {
        private boolean enabled = false;
        private double minConfidence = 0.35;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    }
}
