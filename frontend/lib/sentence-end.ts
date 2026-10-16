/**
 * The one rule for ending a sentence with a value (G-37-6 defect 2, 37-56).
 *
 * Why: a name a vendor or a person typed can already end a sentence. "These items are prepared
 * by Peckham Jollof Co." must not read "Co.." because the template put its own full stop after
 * the name. Every sentence on the frontend that ends with such a value ends it through
 * `withFullStop`, so the rule is decided here once, not at each call site.
 *
 * The convention is British English: an abbreviation's full stop also ends the sentence, and so
 * does a closing "!", "?" or "…". A closing quote or bracket does NOT end it — the full stop goes
 * after it. Only the last character decides; nothing else is removed, rewritten or interpreted
 * (markup-like text stays text), and a name that already ends in two full stops is returned as it
 * was: the rule never repairs input.
 *
 * Trailing whitespace, dropped before deciding, means exactly TRAILING_SPACE_CODE_POINTS — the
 * JavaScript whitespace class written out. It is declared rather than left to each language's own
 * class because Java's `Character.isWhitespace` disagrees (it drops U+001C-U+001F and keeps
 * U+FEFF). The Java mirror is `uk.jtoye.core.common.Sentences` (37-57); a parity test there reads
 * SENTENCE_TERMINATORS and TRAILING_SPACE_CODE_POINTS from this file, so keep each declaration on
 * one line in its current form.
 *
 * This file is the one place a full stop is appended after a value: the source guard in
 * `__tests__/sentence-end-call-sites.test.ts` exempts it by path and fails on any other.
 */

/** What already ends a sentence: full stop, exclamation mark, question mark, ellipsis. */
export const SENTENCE_TERMINATORS = ".!?…"

/** The trailing whitespace dropped before deciding (the JavaScript whitespace class). */
export const TRAILING_SPACE_CODE_POINTS = [0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020, 0x00a0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff]

/** True when the text, trailing whitespace removed, already ends with a sentence terminator. */
export function endsSentence(text: string): boolean {
  const trimmed = text.trimEnd()
  return trimmed.length > 0 && SENTENCE_TERMINATORS.includes(trimmed[trimmed.length - 1])
}

/** The text with trailing whitespace removed, followed by one full stop unless it already ends a sentence. */
export function withFullStop(text: string): string {
  const trimmed = text.trimEnd()
  return endsSentence(trimmed) ? trimmed : `${trimmed}.`
}
