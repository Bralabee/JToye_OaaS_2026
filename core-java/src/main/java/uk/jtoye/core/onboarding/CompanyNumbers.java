package uk.jtoye.core.onboarding;

import java.util.Locale;

/**
 * Canonical form of a Companies House company number — the ONE normalisation shared by the
 * write path ({@code VendorOnboardingService} on create/update) and the read path
 * ({@code CompaniesHouseGate}, which uses it as the lookup key).
 *
 * <p>Why padding exists (INT-7 / adjudication A14, QA council 20260902-134741): the
 * Companies House Public Data API {@code GET /company/{companyNumber}} is an <em>exact-key</em>
 * lookup — 200 / 401 / 404 only, no fuzzy matching — and register keys are 8 characters with
 * leading zeros preserved (Tesco PLC is {@code 00445790}). A vendor who types {@code 445790}
 * therefore 404s, and once a 404 stops being WAIVED (the fail-open this same change closes)
 * that vendor would hard-park for a formatting slip. Purely numeric values shorter than 8 are
 * left-zero-padded; letter-prefixed numbers ({@code SC123456}, {@code NI000123}, {@code OC…})
 * are never padded because their letter prefix already fills the width.
 *
 * <p>Rules, in order: {@code null} → {@code null}; trim; blank → {@code null} (sole trader,
 * no register lookup); upper-case; then pad. Digit detection is ASCII-only on purpose — the
 * boundary {@code @Pattern} admits {@code [A-Za-z0-9]} and a non-ASCII digit must not be
 * silently turned into a plausible-looking key.
 */
public final class CompanyNumbers {

    /** Companies House register keys are exactly this wide. */
    public static final int REGISTER_KEY_LENGTH = 8;

    private CompanyNumbers() {
    }

    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() < REGISTER_KEY_LENGTH && isAsciiDigits(s)) {
            return "0".repeat(REGISTER_KEY_LENGTH - s.length()) + s;
        }
        return s;
    }

    private static boolean isAsciiDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
