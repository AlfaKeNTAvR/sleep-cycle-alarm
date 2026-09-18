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
    fun `maps a null smart-wakeup interval to null, and a real one through unchanged`() {
        val rows = listOf(
            RawBandAlarmRow(DEVICE_ID, position = 0, enabled = false, hour = 5, minute = 29, title = null, smartWakeup = true, repetition = 0, smartWakeupWindowMinutes = null),
            RawBandAlarmRow(DEVICE_ID, position = 1, enabled = false, hour = 3, minute = 17, title = null, smartWakeup = true, repetition = 0, smartWakeupWindowMinutes = 60)
        )

        val slots = mapRawBandAlarmRowsToSlots(rows, DEVICE_ID)

        assertEquals(null, slots[0].smartWakeupWindowMinutes)
        assertEquals(60, slots[1].smartWakeupWindowMinutes)
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
    fun `usable counts free slots plus slots holding one of our ENABLED alarms, never a disabled one of ours`() {
        val slots = listOf(
            slot(0, enabled = false, title = null), // free
            slot(1, enabled = true, hour = 8, minute = 0, title = "SCA-A"), // ours, enabled: reclaimable by dismiss-then-set
            slot(2, enabled = false, hour = 7, minute = 0, title = "SCA-B"), // ours, but disabled AND titled: nothing can use or free it
            slot(3, enabled = false, title = "Alarm") // not free, not ours
        )

        assertEquals(2, countUsableBandAlarmSlots(slots, OUR_TITLES))
    }

    @Test
    fun `a band whose only our-titled slot is disabled has nothing usable at all`() {
        // B1: this is the owner's band after a night whose alarm rang - the slot keeps our title, switched
        // off. Gadgetbridge's picker skips a titled slot and nothing in the protocol ever clears it, so
        // counting it as usable made every SET fail silently while the setup check said the night could run.
        val slots = listOf(
            slot(0, enabled = false, hour = 5, minute = 29, title = "Smart", smartWakeup = true),
            slot(1, enabled = true, hour = 7, minute = 0, title = "Work"),
            slot(2, enabled = false, hour = 8, minute = 0, title = "SCA-A")
        )

        assertEquals(0, countUsableBandAlarmSlots(slots, OUR_TITLES))
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
        assertEquals(0, countUsableBandAlarmSlots(slots, OUR_TITLES))
    }

    @Test
    fun `a disabled untitled smart-wakeup slot is never free, even though it looks exactly like a free slot`() {
        // The owner's real slot 0: enabled=0, title='', SMART_WAKEUP=1, window 60 - DeviceAlarmReceiver.updateAlarm
        // never clears SMART_WAKEUP, so a slot like this must never be handed out as if it were a normal free slot.
        val slots = listOf(
            slot(0, enabled = false, hour = 5, minute = 29, title = null, smartWakeup = true, smartWakeupWindowMinutes = 60),
            slot(1, enabled = false, hour = 3, minute = 17, title = null)
        )

        assertEquals(1, countFreeBandAlarmSlots(slots))
        assertEquals(1, countUsableBandAlarmSlots(slots, OUR_TITLES))
    }

    @Test
    fun `a smart-wakeup slot carrying one of our titles is never counted as usable either`() {
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = "SCA-A", smartWakeup = true, smartWakeupWindowMinutes = 60))

        assertEquals(0, countUsableBandAlarmSlots(slots, OUR_TITLES))
    }

    @Test
    fun `listPoachableSmartWakeupBandAlarmSlots finds only smart slots that are still disabled and untitled`() {
        val poachable = slot(0, enabled = false, hour = 5, minute = 29, title = null, smartWakeup = true, smartWakeupWindowMinutes = 60)
        val parked = slot(1, enabled = false, hour = 0, minute = 0, title = "Smart", smartWakeup = true, smartWakeupWindowMinutes = 60)
        val enabledSmart = slot(2, enabled = true, hour = 8, minute = 30, title = null, smartWakeup = true)
        val normalFree = slot(3, enabled = false, hour = 0, minute = 0, title = null)

        val result = listPoachableSmartWakeupBandAlarmSlots(listOf(poachable, parked, enabledSmart, normalFree))

        assertEquals(listOf(poachable), result)
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

    private fun slot(
        position: Int,
        enabled: Boolean,
        hour: Int = 0,
        minute: Int = 0,
        title: String?,
        smartWakeup: Boolean = false,
        smartWakeupWindowMinutes: Int? = null
    ) = BandAlarmSlot(position, enabled, hour, minute, title, smartWakeup, repetition = 0, smartWakeupWindowMinutes = smartWakeupWindowMinutes)
}
