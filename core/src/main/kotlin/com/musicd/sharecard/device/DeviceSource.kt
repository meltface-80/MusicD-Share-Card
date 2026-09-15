package com.musicd.sharecard.device

import com.musicd.sharecard.source.PlayState
import com.musicd.sharecard.source.Playing
import com.musicd.sharecard.source.Source
import com.musicd.sharecard.source.ZoneRef
import com.musicd.sharecard.source.quality

/**
 * THE PHONE ITSELF, AS A ROOM.
 *
 * Every other source in this app is a NETWORK source — Roon over a socket,
 * Lyrion over JSON-RPC, Sonos and UPnP over SOAP — so the one place none of
 * them can see is the device the app is running on. Spotify or Qobuz CASTING to
 * a speaker has always worked, because then a speaker knows; the same app
 * playing to the phone's own headphones was invisible to all four.
 *
 * [DeviceAudio] was built as a probe to find out what was actually there, and
 * one photograph off a real phone answered it: two apps, each publishing a
 * title, an artist AND an album, one PLAYING and one PAUSED. That is a record,
 * which is all a card needs, so the probe becomes a source.
 *
 * WHAT IS NEW HERE AND WHAT IS NOT:
 *
 *  - **NOT new: reading the sessions.** The shell still does that and still
 *    decides nothing — see [com.musicd.sharecard.device.DeviceAudio] for the
 *    seam. This class added no Android code at all, which is the whole argument
 *    for pushing logic down: every line below is in the module with tests.
 *  - **ONE ZONE, NOT ONE PER APP.** Two apps holding sessions on one phone is
 *    not two rooms — it is one room and a question about which of them to
 *    believe, and that is exactly what [quality] already answers for the
 *    house. Per-app zones would also make every newly installed music app an
 *    unasked-for room, and would need switching on one at a time.
 *  - **NO TIMER, NOTHING LONG-LIVED.** Reading the sessions is one in-process
 *    call with no network on it, so there is nothing to discover, nothing to
 *    cache and nothing to keep warm. [start], [stop] and [refresh] are all
 *    genuinely empty, which is the first source here that can say that.
 *
 * IT IS LAST IN THE SOURCE LIST, AND THAT IS THE TIE-BREAK SPEAKING. Source
 * order decides only when two answers are equally good, and when the house and
 * the phone in your hand are both playing a full record, the house is what this
 * app is for. Naming the zone in the picker still reaches it directly, because
 * a named zone is a lock rather than a preference.
 */
