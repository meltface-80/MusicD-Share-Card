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
}
