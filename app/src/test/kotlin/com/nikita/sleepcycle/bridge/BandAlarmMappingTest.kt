package com.nikita.sleepcycle.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val DEVICE_ID = 1
private const val OTHER_DEVICE_ID = 2
private val OUR_TITLES = setOf("SCA-A", "SCA-B")

class BandAlarmMappingTest {
    @Test
    fun `maps alarm rows for the given device and drops other devices`() {
        val rows = listOf(
            row(position = 0, enabled = true, hour = 8, minute = 30, title = "SCA-A"),
            row(position = 1, enabled = false, hour = 7, minute = 0, title = null, deviceId = OTHER_DEVICE_ID)
        )

        val slots = mapRawBandAlarmRowsToSlots(rows, DEVICE_ID)

        assertEquals(1, slots.size)
        assertEquals(0, slots[0].position)
        assertEquals("SCA-A", slots[0].title)
    }

    @Test
    fun `a disabled slot with no title is free`() {
        val slots = listOf(
            slot(0, enabled = false, title = null),
            slot(1, enabled = false, title = ""),
            slot(2, enabled = false, title = "Alarm"),
            slot(3, enabled = true, title = null)
        )

        assertEquals(2, countFreeBandAlarmSlots(slots))
    }

    @Test
    fun `free-or-owned counts free slots plus slots already carrying one of our titles, enabled or not`() {
        val slots = listOf(
            slot(0, enabled = false, title = null), // free
            slot(1, enabled = true, hour = 8, minute = 0, title = "SCA-A"), // ours, enabled
            slot(2, enabled = false, hour = 7, minute = 0, title = "SCA-B"), // ours, currently disabled
            slot(3, enabled = false, title = "Alarm") // not free, not ours
        )

        assertEquals(3, countFreeOrOwnedBandAlarmSlots(slots, OUR_TITLES))
    }

    @Test
    fun `real export on the owner's band - four disabled titled slots and one enabled smart alarm - has zero free slots`() {
        val slots = listOf(
            slot(0, enabled = true, hour = 8, minute = 30, title = null, smartWakeup = true),
            slot(1, enabled = false, hour = 7, minute = 0, title = "Alarm"),
            slot(2, enabled = false, hour = 7, minute = 0, title = "Alarm"),
            slot(3, enabled = false, hour = 7, minute = 0, title = "Alarm"),
            slot(4, enabled = false, hour = 7, minute = 0, title = "Alarm")
        )

        assertEquals(0, countFreeBandAlarmSlots(slots))
        assertEquals(0, countFreeOrOwnedBandAlarmSlots(slots, OUR_TITLES))
    }

    @Test
    fun `other enabled alarms excludes ours and includes a hand-set smart alarm`() {
        val slots = listOf(
            slot(0, enabled = true, hour = 8, minute = 30, title = null, smartWakeup = true),
            slot(1, enabled = true, hour = 6, minute = 0, title = "SCA-A"),
            slot(2, enabled = false, hour = 5, minute = 0, title = "SCA-B")
        )

        val others = listOtherEnabledBandAlarms(slots, OUR_TITLES)

        assertEquals(1, others.size)
        assertEquals(0, others[0].position)
    }

    @Test
    fun `describes an other alarm with and without a title`() {
        assertEquals("08:30", describeOtherBandAlarm(slot(0, enabled = true, hour = 8, minute = 30, title = null)))
        assertEquals("08:30 (Alarm)", describeOtherBandAlarm(slot(0, enabled = true, hour = 8, minute = 30, title = "Alarm")))
        assertTrue(describeOtherBandAlarm(slot(0, enabled = true, hour = 8, minute = 30, title = "")).let { !it.contains("(") })
    }

    private fun row(position: Int, enabled: Boolean, hour: Int, minute: Int, title: String?, deviceId: Int = DEVICE_ID) =
        RawBandAlarmRow(deviceId, position, enabled, hour, minute, title, smartWakeup = false, repetition = 0)

    private fun slot(position: Int, enabled: Boolean, hour: Int = 0, minute: Int = 0, title: String?, smartWakeup: Boolean = false) =
        BandAlarmSlot(position, enabled, hour, minute, title, smartWakeup, repetition = 0)
}
