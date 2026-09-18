package com.nikita.sleepcycle.ui.state

// File purpose: pure decision logic for the end-night button/dialog flow (night-flow-fix item 1). endNight
// itself takes ~3 s (a verification sync runs inside the transaction lock, per app-spec.md), and on both real
// nights the "Stop night"/"I'm up, end night" button stayed enabled with no indication of work happening, so
// the owner tapped it twice. Kept separate from NightViewModel (Android glue) so it is unit testable.

/** The end-night flow's own state, as far as these decisions need it. */
data class EndNightFlowState(val confirmingEndNight: Boolean, val endingNight: Boolean)

/**
 * Whether tapping the end-night button (which opens the confirmation dialog) should be honored right now.
 * False while already confirming (the dialog is already open) or already ending - the confirmation dialog
 * must never be re-openable while a previous confirm is still being carried out.
 */
fun canRequestEndNight(state: EndNightFlowState): Boolean = !state.confirmingEndNight && !state.endingNight

/**
 * Whether tapping "confirm" inside the dialog should actually start ending the night. False once ending is
 * already in flight, so a second confirm (e.g. a duplicate tap landing before the dialog has visibly closed)
 * is ignored rather than starting a second `endNight` call.
 */
fun canConfirmEndNight(state: EndNightFlowState): Boolean = !state.endingNight
