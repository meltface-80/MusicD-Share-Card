package com.musicd.sharecard.settings

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
    val enabledZones: Set<String> = emptySet()
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
