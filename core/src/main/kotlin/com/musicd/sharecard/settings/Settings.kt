package com.musicd.sharecard.settings

/**
 * What the person running this has turned on and off.
 *
 * THE TWO SETS ARE OPPOSITE WAYS ROUND, AND THAT IS THE WHOLE DESIGN. Each
 * one stores the EXCEPTION to its default, so an empty file means "the
 * defaults" and a thing this version has never heard of gets the default
 * rather than whatever the file happens not to mention:
 *
 *  - SERVICES default ON. A streaming service added in a later version
 *    appears by itself, which is what somebody who never opened Settings
 *    expects. So the set holds the ones switched OFF.
 *  - ZONES default OFF. A television powered on next week arrives silent and
 *    stays out of the picker until it is asked for, which is the entire point
 *    of the zones screen. So the set holds the ones switched ON.
 *
 * Stored the other way round, each default would invert the moment the file
 * was written: every service would need re-enabling after an upgrade, and
 * every new device would appear unasked.
 *
 * IT IS SERVER-SIDE, unlike the preferred-service tick, which lives in
 * `localStorage` because it changes nothing but a link. These change what the
 * server DOES — a disabled service is not looked up and a disabled zone is not
 * asked — so the answer has to be the same for every device in the house, and
 * has to survive a restart.
 */
data class Settings(
    /** Streaming services switched OFF. Everything not named here is on. */
    val disabledServices: Set<String> = emptySet(),
    /** Zones switched ON. Everything not named here is off. */
    val enabledZones: Set<String> = emptySet()
) {

    fun serviceEnabled(id: String): Boolean = id !in disabledServices

    fun zoneEnabled(id: String): Boolean = id in enabledZones

    /** Nothing has been chosen yet, so the app has nothing it may show. */
    val noZonesChosen: Boolean get() = enabledZones.isEmpty()

    fun withService(id: String, enabled: Boolean): Settings = copy(
        disabledServices = if (enabled) disabledServices - id else disabledServices + id
    )

    fun withZone(id: String, enabled: Boolean): Settings = copy(
        enabledZones = if (enabled) enabledZones + id else enabledZones - id
    )
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
