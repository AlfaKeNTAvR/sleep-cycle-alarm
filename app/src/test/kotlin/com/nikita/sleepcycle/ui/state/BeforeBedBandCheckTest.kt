package com.nikita.sleepcycle.ui.state

// File purpose: P2 - the connection test (automatic or tapped) shows on the Before-bed screen as "checking" or
// "failed, with its reason", in the screen's own text, never as a dialog. Goes through buildUiState, the same hop
// NightViewModel uses, so a dropped connectionTest argument would fail here.

import com.nikita.sleepcycle.bridge.BandDataResult
import com.nikita.sleepcycle.night.BedtimeHeartRateCheck
import com.nikita.sleepcycle.night.SetupCheckLine
import com.nikita.sleepcycle.night.buildSuccessReport
import com.nikita.sleepcycle.night.SetupCheckLineSeverity
import com.nikita.sleepcycle.night.SetupCheckReport
import com.nikita.sleepcycle.night.withBluetoothIntentApiHint
import com.nikita.sleepcycle.night.withHeartRateSettingSent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private fun beforeBedWith(
    connectionTest: ConnectionTestState,
    lastSetupCheckPassedAt: java.time.Instant? = null,
    gadgetbridgeInstalled: Boolean = true,
): BeforeBedUiState = buildUiState(
    appSettings = testAppSettings(lastSetupCheckPassedAt = lastSetupCheckPassedAt),
    nightState = null,
    engineView = null,
    now = instant("2026-09-30T23:00"),
    zone = testZone,
    permissionStatus = testPermissionStatus(),
    gadgetbridgeInstalled = gadgetbridgeInstalled,
    screen = Screen.BeforeBed,
    connectionTest = connectionTest,
    nightLogFiles = emptyList(),
    nightLogRatings = emptyMap(),
    confirmingEndNight = false,
    showingMorningReport = false,
    morningReportEndedAt = null,
    pendingFollowUp = null,
    errorMessage = null,
).beforeBed

class BeforeBedBandCheckTest {
    @Test fun `no test run yet shows nothing`() {
        assertEquals(BandCheckStatus.None, beforeBedWith(ConnectionTestState.Idle).bandCheck)
    }

    @Test fun `a running test shows as checking`() {
        assertEquals(BandCheckStatus.Checking, beforeBedWith(ConnectionTestState.Running).bandCheck)
    }

    @Test fun `a passed test shows nothing`() {
        val passed = SetupCheckReport(isReady = true, lines = listOf(SetupCheckLine("Connected. Found 3 sleep segments.", SetupCheckLineSeverity.INFO)))
        assertEquals(BandCheckStatus.None, beforeBedWith(ConnectionTestState.Done(passed)).bandCheck)
    }

    @Test fun `a failed test shows its first action-needed line as the reason`() {
        val failed = SetupCheckReport(
            isReady = false,
            lines = listOf(
                SetupCheckLine("Notifications are enabled.", SetupCheckLineSeverity.INFO),
                SetupCheckLine("In Gadgetbridge, disconnect and reconnect the band, then test again.", SetupCheckLineSeverity.ACTION_NEEDED),
                SetupCheckLine("Notifications are disabled; the alarm will not show its stop screen.", SetupCheckLineSeverity.ACTION_NEEDED),
            ),
        )
        assertEquals(
            BandCheckStatus.Failed("In Gadgetbridge, disconnect and reconnect the band, then test again."),
            beforeBedWith(ConnectionTestState.Done(failed)).bandCheck,
        )
    }

    // PER-130 (2026-11-02): the band synced, but every heart-rate row carried -1. The test fails with its own
    // message naming that fix. Start night is gated on the last pass alone (startNightGate never reads the band
    // check), so an earlier pass still counts, as for any failed test. P6: only with "Require a reading" on.
    @Test fun `with Require a reading, a band that syncs but measures no heart rate fails the test with its own message`() {
        val notMeasuring = notMeasuringReport(BedtimeHeartRateCheck.REQUIRE_READING)

        val beforeBed = beforeBedWith(ConnectionTestState.Done(notMeasuring), lastSetupCheckPassedAt = instant("2026-09-30T20:00"))

        assertEquals(
            BandCheckStatus.Failed(
                "Band connected but not measuring heart rate. In Gadgetbridge band settings, switch \"Enable automatic heart rate measuring\" off and on.",
                notMeasuring = true,
            ),
            beforeBed.bandCheck,
        )
        assertEquals(false, beforeBed.showNoHeartRateReadingNote)
        assertEquals(true, beforeBed.offerOpenGadgetbridge)
    }

