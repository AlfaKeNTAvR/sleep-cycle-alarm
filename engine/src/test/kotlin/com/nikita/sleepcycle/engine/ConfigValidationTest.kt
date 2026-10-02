package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/** The out-of-bed nudge range the Settings screen offers must be a config the engine accepts. */
class ConfigValidationTest {

    private fun planWith(config: EngineConfig): Boolean =
        isSleepLengthAvailable(5, instant("2026-10-02T23:00"), null, config)

    @ParameterizedTest
    @ValueSource(longs = [5, 9, 10, 15])
    fun `every nudge the Settings screen offers is accepted, even one shorter than the ring auto-stop`(minutes: Long) {
        planWith(EngineConfig(outOfBedDelay = Duration.ofMinutes(minutes)))
    }

    @Test
    fun `a nudge no longer than the pre-nudge check lead is still rejected`() {
        val config = EngineConfig(outOfBedDelay = Duration.ofMinutes(2), preNudgeCheckLead = Duration.ofMinutes(2))
        assertThrows(IllegalArgumentException::class.java) { planWith(config) }
    }
}
