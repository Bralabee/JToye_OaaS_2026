package uk.jtoye.core.storefront.dto;

import java.time.OffsetDateTime;

public class PublicAnnouncementDto {
    private String title;
    private String body;
    private OffsetDateTime validUntil;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(OffsetDateTime validUntil) { this.validUntil = validUntil; }
}
