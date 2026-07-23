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
     * How often the PENDING-row reaper sweeps for orphaned quarantine uploads
     * from crashed workers, in milliseconds. Consumed by a later plan's reaper.
     */
    private long reaperIntervalMs = 600_000;

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

    public long getReaperIntervalMs() { return reaperIntervalMs; }
    public void setReaperIntervalMs(long reaperIntervalMs) { this.reaperIntervalMs = reaperIntervalMs; }

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
