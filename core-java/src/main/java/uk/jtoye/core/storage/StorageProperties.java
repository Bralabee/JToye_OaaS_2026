package uk.jtoye.core.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private S3Properties s3 = new S3Properties();
    private long maxFileSizeBytes = 5_242_880; // 5MB
    private List<String> allowedContentTypes = List.of("image/jpeg", "image/png", "image/webp", "image/gif");

    public S3Properties getS3() { return s3; }
    public void setS3(S3Properties s3) { this.s3 = s3; }
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
    public List<String> getAllowedContentTypes() { return allowedContentTypes; }
    public void setAllowedContentTypes(List<String> allowedContentTypes) { this.allowedContentTypes = allowedContentTypes; }

    public static class S3Properties {
        private String endpoint = "http://localhost:9000";
        private String region = "eu-west-2";
        private String bucket = "jtoye-images";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String publicUrl = "http://localhost:9000/jtoye-images";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getPublicUrl() { return publicUrl; }
        public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    }
}
