package uk.jtoye.core.review;

/**
 * 37-35 RED scaffold: the reviewer name exactly as stored, which is what the public review read
 * publishes today. Replaced by the display rule in the GREEN commit.
 */
public final class ReviewerDisplayName {

    private ReviewerDisplayName() {
    }

    public static String of(String fullName) {
        return fullName;
    }
}
