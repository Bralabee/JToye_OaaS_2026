package uk.jtoye.core.gdpr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The one-way subject identifier for the DSAR path, and the <b>single</b> implementation of it.
 *
 * <h2>Why this class exists rather than a second copy of six lines</h2>
 *
 * Two systems have to agree on this digest byte for byte: {@link DsarIntakeService} computes it
 * from the address a data subject typed into a public form, and {@link DsarFanoutWorker} recomputes
 * it over every tenant's customer rows to find the matches. If the two normalisations diverge by so
 * much as a lower-casing locale, the fan-out matches NOTHING — and it does so silently: the intake
 * still returns 202, the queue still drains, every request is still marked complete, and every test
 * still passes, because "no tenant held this address" and "I hashed it differently from you" are
 * indistinguishable from the outside. That is the exact shape of failure V62's header warns about
 * and the reason 31-05 wrote the normalisation contract down.
 *
 * <p>A written contract is not enough on its own — it is a rule two files can drift away from.
 * Sharing ONE implementation makes agreement <em>structural</em> rather than something to be
 * re-proven every time either side is edited. The end-to-end coverage is the complement: the
 * integration test lodges an address in one surface form ({@code "  MIXED.CASE@EXAMPLE.COM  "}) and
 * seeds the customer in another ({@code "mixed.case@example.com"}), so a divergence in either
 * direction reds the erasure-record count rather than merely a unit assertion about a string.
 *
 * <h2>Why the match is computed in Java and not in SQL</h2>
 *
 * PostgreSQL 11+ has a built-in {@code sha256(bytea)}, so
 * {@code encode(sha256(convert_to(lower(btrim(email)), 'UTF8')), 'hex')} would push the comparison
 * server-side and need no extension (which matters — {@code scripts/check-no-create-extension.sh}
 * forbids one, and the Flyway role could not create it anyway). It was rejected because the two
 * normalisations are NOT the same function and the difference is invisible until it costs someone
 * their statutory right:
 *
 * <ul>
 *   <li>{@link String#trim()} strips every character {@code <= U+0020} — tab, newline, carriage
 *       return, form feed. PostgreSQL's {@code btrim(x)} strips SPACES only.</li>
 *   <li>{@link String#toLowerCase(Locale)} with {@link Locale#ROOT} is locale-independent by
 *       construction. PostgreSQL's {@code lower()} follows the database collation, so the same
 *       address can fold differently on two servers.</li>
 * </ul>
 *
 * Both differences are measured against a real Postgres by
 * {@code DsarFanoutIntegrationTest.theSqlSideDigestIsNotEquivalentToThisOne}, which records where
 * the two forms disagree rather than asserting they agree. Paying a per-tenant projection scan to
 * keep one authoritative definition is the better trade for a legal-floor feature that runs on a
 * queue which is usually empty.
 *
 * <h2>The address itself is never stored</h2>
 *
 * V42 set the rule for {@code erasure_records} and V62 follows it verbatim: an intake keyed by an
 * email address would be a NEW personal-data store created by a privacy feature. Everything here is
 * one-way.
 *
 * <p><b>Not interchangeable with {@code GdprService}'s own hash.</b> That one digests the address
 * AS-IS with no normalisation, and is what {@code ErasureRecord.subjectEmailSha256} holds. The two
 * values coincide only for an already-normalised address. Neither is wrong; they answer different
 * questions, and this javadoc exists so nobody assumes they are the same value.
 */
public final class DsarSubjectDigest {

    private DsarSubjectDigest() {
    }

    /**
     * The normalisation contract: trim, then lower-case under {@link Locale#ROOT}.
     *
     * <p>{@code Locale.ROOT} is load-bearing rather than stylistic. Under a Turkish default locale
     * {@code "I".toLowerCase()} is {@code "ı"} (dotless i, U+0131), so an address containing a
     * capital I would hash differently on a machine whose locale differed from the one that lodged
     * the request — a fan-out that silently matches nothing, decided by an environment variable.
     */
    public static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** The subject identifier: SHA-256 hex over the normalised, UTF-8 encoded address. */
    public static String of(String email) {
        return sha256Hex(normalise(email));
    }

    /**
     * Lowercase hex SHA-256 of the input, UTF-8 encoded. Also used for the verification token,
     * which is held as a digest for the same reason the address is: a readable token at rest is a
     * bearer credential at rest.
     */
    public static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
