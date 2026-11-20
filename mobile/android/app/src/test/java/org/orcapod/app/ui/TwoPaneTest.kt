package org.orcapod.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
    fun nearlySquareFoldableInnerScreenUsesTwoPanes() {
        assertTrue(usesTwoPane(750, 832)) // book-style foldable, inner screen upright
        assertTrue(usesTwoPane(707, 823))
    }

    @Test
    fun tallMediumPortraitKeepsThePhoneLayout() {
        assertFalse(usesTwoPane(673, 841)) // narrow unfolded foldable, portrait
        assertFalse(usesTwoPane(800, 1280)) // tablet, portrait
    }

    @Test
    fun coverScreenKeepsThePhoneLayoutInEveryPosture() {
        assertEquals(PlateLayout.PHONE, plateLayout(411, 960, Posture.FLAT))
        assertEquals(PlateLayout.PHONE, plateLayout(960, 411, Posture.FLAT))
    }

    @Test
    fun tabletopPutsTheControlsBelowTheHinge() {
        assertEquals(PlateLayout.TABLETOP, plateLayout(832, 750, Posture.TABLETOP))
        // Too narrow to lay the controls out in two columns.
        assertEquals(PlateLayout.PHONE, plateLayout(411, 900, Posture.TABLETOP))
    }

    @Test
    fun bookPostureKeepsTwoPanes() {
        assertEquals(PlateLayout.TWO_PANE, plateLayout(673, 841, Posture.BOOK))
        assertEquals(PlateLayout.TWO_PANE, plateLayout(750, 832, Posture.FLAT))
    }

    @Test
    fun bookPaneFillsTheHalfRightOfTheHinge() {
        assertEquals(375.dp, bookPaneWidth(750, 375))
        assertEquals(320.dp, bookPaneWidth(750, null)) // hinge not reported
        assertEquals(320.dp, bookPaneWidth(750, 500)) // right half too narrow for the pane
    }
}
