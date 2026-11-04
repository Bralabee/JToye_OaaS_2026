package uk.jtoye.core.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #822: the term shaping is where a search box could become a pattern, so it is pinned here. */
class ListSearchTest {

    @Test
    @DisplayName("null, empty and whitespace-only are no search at all")
    void blankIsNoSearch() {
        assertThat(ListSearch.of(null)).isNull();
        assertThat(ListSearch.of("")).isNull();
        assertThat(ListSearch.of("  \t ")).isNull();
    }

    @Test
    @DisplayName("the term is lower-cased and wrapped for a substring match")
    void lowerCasedSubstring() {
        ListSearch s = ListSearch.of("  Ada Lovelace ");
        assertThat(s.text()).isEqualTo("%ada lovelace%");
    }

    @Test
    @DisplayName("LIKE metacharacters are escaped so they match themselves, never everything")
    void metacharactersAreData() {
        assertThat(ListSearch.of("100%").text()).isEqualTo("%100\\%%");
        assertThat(ListSearch.of("a_b").text()).isEqualTo("%a\\_b%");
        assertThat(ListSearch.of("c:\\x").text()).isEqualTo("%c:\\\\x%");
        assertThat(ListSearch.of("%").text()).isEqualTo("%\\%%");
    }

    @Test
    @DisplayName("the phone form drops spaces and is consulted only when the term has a digit")
    void phoneForm() {
        ListSearch withDigits = ListSearch.of("07700 900 123");
        assertThat(withDigits.searchesPhone()).isTrue();
        assertThat(withDigits.phone()).isEqualTo("%07700900123%");

        ListSearch word = ListSearch.of("ada");
        assertThat(word.searchesPhone()).isFalse();
    }

    @Test
    @DisplayName("a term past the bound is cut, never turned into an unbounded pattern")
    void bounded() {
        ListSearch s = ListSearch.of("x".repeat(5000));
        assertThat(s.text()).isEqualTo("%" + "x".repeat(ListSearch.MAX_LENGTH) + "%");
    }
}
