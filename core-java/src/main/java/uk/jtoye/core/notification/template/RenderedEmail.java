package uk.jtoye.core.notification.template;

/**
 * The rendered output of {@link EmailTemplateRenderer} for one event: a subject
 * line plus both a branded HTML body and a plain-text alternative
 * (D-01 — {@code multipart/alternative} for deliverability). {@code EmailChannel}
 * maps these three fields directly onto a {@code MimeMessageHelper}.
 */
public record RenderedEmail(String subject, String html, String text) {
}