    // P6 (owner decision, 2026-11-05): with the default Warn only, the same band passes, with a quiet note.
    @Test fun `with Warn only, a band that syncs but has no heart-rate reading yet passes with the note and no failure`() {
        val beforeBed = beforeBedWith(ConnectionTestState.Done(notMeasuringReport(BedtimeHeartRateCheck.WARN_ONLY)))

        assertEquals(BandCheckStatus.None, beforeBed.bandCheck)
        assertEquals(true, beforeBed.showNoHeartRateReadingNote)
        assertEquals(false, beforeBed.noHeartRateReadingNoteSaysAsked)
        assertEquals(false, beforeBed.offerOpenGadgetbridge)
    }

    // P7: the test asked Gadgetbridge to turn the band's automatic heart rate back on, so the note says so.
    @Test fun `with Warn only, a test that sent the heart-rate setting shows the note that says the app asked`() {
        val beforeBed = beforeBedWith(ConnectionTestState.Done(withHeartRateSettingSent(notMeasuringReport(BedtimeHeartRateCheck.WARN_ONLY))))

        assertEquals(true, beforeBed.showNoHeartRateReadingNote)
        assertEquals(true, beforeBed.noHeartRateReadingNoteSaysAsked)
    }

    // P7: under Require a reading the failure reason itself says the app asked, and keeps the manual fix.
    @Test fun `with Require a reading, a test that sent the heart-rate setting fails with the asked wording`() {
        val beforeBed = beforeBedWith(ConnectionTestState.Done(withHeartRateSettingSent(notMeasuringReport(BedtimeHeartRateCheck.REQUIRE_READING))))

        assertEquals(
            BandCheckStatus.Failed(
                "Band connected but not measuring heart rate. The app asked the band to turn heart rate back on; test again in a few minutes. " +
                    "If it still fails, in Gadgetbridge band settings, switch \"Enable automatic heart rate measuring\" off and on.",
                notMeasuring = true,
            ),
            beforeBed.bandCheck,
        )
    }

    @Test fun `a pass with a real reading shows no note`() {
        val passed = SetupCheckReport(isReady = true, lines = listOf(SetupCheckLine("Connected.", SetupCheckLineSeverity.INFO)))

        assertEquals(false, beforeBedWith(ConnectionTestState.Done(passed)).showNoHeartRateReadingNote)
    }

    private fun notMeasuringReport(heartRateCheck: BedtimeHeartRateCheck): SetupCheckReport = buildSuccessReport(
        BandDataResult.Success(
            segments = emptyList(),
            newestSampleAt = null,
            exportFileModifiedAt = instant("2026-09-30T23:00"),
            newestHeartRateRowAt = instant("2026-09-30T22:59"),
        ),
        now = instant("2026-09-30T23:00"),
        phoneLines = emptyList(),
        heartRateCheck = heartRateCheck,
    )

    // P5 (owner choice, 2026-11-03): the corner said "Band not responding" for a band that synced fine but did
    // not measure; that case now has its own corner status.
    @Test fun `a test failed for not measuring turns the corner to not measuring, not not responding`() {
        assertEquals(
            BandStatus.NOT_MEASURING,
            bandStatusFor(true, BandCheckStatus.Failed("Band connected but not measuring heart rate.", notMeasuring = true), passedTenMinutesAgo, screenNow),
        )
    }

    // P5: the test passed, but Gadgetbridge did not answer a Bluetooth CONNECT, so the app cannot reconnect the
    // band itself. A quiet hint; the pass still counts and the band check shows nothing else.
    @Test fun `a pass with the Intent API looking off shows the hint and nothing else`() {
        val passed = SetupCheckReport(isReady = true, lines = listOf(SetupCheckLine("Connected.", SetupCheckLineSeverity.INFO)))

        val beforeBed = beforeBedWith(ConnectionTestState.Done(withBluetoothIntentApiHint(passed)))

        assertEquals(true, beforeBed.showBluetoothIntentApiHint)
        assertEquals(BandCheckStatus.None, beforeBed.bandCheck)
        assertEquals(false, beforeBed.offerOpenGadgetbridge)
    }

    @Test fun `a pass where Gadgetbridge answered shows no hint`() {
        val passed = SetupCheckReport(isReady = true, lines = listOf(SetupCheckLine("Connected.", SetupCheckLineSeverity.INFO)))

        assertEquals(false, beforeBedWith(ConnectionTestState.Done(passed)).showBluetoothIntentApiHint)
    }

