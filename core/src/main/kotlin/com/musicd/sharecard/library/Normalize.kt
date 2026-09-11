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

    /** Lowercase, strip accents, collapse everything else to single spaces. */
    fun text(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val folded = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        return COMBINING.replace(folded, "")
            .replace(NON_ALNUM, " ")
            .trim()
    }

    /**
     * Sort key: every record shop files "The Wall" under W, not T. Leading
     * articles are dropped for MATCHING only; the displayed title is untouched.
     */
    fun sortKey(normalized: String): String = LEADING_ARTICLE.replace(normalized, "")
}
