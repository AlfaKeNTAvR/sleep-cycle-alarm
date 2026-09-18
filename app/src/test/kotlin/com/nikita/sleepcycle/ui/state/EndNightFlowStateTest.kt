package com.nikita.sleepcycle.ui.state

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EndNightFlowStateTest {
    @Test
    fun `a first tap on the end-night button is honored`() {
        assertTrue(canRequestEndNight(EndNightFlowState(confirmingEndNight = false, endingNight = false)))
    }

    @Test
    fun `a second tap on the end-night button while the dialog is already open is ignored`() {
        assertFalse(canRequestEndNight(EndNightFlowState(confirmingEndNight = true, endingNight = false)))
    }

    @Test
    fun `a tap on the end-night button while ending is already in flight is ignored - the dialog cannot reopen`() {
        assertFalse(canRequestEndNight(EndNightFlowState(confirmingEndNight = false, endingNight = true)))
    }

    @Test
    fun `confirming end night is honored once`() {
        assertTrue(canConfirmEndNight(EndNightFlowState(confirmingEndNight = true, endingNight = false)))
    }

    @Test
    fun `a second confirm while ending is already in flight is ignored`() {
        assertFalse(canConfirmEndNight(EndNightFlowState(confirmingEndNight = true, endingNight = true)))
    }
}
