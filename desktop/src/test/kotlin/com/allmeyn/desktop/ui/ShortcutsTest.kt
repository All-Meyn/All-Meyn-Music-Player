package com.allmeyn.desktop.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutsTest {
    @Test fun keysMapToActions() {
        assertEquals(ShortcutAction.PLAY_PAUSE, shortcutFor(Key.Spacebar, ctrl = false, shift = false, alt = false))
        assertEquals(ShortcutAction.NEXT, shortcutFor(Key.DirectionRight, ctrl = true, shift = false, alt = false))
        assertEquals(ShortcutAction.SEEK_BACK, shortcutFor(Key.DirectionLeft, ctrl = false, shift = true, alt = false))
        assertEquals(ShortcutAction.SEARCH, shortcutFor(Key.F, ctrl = true, shift = false, alt = false))
        assertEquals(ShortcutAction.BACK, shortcutFor(Key.Escape, ctrl = false, shift = false, alt = false))
    }

    @Test fun otherCombinationsAreLeftAlone() {
        assertNull(shortcutFor(Key.DirectionRight, ctrl = false, shift = false, alt = false))  // plain arrows scroll lists
        assertNull(shortcutFor(Key.DirectionRight, ctrl = true, shift = true, alt = false))
        assertNull(shortcutFor(Key.Spacebar, ctrl = false, shift = false, alt = true))
        assertNull(shortcutFor(Key.A, ctrl = true, shift = false, alt = false))
    }

    @Test fun everyActionIsListed() {
        assertEquals(12, ShortcutAction.entries.size)
    }
}
