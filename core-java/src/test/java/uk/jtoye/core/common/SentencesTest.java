package uk.jtoye.core.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.jtoye.core.testsupport.IntegrationTestSupport.locateRepoFile;

/**
 * G-37-6 defect 2 (37-57): the Java sentence-end rule, the same table as 37-56's
 * {@code frontend/lib/__tests__/sentence-end.test.ts}, and the parity test that holds
 * {@link Sentences} equal to {@code frontend/lib/sentence-end.ts}.
 */
class SentencesTest {

    private static final String TS_SOURCE = "frontend/lib/sentence-end.ts";

    /**
     * The JavaScript whitespace class written out: what {@code trimEnd} drops. Held equal to the
     * TypeScript TRAILING_SPACE_CODE_POINTS declaration by {@link #parity_trailingSpaceCodePointsEqualTheTypeScriptDeclaration}.
     */
    private static final int[] TRAILING_SPACE = {
            0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF
    };

    @Test
    @DisplayName("an ordinary name gains one full stop")
    void ordinaryNameGainsAFullStop() {
        assertThat(Sentences.withFullStop("Mama Ade's Kitchen")).isEqualTo("Mama Ade's Kitchen.");
    }

    @Test
    @DisplayName("a name ending in a full stop, '!', '?' or '…' is unchanged")
    void terminatorsAreKept() {
        assertThat(Sentences.withFullStop("Peckham Jollof Co.")).isEqualTo("Peckham Jollof Co.");
        assertThat(Sentences.withFullStop("Yum!")).isEqualTo("Yum!");
        assertThat(Sentences.withFullStop("Really?")).isEqualTo("Really?");
        assertThat(Sentences.withFullStop("Wait…")).isEqualTo("Wait…");
    }

    @Test
    @DisplayName("trailing space, tab and no-break space are dropped before the decision")
    void trailingWhitespaceIsDropped() {
        assertThat(Sentences.withFullStop("Mama Ade's Kitchen  ")).isEqualTo("Mama Ade's Kitchen.");
        assertThat(Sentences.withFullStop("Peckham Jollof Co. ")).isEqualTo("Peckham Jollof Co.");
        assertThat(Sentences.withFullStop("Peckham Jollof Co.\t")).isEqualTo("Peckham Jollof Co.");
        assertThat(Sentences.withFullStop("Peckham Jollof Co." + (char) 0x00A0)).isEqualTo("Peckham Jollof Co.");
    }

    @Test
    @DisplayName("the trailing whitespace dropped is exactly the declared 25 code units, over every code unit 0x0000-0xFFFF")
    void trailingWhitespaceIsExactlyTheDeclaredSet() {
        boolean[] declared = new boolean[0x10000];
        for (int cp : TRAILING_SPACE) {
            declared[cp] = true;
        }
        List<String> disagreements = new ArrayList<>();
        for (int c = 0; c <= 0xFFFF; c++) {
            boolean dropped = Sentences.withFullStop("Co." + (char) c).equals("Co.");
            if (dropped != declared[c]) {
                disagreements.add(String.format("U+%04X %s", c,
                        dropped ? "dropped but not declared" : "declared but not dropped"));
            }
        }
        assertThat(disagreements).as("code units where the trim and the declared set disagree").isEmpty();
    }

    @Test
    @DisplayName("edge rows: empty, closing quote, markup-like text, an inner full stop, two full stops")
    void edgeRows() {
        assertThat(Sentences.withFullStop("")).isEqualTo(".");
        assertThat(Sentences.withFullStop("The \"Co.\"")).isEqualTo("The \"Co.\".");
        assertThat(Sentences.withFullStop("<b>Co.</b>")).isEqualTo("<b>Co.</b>.");
        assertThat(Sentences.withFullStop("Mid. Name Ltd")).isEqualTo("Mid. Name Ltd.");
        assertThat(Sentences.withFullStop("Odd Name..")).isEqualTo("Odd Name..");
    }

    @Test
    @DisplayName("fullStopAfter is '' exactly where withFullStop appends nothing, and endsSentence agrees with both")
    void fullStopAfterAndEndsSentenceAgreeWithWithFullStop() {
        List<String> rows = List.of("Mama Ade's Kitchen", "Peckham Jollof Co.", "Yum!", "Really?", "Wait…",
                "Co. ", "Co.\t", "Co." + (char) 0x00A0, "", "   ", "The \"Co.\"", "<b>Co.</b>", "Mid. Name Ltd",
                "Odd Name..", "pending");
        for (String row : rows) {
            String trimmed = trimDeclared(row);
            String ended = Sentences.withFullStop(row);
            assertThat(ended).as("withFullStop(%s) is the trimmed text, or it plus one full stop", row)
                    .isIn(trimmed, trimmed + ".");
            boolean appended = ended.equals(trimmed + ".");
            assertThat(Sentences.fullStopAfter(row)).as("fullStopAfter(%s)", row).isEqualTo(appended ? "." : "");
            assertThat(Sentences.endsSentence(row)).as("endsSentence(%s)", row).isEqualTo(!appended);
        }
    }

    /** Drops trailing code units in the declared set; independent of the class under test. */
    private static String trimDeclared(String text) {
        int end = text.length();
        while (end > 0) {
            int last = text.charAt(end - 1);
            if (Arrays.stream(TRAILING_SPACE).noneMatch(cp -> cp == last)) {
                break;
            }
            end--;
        }
        return text.substring(0, end);
    }

    @Test
    @DisplayName("parity: Sentences.TERMINATORS equals SENTENCE_TERMINATORS in frontend/lib/sentence-end.ts")
    void parity_terminatorsEqualTheTypeScriptDeclaration() throws IOException {
        String source = typeScriptSource();
        Matcher m = Pattern.compile("export const SENTENCE_TERMINATORS = \"([^\"]*)\"").matcher(source);
        assertThat(m.find()).as("SENTENCE_TERMINATORS declaration found in %s", TS_SOURCE).isTrue();
        String declared = m.group(1);
        assertThat(declared).as("SENTENCE_TERMINATORS in %s parses to something", TS_SOURCE).isNotEmpty();
        assertThat(Sentences.TERMINATORS).as("Sentences.TERMINATORS vs %s SENTENCE_TERMINATORS", TS_SOURCE)
                .isEqualTo(declared);
    }

    @Test
    @DisplayName("parity: the 25 trailing-space code units equal TRAILING_SPACE_CODE_POINTS in frontend/lib/sentence-end.ts")
    void parity_trailingSpaceCodePointsEqualTheTypeScriptDeclaration() throws IOException {
        String source = typeScriptSource();
        Matcher m = Pattern.compile("export const TRAILING_SPACE_CODE_POINTS = \\[([^\\]\\n]*)\\]").matcher(source);
        assertThat(m.find()).as("one-line TRAILING_SPACE_CODE_POINTS declaration found in %s", TS_SOURCE).isTrue();
        List<Integer> declared = new ArrayList<>();
        Matcher hex = Pattern.compile("0x([0-9a-fA-F]+)").matcher(m.group(1));
        while (hex.find()) {
            declared.add(Integer.parseInt(hex.group(1), 16));
        }
        assertThat(declared).as("TRAILING_SPACE_CODE_POINTS in %s parses to something", TS_SOURCE).isNotEmpty();
        assertThat(declared).as("%s TRAILING_SPACE_CODE_POINTS vs SentencesTest's list", TS_SOURCE)
                .containsExactlyElementsOf(Arrays.stream(TRAILING_SPACE).boxed().toList());
    }

    private static String typeScriptSource() throws IOException {
        Path file = locateRepoFile(TS_SOURCE);
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