    // Owner request, 2026-10-05: when the band check fails, a button above Start night opens Gadgetbridge to
    // reconnect the band there.
    private val failedReport = SetupCheckReport(
        isReady = false,
        lines = listOf(SetupCheckLine("In Gadgetbridge, disconnect and reconnect the band, then test again.", SetupCheckLineSeverity.ACTION_NEEDED)),
    )

    @Test fun `a failed test offers to open Gadgetbridge`() {
        assertEquals(true, beforeBedWith(ConnectionTestState.Done(failedReport)).offerOpenGadgetbridge)
    }

    @Test fun `no failed test, no Gadgetbridge button`() {
        assertEquals(false, beforeBedWith(ConnectionTestState.Idle).offerOpenGadgetbridge)
        assertEquals(false, beforeBedWith(ConnectionTestState.Running).offerOpenGadgetbridge)
    }

    @Test fun `with Gadgetbridge not installed there is nothing to open`() {
        assertEquals(false, beforeBedWith(ConnectionTestState.Done(failedReport), gadgetbridgeInstalled = false).offerOpenGadgetbridge)
    }

    // The status line in the top corner. Owner-reported on the prototype: it said "Band connected" right above an
    // amber "Band check failed", contradicting it. Tested at bandStatusFor rather than through buildUiState: a
    // fully set-up band needs an export Uri, which android.net.Uri cannot build on the JVM.

    private val screenNow = instant("2026-09-30T23:10")
    private val passedTenMinutesAgo = instant("2026-09-30T23:00")

    @Test fun `a failed test turns the corner status to not responding`() {
        assertEquals(
            BandStatus.NOT_RESPONDING,
            bandStatusFor(
                bandSetUp = true,
                bandCheck = BandCheckStatus.Failed("In Gadgetbridge, disconnect and reconnect the band, then test again."),
                lastPassedAt = passedTenMinutesAgo,
                now = screenNow,
            ),
        )
    }

    @Test fun `a pass ten minutes ago shows the band as connected`() {
        assertEquals(BandStatus.CONNECTED, bandStatusFor(true, BandCheckStatus.None, passedTenMinutesAgo, screenNow))
    }

    // Owner-reported on the phone, 2026-09-30: "Band connected" above "Checking the band connection..." claims
    // the answer before the check has given it.
    @Test fun `a running test shows the band as being checked, not connected`() {
        assertEquals(BandStatus.CHECKING, bandStatusFor(true, BandCheckStatus.Checking, passedTenMinutesAgo, screenNow))
    }

    @Test fun `a band not set up says so even when a test failed`() {
        assertEquals(BandStatus.SETUP_INCOMPLETE, bandStatusFor(false, BandCheckStatus.Failed("x"), passedTenMinutesAgo, screenNow))
    }

    // Owner-reported on the phone, 2026-09-30: the band was disconnected while the corner still said "Band
    // connected" from an earlier pass. The app only knows what its last check found, so an old pass is not shown
    // as connected: past the 1 h re-check age it says "Not synced yet" instead.
    @Test fun `a pass two hours ago is not shown as connected`() {
        assertEquals(BandStatus.NOT_CHECKED_RECENTLY, bandStatusFor(true, BandCheckStatus.None, instant("2026-09-30T21:10"), screenNow))
    }

    @Test fun `no pass at all is not shown as connected`() {
        assertEquals(BandStatus.NOT_CHECKED_RECENTLY, bandStatusFor(true, BandCheckStatus.None, null, screenNow))
    }

    @Test fun `a pass stamped just after the screen last read its clock still shows as connected`() {
        // Same 30 s race as SetupCheckRecencyTest: the pass is real time, the screen's clock is re-read every 30 s.
        assertEquals(BandStatus.CONNECTED, bandStatusFor(true, BandCheckStatus.None, instant("2026-09-30T23:10:20"), screenNow))
    }

    @Test fun `the screen state carries the corner status through`() {
        // The fixture has no export Uri, so the band is not set up: the wiring, not the rule, is what this pins.
        assertEquals(BandStatus.SETUP_INCOMPLETE, beforeBedWith(ConnectionTestState.Running).bandStatus)
    }

    // Owner request, 2026-09-30: "when it shows Band connected show last time checked in ()".
    @Test fun `the time of the last pass is carried for the corner`() {
        assertEquals("22:55", beforeBedWith(ConnectionTestState.Idle, lastSetupCheckPassedAt = instant("2026-09-30T22:55")).bandCheckedAtLabel)
    }

    @Test fun `no pass means no time to show`() {
        assertEquals(null, beforeBedWith(ConnectionTestState.Idle).bandCheckedAtLabel)
    }
}
