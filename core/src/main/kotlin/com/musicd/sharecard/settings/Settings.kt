package com.musicd.sharecard.settings

import com.musicd.sharecard.meta.Reviews

/**
 * What the person running this has turned on and off.
 *
 * BOTH SETS HOLD WHAT IS SWITCHED ON, AND BOTH DEFAULT TO NOTHING. This app
 * shows what it has been asked to show and nothing else — a room appears in
 * the picker because somebody chose it, a streaming service is linked because
 * somebody wanted it.
 *
 * SERVICES USED TO DEFAULT ON, on the reasoning that a service added in a
 * later version should appear by itself. Reported as a bug on the first run
 * ("the services show enabled already — said disabled"), and the report is
 * right: an app whose rooms are opt-in and whose services are opt-out is one
 * rule wearing two faces. A service added in a later version now arrives
 * switched off like everything else, which is the duller and more predictable
 * half of that trade.
 *
 * The older file wrote `disabledServices` and is simply not read: everything
 * it named is off now anyway, which is the new default, so the worst an
 * upgrade costs is switching a service back on.
 *
 * IT IS SERVER-SIDE, unlike the preferred-service tick, which lives in
 * `localStorage` because it changes nothing but a link. These change what the
 * server DOES — a disabled service is not looked up and a disabled zone is not
 * asked — so the answer has to be the same for every device in the house, and
 * has to survive a restart.
 */
data class Settings(
    /** Streaming services switched ON. Everything not named here is off. */
    val enabledServices: Set<String> = emptySet(),
    /** Zones switched ON. Everything not named here is off. */
    val enabledZones: Set<String> = emptySet(),
    /**
     * Review sources, or NULL meaning "nobody has chosen yet".
     *
     * THE NULL IS THE POINT, and it is why this is not a third set of the same
     * shape as the two above. Those two default to nothing; these do not — the
     * album sources are what the card has always drawn, and defaulting them
     * off would empty every card in the house to make a settings screen
     * consistent. So the absence of a choice has to be distinguishable from
     * the choice of nothing, which an empty set cannot do.
     *
     * Once anything is touched the whole set is written out literally, so the
     * file always says what is on rather than what was left alone.
     */
    val enabledReviews: Set<String>? = null
) {

    fun serviceEnabled(id: String): Boolean = id in enabledServices

    fun zoneEnabled(id: String): Boolean = id in enabledZones

    /** Nothing has been chosen yet, so the app has nothing it may show. */
    val noZonesChosen: Boolean get() = enabledZones.isEmpty()

    fun withService(id: String, enabled: Boolean): Settings = copy(
        enabledServices = if (enabled) enabledServices + id else enabledServices - id
    )

    fun withZone(id: String, enabled: Boolean): Settings = copy(
        enabledZones = if (enabled) enabledZones + id else enabledZones - id
    )

    fun reviewEnabled(id: String): Boolean =
        enabledReviews?.contains(id) ?: Reviews.onByDefault(id)

    /**
     * Materialises the defaults before changing one of them, so that switching
     * Pitchfork off does not silently switch Wikipedia off with it.
     */
    fun withReview(id: String, enabled: Boolean): Settings {
        val current = enabledReviews ?: Reviews.ALL.filter { it.onByDefault }.mapTo(
            LinkedHashSet()
        ) { it.id }
        return copy(enabledReviews = if (enabled) current + id else current - id)
    }
}

/**
 * Where [Settings] is kept.
 *
 * THE FOURTH THING THAT WRITES TO DISK, and this repo's rules have a sentence
 * about that: a new route that writes goes behind `Access.mayConfigure` in the
 * same change. It does — see `CardApi.settingsWrite`. Nothing on the network
 * reads this file back except through that route, and it holds no credential:
 * the worst it carries is the name of a room.
 */
interface SettingsStore {

    fun read(): Settings

    fun write(settings: Settings)

    companion object {
        /** Remembers nothing, for tests and for a host with no storage. */
        fun inMemory(initial: Settings = Settings()): SettingsStore =
            object : SettingsStore {
                @Volatile
                private var held = initial
                override fun read(): Settings = held
                override fun write(settings: Settings) { held = settings }
            }
    }
}
