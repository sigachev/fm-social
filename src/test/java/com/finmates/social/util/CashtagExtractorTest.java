package com.finmates.social.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-JUnit tests for {@link CashtagExtractor}. No Spring context, no DB.
 *
 * <p>Fixture inputs MUST match the FE behaviour produced by
 * {@code collectSegments()} at
 * {@code F:\Projects\finmates-front\src\components\mentions\mentionSegments.ts}.
 * If either regex changes, re-run the manual cross-check: paste each input
 * through both extractors and confirm the canonical (uppercase, sorted,
 * deduplicated) outputs match. The SQL backfill in
 * {@code V17__cashtag_extraction.sql} is the third leg of that contract.</p>
 *
 * <p><b>Known asymmetry:</b> the regex requires the symbol to start with a
 * letter, so digit-prefix tickers ({@code 1INCH}, {@code 00}) do not match —
 * see the {@code startsWithDigitIgnored} and {@code oneInchIgnored} fixtures.
 * Acceptable for v1; relaxation is tracked in {@code fm-social/CLAUDE.md}
 * "Known issues / Follow-up tickets".</p>
 *
 * <p>Last FE/BE cross-checked: 2026-05-14 — both produced identical output for
 * all fixture inputs.</p>
 */
class CashtagExtractorTest {

    @Test
    @DisplayName("single cashtag is extracted and uppercased")
    void singleCashtag() {
        assertThat(CashtagExtractor.extract("buying $ETH today")).containsExactly("ETH");
    }

    @Test
    @DisplayName("mixed-case input dedupes to a single uppercase entry")
    void mixedCaseDedupes() {
        assertThat(CashtagExtractor.extract("$btc $ETH $Eth")).containsExactly("BTC", "ETH");
    }

    @Test
    @DisplayName("multiple distinct cashtags come out sorted")
    void multipleDistinct() {
        assertThat(CashtagExtractor.extract("rotating from $BTC to $ETH and $SOL"))
                .containsExactly("BTC", "ETH", "SOL");
    }

    @Test
    @DisplayName("adjacent punctuation does not consume into the symbol")
    void adjacentPunctuation() {
        assertThat(CashtagExtractor.extract("$ETH, $BTC.")).containsExactly("BTC", "ETH");
    }

    @Test
    @DisplayName("plain dollar amounts and emails do not produce false positives")
    void dollarAmountIgnored() {
        assertThat(CashtagExtractor.extract("email me $50 at me@x.com")).isEmpty();
    }

    @Test
    @DisplayName("$ preceded by an alphanumeric does not match (word-start required)")
    void noSpaceBeforeDollarIgnored() {
        assertThat(CashtagExtractor.extract("foo$BAR no space before")).isEmpty();
    }

    @Test
    @DisplayName("$ preceded by whitespace matches")
    void spaceBeforeDollarMatches() {
        assertThat(CashtagExtractor.extract("price $BAR yes")).containsExactly("BAR");
    }

    @Test
    @DisplayName("a bare lone dollar sign is ignored")
    void bareLoneDollarIgnored() {
        assertThat(CashtagExtractor.extract("$")).isEmpty();
    }

    @Test
    @DisplayName("symbols starting with a digit are ignored ($1ABC)")
    void startsWithDigitIgnored() {
        assertThat(CashtagExtractor.extract("$1ABC starts with digit")).isEmpty();
    }

    @Test
    @DisplayName("real digit-prefix ticker $1INCH is ignored — see CLAUDE.md known asymmetry")
    void oneInchIgnored() {
        // 1INCH is a real asset on the catalog, but the FE TOKEN_REGEX
        // ([A-Za-z][A-Za-z0-9]{0,14}) cannot match it because the leading
        // char is a digit. BE mirrors this on purpose; both sides drop it.
        assertThat(CashtagExtractor.extract("buying $1INCH today")).isEmpty();
    }

    @Test
    @DisplayName("symbols longer than 15 chars are truncated at the cap")
    void fifteenCharCap() {
        // Regex captures [A-Za-z][A-Za-z0-9]{0,14} → max 15 chars total.
        // "$ABCDEFGHIJKLMNOPQ" captures the first 15 (ABCDEFGHIJKLMNO);
        // the remaining "PQ" is left as trailing non-match content.
        assertThat(CashtagExtractor.extract("$ABCDEFGHIJKLMNOPQ too long"))
                .containsExactly("ABCDEFGHIJKLMNO");
    }

    @Test
    @DisplayName("empty string returns an empty list")
    void emptyStringReturnsEmpty() {
        assertThat(CashtagExtractor.extract("")).isEmpty();
    }

    @Test
    @DisplayName("null input returns an empty list, never null")
    void nullReturnsEmpty() {
        assertThat(CashtagExtractor.extract(null)).isEmpty();
    }

    @Test
    @DisplayName("adjacent cashtags: only the first matches, the second's $ is preceded by alphanumeric")
    void adjacentCashtagsSecondIgnored() {
        // Cross-checked against FE collectSegments(): "$ETH$BTC" produces a
        // single token segment ("$ETH"); the second "$" is preceded by the
        // alphanumeric 'H', so the word-start rule rejects it on both sides.
        assertThat(CashtagExtractor.extract("$ETH$BTC adjacent")).containsExactly("ETH");
    }

    @Test
    @DisplayName("output is sorted alphabetically regardless of source order")
    void outputIsSorted() {
        assertThat(CashtagExtractor.extract("$ZRX $AAVE $MKR"))
                .containsExactly("AAVE", "MKR", "ZRX");
    }
}