class DeviceSource(
    private val audio: DeviceAudio,
    /**
     * Whether "This device" is switched ON in Settings → Zones.
     *
     * Read ONLY by [notice], and that is the whole reason it exists. Rooms are
     * opt-in, so this zone does nothing until somebody asks for it — and a
     * permission notice shown before they have asked would sit on EVERY Android
     * install for ever, about a feature nobody had requested. That is precisely
     * the bug the Roon notice rule was narrowed after: "Looking for your Roon
     * Core…" under a card that worked, about a product the reader did not own.
     *
     * Switching the room on IS the request. After that, "you have not granted
     * notification access" is the one thing worth saying.
     */
    private val isEnabled: () -> Boolean = { false }
) : Source {

    override val name: String = NAME

    /**
     * The one zone, offered whenever this build could ever answer.
     *
     * DENIED still lists it, on purpose. The room has to be visible in Settings
     * before it can be switched on, and a source that hid itself until the
     * permission was granted would be unreachable: there would be nothing to
     * turn on, and nothing to explain why. UNSUPPORTED lists nothing, because a
     * container has no media sessions and never will — the same split as Roon's
     * ABSENT being silent while AWAITING_APPROVAL speaks.
     */
    override fun zones(): List<ZoneRef> =
        if (read().access == DeviceAudio.Access.UNSUPPORTED) emptyList() else listOf(ZONE)

    override fun nowPlaying(zoneId: String?): Playing? {
        // A named zone that is not ours is not ours to answer about. Sources
        // routes by prefix, but nowPlaying(null) reaches every source, and an
        // id typed by hand reaches whatever claims it.
        if (zoneId != null && zoneId != RAW) return null
        val report = read()
        if (report.access != DeviceAudio.Access.GRANTED) return null
        return best(report.sessions)
    }

    /**
     * WHAT IS ACTUALLY PLAYING, out of everything holding a session.
     *
     * The rule is [quality]'s, deliberately — the same one the ladder uses to
     * choose between rooms, applied here to choose between apps. Playing beats
     * paused, a fuller answer beats a thinner one, and the tie-break is the
     * order Android handed them over, which is its own most-recently-active
     * ranking and the only opinion available.
     *
     * An answer describing NOTHING is dropped rather than returned: a session
     * with a state and no record draws a blank card that looks like the app
     * working, which is worse than the page saying nothing is playing.
     */
    internal fun best(sessions: List<DeviceAudio.Session>): Playing? =
        sessions.map { playing(it) }
            .filter { quality(it) >= 0 }
            .withIndex()
            .minWithOrNull(
                compareBy(
                    { if (it.value.state.isPlaying) 0 else 1 },
                    { -quality(it.value) },
                    { it.index }
                )
            )
            ?.value

    /**
     * Nothing on the LAN, so nothing to allow.
     *
     * An art url from here is a music service's own CDN over TLS, which the
     * proxy already permits on its public-https rule. A session cannot name a
     * player, so this must stay empty: widening the proxy on the say-so of a
     * value another app put in its metadata is the one thing `StreamHosts` was
     * careful NOT to do.
     */
    override fun artHosts(): Collection<String> = emptyList()

    /**
     * WHICH SESSION WON, AND NOTHING THE OTHER SECTION ALREADY SAYS.
     *
     * `/api/debug` prints every session under "Playing on this device" —
     * [DeviceAudio.diagnostics] owns that and it is a wall of facts. What is
     * NOT there is the decision this class makes, which is the part that can be
     * wrong: two apps both hold sessions and one of them ends up on the card.
     * So this names the pick and points at the list rather than repeating it.
     */
    override fun diagnostics(): List<String> {
        val report = read()
        return when (report.access) {
            DeviceAudio.Access.UNSUPPORTED -> listOf("not an Android build, so there is no zone here")
            DeviceAudio.Access.DENIED -> listOf(
                "notification access is not granted, so this room can answer nothing",
                "see \"Playing on this device\" below for how to grant it"
            )
            DeviceAudio.Access.GRANTED -> {
                val chosen = best(report.sessions)
                listOf(
                    "${report.sessions.size} app(s) holding a session" +
                        " — listed under \"Playing on this device\"",
                    if (chosen == null) {
                        "none of them describes a record, so this room says nothing"
                    } else {
                        "chose ${chosen.state}: ${chosen.artist} / ${chosen.album}" +
                            " (art: ${if (chosen.artUrl.isEmpty()) "none this app can fetch" else chosen.artUrl})"
                    }
                )
            }
        }
    }

    /**
     * The grant, and ONLY once somebody has asked for this room.
     *
     * See [isEnabled]. The whole of this rule is that a notice must not outlive
     * the question it answers.
     */
    override fun notice(): String? =
        if (read().access == DeviceAudio.Access.DENIED && isEnabled()) {
            "\"This device\" is switched on, but Android has not given this app " +
                "notification access, so it cannot see what your music apps are playing. " +
                "Settings → Apps → Special app access → Notification access. If that " +
                "toggle refuses, first allow restricted settings for this app."
        } else {
            null
        }

    internal companion object {

        /** Reads as "via Android" on the card, and prefixes ids `android:`. */
        const val NAME = "Android"

        /** One device, one room, one id — see the class comment. */
        const val RAW = "this"

        val ZONE_ID: String = ZoneRef.idFor(NAME, RAW)

        /**
         * "This device" rather than a model name or a phone's own label.
         *
         * Neither is available without more shell code, and neither is better:
         * the person reading the picker is holding the thing it names.
         */
        const val ZONE_NAME = "This device"

        val ZONE = ZoneRef(NAME, ZONE_ID, ZONE_NAME)

        /**
         * ONE SESSION AS A RECORD, WITH THE ART RULE IN IT.
         *
         * **ONLY AN http(s) URL IS CARRIED, AND THAT IS NOT TIMIDITY.** A media
         * session's `ART_URI` is usually a `content://` belonging to the OTHER
         * app — this process holds no grant to read it and no proxy can fetch
         * it — and an attached bitmap has no URL at all. Passing either on
         * would hand [com.musicd.sharecard.api.ArtProxy] something it must
         * refuse, on every card, for ever: a refusal note that is informative
         * once and noise afterwards. Empty means "no cover", which is exactly
         * what it is, and the reason is printed in full under "Playing on this
         * device".
         *
         * WHAT WAS CONSIDERED AND NOT DONE is rebuilding Spotify's CDN url out
         * of its content uri. Two dumps off one phone, minutes apart, carried
         * two different shapes — `…/image/<id>?cdn=i.scdn.co` and
         * `…/spotify%3Aimage%3A<id>` with no cdn at all — so a parser written
         * against the first finds nothing in the second and one written against
         * both has to invent the host. A sleeve resolved from the RECORD, the
         * way Discover already resolves one out of Deezer, is the honest
         * version of that and is its own change.
         */
        internal fun playing(session: DeviceAudio.Session): Playing = Playing(
            source = NAME,
            zoneId = ZONE_ID,
            zoneName = ZONE_NAME,
            album = session.album.trim(),
            artist = session.artist.trim(),
            track = session.title.trim(),
            state = state(session.state),
            artUrl = session.artUri.trim().takeIf {
                it.startsWith("https://") || it.startsWith("http://")
            }.orEmpty()
        )

        /**
         * Android's state name as this app's.
         *
         * BUFFERING COUNTS AS PLAYING. It means the track is loading in order
         * to play, and ranking it below a PAUSED app would hand the card to
         * whatever somebody stopped listening to an hour ago. Anything
         * unrecognised is UNKNOWN rather than STOPPED — `DeviceSessions` writes
         * "state 7" for a code it does not know, and guessing that it means
         * silence is the sort of confident wrong answer this app refuses.
         */
        internal fun state(name: String): PlayState = when (name.trim().uppercase()) {
            "PLAYING", "BUFFERING" -> PlayState.PLAYING
            "PAUSED" -> PlayState.PAUSED
            "STOPPED", "NONE" -> PlayState.STOPPED
            else -> PlayState.UNKNOWN
        }
    }

    private fun read(): DeviceAudio.Report = audio.report()
}
