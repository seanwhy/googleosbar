package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Sym

/**
 * Pure rules for the Headphones and output item: which of Android's audio outputs are listed, what kind each is, which one
 * the sound goes to, and what the bar calls it. The Android side is in [OutputItem]. The types are the platform's own
 * numbers (`AudioDeviceInfo.TYPE_*`), written out here so these rules need no Android.
 */
object OutputRules {
    enum class Kind { SPEAKER, WIRED, BLUETOOTH, USB, DISPLAY, OTHER }

    /** One output as Android lists it: its kind and its own name (a headset's, a monitor's; the speaker's is the device's model). */
    data class Out(val kind: Kind, val name: String)

    /** The most characters of a device's name the bar shows. */
    const val NAME_MAX = 16

    // AudioDeviceInfo's types, by the platform's names.
    private const val BUILTIN_EARPIECE = 1
    private const val BUILTIN_SPEAKER = 2
    private const val WIRED_HEADSET = 3
    private const val WIRED_HEADPHONES = 4
    private const val BLUETOOTH_SCO = 7
    private const val BLUETOOTH_A2DP = 8
    private const val HDMI = 9
    private const val HDMI_ARC = 10
    private const val USB_DEVICE = 11
    private const val USB_ACCESSORY = 12
    private const val TELEPHONY = 18
    private const val USB_HEADSET = 22
    private const val HEARING_AID = 23
    private const val BUILTIN_SPEAKER_SAFE = 24
    private const val REMOTE_SUBMIX = 25
    private const val BLE_HEADSET = 26
    private const val BLE_SPEAKER = 27
    private const val HDMI_EARC = 29
    private const val BLE_BROADCAST = 30
    /** API 36: a newer Android sends it, an older one never does. */
    private const val BLE_HEARING_AID = 33

    /**
     * Whether an output of [type] is one sound can be sent to by choice: not the earpiece or a call's line, not a Bluetooth
     * headset's call path (its music path is listed), not the speaker's own safe twin and not a recording's submix.
     */
    fun listed(type: Int): Boolean = type !in setOf(BUILTIN_EARPIECE, TELEPHONY, BLUETOOTH_SCO, BUILTIN_SPEAKER_SAFE, REMOTE_SUBMIX)

    fun kind(type: Int): Kind = when (type) {
        BUILTIN_SPEAKER -> Kind.SPEAKER
        WIRED_HEADSET, WIRED_HEADPHONES -> Kind.WIRED
        BLUETOOTH_A2DP, BLE_HEADSET, BLE_SPEAKER, HEARING_AID, BLE_BROADCAST, BLE_HEARING_AID -> Kind.BLUETOOTH
        USB_DEVICE, USB_HEADSET, USB_ACCESSORY -> Kind.USB
        HDMI, HDMI_ARC, HDMI_EARC -> Kind.DISPLAY
        else -> Kind.OTHER
    }

    /** The order Android itself prefers for media when nobody chose: Bluetooth, then a cable, USB, a display, the speaker. */
    private val preferred = listOf(Kind.BLUETOOTH, Kind.WIRED, Kind.USB, Kind.DISPLAY, Kind.SPEAKER, Kind.OTHER)

    /**
     * Where the sound goes: of [routed], the outputs Android says media goes to now (a choice in its output switcher
     * included), the first by [preferred] that is listed; where it says nothing usable, the first of [outs] by that order.
     */
    fun current(outs: List<Out>, routed: List<Out> = emptyList()): Out? =
        first(routed.filter { it in outs }) ?: first(outs)

    private fun first(outs: List<Out>): Out? = preferred.firstNotNullOfOrNull { k -> outs.firstOrNull { it.kind == k } }

    fun icon(kind: Kind): String = when (kind) {
        Kind.BLUETOOTH, Kind.WIRED -> Sym.HEADPHONES
        Kind.USB -> Sym.USB
        Kind.DISPLAY -> Sym.TV
        Kind.SPEAKER, Kind.OTHER -> Sym.SPEAKER
    }

    /** Anything but the built-in speaker is worth showing. */
    fun active(out: Out?): Boolean = out != null && out.kind != Kind.SPEAKER

    /**
     * What the bar and the menu call [out]: its own name on one line, cut to [NAME_MAX]; the speaker (whose name is only
     * the device's model) and a device without a name by the [word] for their kind.
     */
    fun name(out: Out, word: (Kind) -> String): String {
        val own = TextRules.oneLine(out.name, NAME_MAX * 4)
        return if (out.kind == Kind.SPEAKER || own.isBlank()) word(out.kind) else TextRules.short(own, NAME_MAX)
    }

    /** The outputs the debug hook stages (`output stage NAME`), each with the speaker as a device has it; null for a name it doesn't know. */
    fun staged(name: String?): List<Out>? {
        val speaker = Out(Kind.SPEAKER, "")
        return when (name) {
            "headphones" -> listOf(speaker, Out(Kind.BLUETOOTH, "Headset"))
            "usb" -> listOf(speaker, Out(Kind.USB, "USB headset"))
            "two" -> listOf(speaker, Out(Kind.DISPLAY, "Monitor"), Out(Kind.WIRED, ""))
            "speaker" -> listOf(speaker)
            else -> null
        }
    }
}
