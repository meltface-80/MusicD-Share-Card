package com.musicd.sharecard.meta

import java.text.Normalizer

/**
 * One folding rule, used everywhere two titles have to be compared.
 *
 * Ported from MusicD Remote Lite, where it exists for the same reason: an album
 * title arrives from a speaker, a review, a search result and a slug, and no two
 * of them punctuate it the same way. Folding in one place means they all fold
 * the same way.
 */
object Normalize {

    private val COMBINING = Regex("[\\u0300-\\u036f]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /**
     * Lower-cased, accents folded off, every run of anything else a space.
     *
     * "Björk" and "Bjork" match; so do "good kid, m.A.A.d city" and
     * "good-kid-maad-city" once the spaces are dropped too — see
     * [QobuzAlbum.pick], which is why the punctuation is not merely normalised
     * but thrown away.
     */
    fun text(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val folded = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        return COMBINING.replace(folded, "")
            .replace(NON_ALNUM, " ")
            .trim()
    }
}
