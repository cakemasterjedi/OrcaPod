package org.orcapod.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPaneTest {
    @Test
    fun phonesKeepThePhoneLayout() {
        assertFalse(usesTwoPane(411, 891)) // portrait
        assertFalse(usesTwoPane(891, 411)) // landscape: wide but too short for the pane
    }

    @Test
    fun tabletsAndFoldablesUseTwoPanes() {
        assertTrue(usesTwoPane(1280, 800)) // tablet, landscape
        assertTrue(usesTwoPane(841, 701)) // unfolded foldable, landscape
        assertTrue(usesTwoPane(900, 1200)) // large tablet, portrait
    }

    @Test
    fun mediumPortraitKeepsThePhoneLayout() {
        assertFalse(usesTwoPane(673, 841)) // unfolded foldable, portrait
        assertFalse(usesTwoPane(800, 1280)) // tablet, portrait
    }
}
