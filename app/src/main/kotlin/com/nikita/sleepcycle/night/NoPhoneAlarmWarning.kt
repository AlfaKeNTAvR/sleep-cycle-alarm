package com.nikita.sleepcycle.night

// File purpose: item 4 - a night can run with no phone alarm at all (deadline off AND phone backup off): if
// the band fails, nothing wakes the owner. Following the rules is fine (it is the owner's choice), but nothing
// told them so on Before bed, night 1. This one pure decision drives the amber warning line on Before bed, the
// Night screen's status area, and the night service notification, so the three surfaces can never disagree.

/**
 * True when tonight leaves no phone alarm at all: the deadline switch is off AND the phone-backup switch is
 * off. Does not block starting the night - that stays the owner's choice - only whether the warning shows.
 */
fun noPhoneAlarmTonight(deadlineEnabled: Boolean, phoneBackupEnabled: Boolean): Boolean =
    !deadlineEnabled && !phoneBackupEnabled
