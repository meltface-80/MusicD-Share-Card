package com.musicd.sharecard.library

import java.text.Normalizer

/**
 * The one text-folding rule this app matches on.
 *
 * Every comparison — is this the same album as the one we cached, does the
 * Pitchfork page name the artist we asked about — goes through [text]. Two
 * copies of this rule that drift apart is how a lookup ends up caching under
 * one key and reading back under another.
 */
object Normalize {

    private val COMBINING = Regex("[\\u0300-\\u036f]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val LEADING_ARTICLE = Regex("^(the|a|an) ")

    /**
     * The letters NFKD will not take apart, and what they are worth.
     *
     * NFKD splits an accent off its letter, which is most of the job — but a
     * LIGATURE or a STROKED letter has no decomposition at all, so "Ænima"
     * came out as "nima" once the unmatched character was dropped. Pitchfork
     * files it as "aenima" and so does everyone else; a letter silently
     * vanishing is how an album ends up with no score and no explanation.
     */
    private val LIGATURES = mapOf(
        'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ß' to "ss",
        'đ' to "d", 'ð' to "d", 'þ' to "th", 'ł' to "l", 'ħ' to "h", 'ı' to "i"
    )

    /** Lowercase, strip accents, collapse everything else to single spaces. */
    fun text(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val expanded = buildString {
            for (c in s.lowercase()) append(LIGATURES[c] ?: c)
        }
        val folded = Normalizer.normalize(expanded, Normalizer.Form.NFKD)
        return COMBINING.replace(folded, "")
            .replace(NON_ALNUM, " ")
            .trim()
    }

    /**
     * Sort key: every record shop files "The Wall" under W, not T. Leading
     * articles are dropped for MATCHING only; the displayed title is untouched.
     */
    fun sortKey(normalized: String): String = LEADING_ARTICLE.replace(normalized, "")

    /**
     * Is one of these names the same act, or the same record, as the other.
     *
     * IT LIVES HERE FOR THE SAME REASON [text] DOES. It was a private method on
     * Metadata while Wikipedia was the only thing that had to ask "is this
     * really the act I named"; the similar-artist lookup asks it of Deezer's
     * top search hit, and the answer to that question must not depend on which
     * file is asking. A second copy that drifts is how one lookup refuses a
     * stranger and the next one accepts them.
     *
     * A PREFIX, NOT ANY RUN OF WORDS, AND THAT IS A FIX. This matched the
     * shorter name ANYWHERE inside the longer one, which meant "The Who" and
     * "The Guess Who" overlapped — both reduce to something ending in "who".
     * The comment above it named that exact pair as the case it rejected, for
     * as long as the function has existed. It did not: the leading article is
     * stripped for matching, so "who" sits at the END of "guess who" and a
     * run-of-words search found it. That is a stranger's biography on somebody
     * else's card, which is the one thing this guard exists to stop.
     *
     * Anchoring at the front keeps everything it is actually for — a name
     * qualified on the RIGHT — and drops nothing else:
     *
     *   "jay z"        vs "jay z feat alicia keys"  -> true
     *   "spiderland"   vs "spiderland (slint album)" -> true
     *   "abbey road"   vs "abbey road (remastered)"  -> true
     *   "the who"      vs "the guess who"            -> FALSE
     *
     * What it does cost is a name qualified on the LEFT: "Eno" no longer
     * matches "Brian Eno". That is the right way to be wrong here — a missing
     * blurb is honest, and the rule this app keeps returning to is that a
     * confident wrong answer is worse than none.
     */
    fun namesOverlap(a: String, b: String): Boolean {
        val na = sortKey(text(a))
        val nb = sortKey(text(b))
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        // A wikipedia page is often "Title (album)" or "Artist (band)".
        val strippedA = DESCRIPTOR.replace(na, "").trim()
        val strippedB = DESCRIPTOR.replace(nb, "").trim()
        if (strippedA == strippedB) return true
        return startsWithWords(strippedA, strippedB) || startsWithWords(strippedB, strippedA)
    }

    /**
     * Does [haystack] NAME [needle] — as a run of whole words, folded once.
     *
     * This is the prose counterpart to [namesOverlap], and the difference
     * between them is deliberate. [namesOverlap] compares two NAMES, where a
     * match anywhere but the front is exactly how "The Who" became "The Guess
     * Who". Here the haystack is a SENTENCE and the name may sit anywhere in
     * it — "…is the sixth studio album by American industrial metal band
     * Static-X" — so a run of words is the right search. Whole WORDS still,
     * never characters: "Low" must not be found inside "Lowlife".
     *
     * "AND" IS DROPPED FROM BOTH SIDES. A speaker writes "Nick Cave & the Bad
     * Seeds" and [text] spends the ampersand as a space, while Wikipedia's own
     * sentence spells it out — so the RIGHT article would be refused over a
     * conjunction. Only "and": dropping "the" with it would put the needle
     * "the who" back inside "the guess who", which is the pair this whole
     * family of rules exists to keep apart.
     */
    fun mentions(haystack: String, needle: String): Boolean {
        val hay = words(haystack)
        val name = words(needle)
        if (hay.isEmpty() || name.isEmpty() || name.size > hay.size) return false
        return (0..hay.size - name.size).any { at ->
            name.indices.all { hay[at + it] == name[it] }
        }
    }

    private fun words(s: String): List<String> =
        text(s).split(" ").filter { it.isNotEmpty() && it != "and" }

    /**
     * [hay] begins with every word of [needle], in order.
     *
     * Word by word rather than by string prefix: "the beat" must not match
     * "the beatles", and it would if this compared characters.
     */
    private fun startsWithWords(hay: String, needle: String): Boolean {
        val h = hay.split(" ").filter(String::isNotEmpty)
        val n = needle.split(" ").filter(String::isNotEmpty)
        if (n.isEmpty() || n.size > h.size) return false
        return n.indices.all { h[it] == n[it] }
    }

    private val DESCRIPTOR = Regex("\\b(album|band|musician|singer|song)\\b")

    /**
     * THE FIRST CREDITED ACT, FOR A SEARCH BOX AND NOTHING ELSE.
     *
     * A Roon card came back credited "Stan Getz / Cal Tjader / Alan Jay Lerner
     * / Frederick Loewe" — a performer, a co-performer and the two men who
     * wrote the songs — and every chip in the links row spent all four as one
     * search term. AllMusic answered "No search results were found for Stan
     * Getz Cal Tjader Alan Jay Lerner Frederick Loewe", which is correct: there
     * is no such act. Reported with that page as the evidence.
     *
     * THE CARD STILL SAYS ALL OF THEM. This is for the query only — the credit
     * under the cover is what the record says it is, and shortening that would
     * be inventing a different record.
     *
     * THE SPLIT IS DELIBERATELY NARROW, because a false positive here throws
     * away a real name and leaves a search that finds nothing — the exact
     * failure being fixed. So:
     *
     *   - a SPACED slash separates, a bare one does not: "AC/DC" is one act,
     *     and it is the case [StreamingLinks.searchQuery] already has a rule
     *     and a test for;
     *   - a semicolon separates, spaced or not — no act is named with one;
     *   - "feat.", "ft." and "featuring" separate, which is the same shape
     *     [namesOverlap] already accepts as a right-hand qualifier;
     *   - a COMMA does NOT. "Earth, Wind & Fire" is one act.
     *   - an AMPERSAND does NOT. "Nick Cave & the Bad Seeds", "Simon &
     *     Garfunkel", "Hall & Oates" are each one act, and that is the same
     *     conjunction [namesOverlap] drops rather than splits on.
     *   - " with " does NOT. "Sleeping with Sirens".
     *
     * A string with none of those in it comes back exactly as it went in.
     */
    fun primaryArtist(artist: String?): String {
        val whole = artist?.trim().orEmpty()
        if (whole.isEmpty()) return ""
        val first = CREDIT_SEPARATOR.split(whole).firstOrNull()?.trim().orEmpty()
        // A credit that BEGINS with a separator would leave nothing at all,
        // and no name is better answered by an empty search box than by the
        // whole string.
        return first.ifEmpty { whole }
    }

    private val CREDIT_SEPARATOR = Regex(
        """\s+/\s*|\s*/\s+|\s*;\s*|\s+(?:feat\.?|ft\.?|featuring)\s+""",
        RegexOption.IGNORE_CASE
    )
}
