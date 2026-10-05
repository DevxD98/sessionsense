package com.sessionsense.session_sense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphSupportTest {
    private val asked = mutableListOf<String>()
    private fun resolves(result: Boolean): (String) -> Boolean = { asked += it; result }

    @Test fun nothingPhoneWithTheGlyphScreen() {
        assertTrue(GlyphSupport.available("Nothing", resolves(true)))
        assertTrue(GlyphSupport.available("NOTHING", resolves(true)))
        assertEquals(GlyphSupport.SETTINGS_ACTION, asked.first())
    }

    @Test fun nothingPhoneWithoutTheGlyphScreen() = assertFalse(GlyphSupport.available("Nothing", resolves(false))) // e.g. a CMF phone

    @Test fun otherPhonesNeverSeeIt() {
        assertFalse(GlyphSupport.available("Google", resolves(true)))
        assertFalse(GlyphSupport.available("CMF", resolves(true)))
        assertTrue("other manufacturers shouldn't even query the package manager", asked.isEmpty())
    }
}
