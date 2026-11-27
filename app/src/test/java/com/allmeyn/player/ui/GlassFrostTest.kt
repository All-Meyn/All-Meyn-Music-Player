package com.allmeyn.player.ui

import com.wexpa.liquidglass.material3.GlassMaterialDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassFrostTest {
    // The default frost is the library's own glass: nothing changes until the slider moves.
    @Test fun theDefaultIsTheLibrarysLook() {
        assertEquals(GlassMaterialDefaults.control.style(false), AppGlass.control(GlassFrost.DEFAULT).style(false))
        assertEquals(GlassMaterialDefaults.control.style(true), AppGlass.control(GlassFrost.DEFAULT).style(true))
        assertEquals(GlassMaterialDefaults.panel.style(false), AppGlass.panel(GlassFrost.DEFAULT).style(false))
        assertEquals(GlassMaterialDefaults.panel.style(true), AppGlass.panel(GlassFrost.DEFAULT).style(true))
        assertEquals(GlassMaterialDefaults.control.light(false), AppGlass.control(GlassFrost.DEFAULT).light(false))
        assertEquals(GlassMaterialDefaults.control, AppGlass.control(GlassFrost.DEFAULT))
    }

    @Test fun moreFrostBlursMoreAndIsMoreOpaque() {
        val clear = AppGlass.panel(0).style(true)!!
        val frosted = AppGlass.panel(100).style(true)!!
        assertTrue(frosted.backdropSigma > clear.backdropSigma)
        assertTrue(frosted.tint.alpha > clear.tint.alpha)
        assertTrue(AppGlass.control(100).style(false)!!.backdropSigma.value > 0f)
        assertNotEquals(AppGlass.control(0), AppGlass.control(100))
    }

    // Clear keeps enough blur and tint to read text on a panel (0 % was unreadable at 2 dp).
    @Test fun clearStaysReadable() {
        val clear = AppGlass.panel(0).style(true)!!
        assertTrue(clear.backdropSigma.value >= 5f)
        assertTrue(clear.backdropSigma < AppGlass.panel(GlassFrost.DEFAULT).style(true)!!.backdropSigma)
    }

    // Even steps: every 5 % step changes the panel blur and the tint.
    @Test fun everyStepChangesSomething() {
        for (f in 0 until 100 step 5) {
            val a = AppGlass.panel(f).style(true)!!
            val b = AppGlass.panel(f + 5).style(true)!!
            assertTrue("$f", b.backdropSigma > a.backdropSigma && b.tint.alpha >= a.tint.alpha)
        }
    }

    @Test fun labels() {
        assertEquals("Clearest", GlassFrost.label(0))
        assertEquals("Standard", GlassFrost.label(35))
        assertEquals("55 %", GlassFrost.label(55))
        assertEquals("Frosted", GlassFrost.label(100))
    }
}
