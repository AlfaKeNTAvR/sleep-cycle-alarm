package com.nikita.sleepcycle.ui.screens.night

// File purpose: PER-130 - each Night screen band warning names its own fix. A band that syncs but measures no
// heart rate is not fixed by a reconnect (Gadgetbridge sends the measuring setting only when it is toggled), so
// its warning must say to toggle "Enable automatic heart rate measuring", not to reconnect.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.ui.state.BandDataWarning
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BandDataWarningTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the not-measuring warning names the heart rate toggle`() {
        assertEquals(
            "Band not measuring heart rate - alarm is only an estimate. In Gadgetbridge, switch \"Enable automatic heart rate measuring\" off and on.",
            context.getString(bandDataWarningText(BandDataWarning.NOT_MEASURING_HEART_RATE))
        )
    }

    @Test
    fun `the not-syncing warning keeps the reconnect fix`() {
        assertEquals(
            "Band not syncing - alarm is only an estimate. Disconnect and reconnect the band in Gadgetbridge.",
            context.getString(bandDataWarningText(BandDataWarning.NOT_SYNCING))
        )
    }
}
