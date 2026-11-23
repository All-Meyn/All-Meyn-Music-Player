package com.allmeyn.desktop.ui

import androidx.compose.ui.input.key.Key
import java.util.prefs.Preferences

/** What a keyboard shortcut does. */
enum class ShortcutAction(val keys: String, val label: String) {
    PLAY_PAUSE("Space", "Play / pause"),
    NEXT("Ctrl + →", "Next song"),
    PREVIOUS("Ctrl + ←", "Previous song (or back to the start)"),
    SEEK_FORWARD("Shift + →", "Forward 10 s"),
    SEEK_BACK("Shift + ←", "Back 10 s"),
    VOLUME_UP("Ctrl + ↑", "Volume up"),
    VOLUME_DOWN("Ctrl + ↓", "Volume down"),
    MUTE("Ctrl + M", "Mute / unmute"),
    SHUFFLE("Ctrl + S", "Shuffle on / off"),
    REPEAT("Ctrl + R", "Repeat off / all / one"),
    SEARCH("Ctrl + F", "Search"),
    BACK("Esc", "Back (or clear the search)"),
}

/**
 * The shortcut for a key press, or null. They reach the app only when nothing focused uses the key
 * first: while typing in a field, Space and the arrows type and move there instead.
 */
fun shortcutFor(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): ShortcutAction? {
    if (alt) return null
    return when {
        !ctrl && !shift && key == Key.Spacebar -> ShortcutAction.PLAY_PAUSE
        !ctrl && !shift && key == Key.Escape -> ShortcutAction.BACK
        ctrl && !shift -> when (key) {
            Key.DirectionRight -> ShortcutAction.NEXT
            Key.DirectionLeft -> ShortcutAction.PREVIOUS
            Key.DirectionUp -> ShortcutAction.VOLUME_UP
            Key.DirectionDown -> ShortcutAction.VOLUME_DOWN
            Key.M -> ShortcutAction.MUTE
            Key.S -> ShortcutAction.SHUFFLE
            Key.R -> ShortcutAction.REPEAT
            Key.F -> ShortcutAction.SEARCH
            else -> null
        }
        shift && !ctrl -> when (key) {
            Key.DirectionRight -> ShortcutAction.SEEK_FORWARD
            Key.DirectionLeft -> ShortcutAction.SEEK_BACK
            else -> null
        }
        else -> null
    }
}

/** The window passes key presses here; App sets [handler] (true = done). */
class ShortcutTarget {
    var handler: ((ShortcutAction) -> Boolean)? = null
}

/** Media keys (play/pause, next, previous, stop) control All-Meyn, also in the background. Default on. */
object MediaKeysSetting {
    private val prefs: Preferences = Preferences.userRoot().node("com/allmeyn/desktop")
    fun enabled(): Boolean = prefs.getBoolean("mediaKeys", true)
    fun setEnabled(on: Boolean) = prefs.putBoolean("mediaKeys", on)
}
