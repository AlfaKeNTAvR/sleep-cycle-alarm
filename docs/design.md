# Design

Mockup: https://claude.ai/artifact/9caJhL63zpQnX9oeweSbpV
Final screens: "Before bed" plus the "with cycles" row (B2, C2, D2), as edited by the user on 2026-09-17. The first night-screen row is kept only for comparison.

Look: dark, low-glare (used at bedtime). Fraunces for large numbers, Manrope for text, warm amber accent.

## Before bed

- Band connection status.
- "Wake me by" deadline with on/off switch.
- "Sleep up to": 4.5 / 6 / 7.5 / 9 h. Options that cannot fit before the deadline (from now plus an estimated time to fall asleep) are disabled and covered with thin "/" lines at 45 degrees, about 1 mm apart, same stroke weight as the text. No warning text.
- Start night.

There is no phone-backup switch: the phone alarm is the only alarm there is, deadline or not, so there is nothing to opt into.

## Night screen

One screen whose content depends on the moment you look at it. Opening it syncs first.

- **Mode header (A, B, C):** owner request - a small amber block above the hero number naming which alarm is coming ("Morning alarm" / "Nap alarm"), with the engine's own one-sentence reason underneath, so a 3am glance says which alarm and why. Reads "No alarm armed" instead of naming one when nothing is actually armed (e.g. the morning alarm has just fired and the band has not yet reported the owner awake). The reason line is left out in state A's already-rang case below, where the subtitle already explains it.
- **A. Going to bed:** "If you fall asleep by 00:25", big planned alarm, "7.5 h of sleep" (or, once the morning alarm has already rung while the band still reads asleep, "Already rang, waiting for you to wake up" with the real rung time as the hero number instead of a blank dash), a plain "Deadline HH:mm" caption when a deadline is set, Stop night. The "possible wake-ups" timeline this state used to show was removed on owner request - not worth reading at 3am.
- **B. Woke up, more sleep fits:** "You slept 1 h 59" (hours only, no from-to times, no cycles). Header "Fall back asleep by 02:45", alarm estimate. The timeline of wake-time options this state used to show alongside the header was removed for the same reason as state A's.
- **C. Woke up, only a nap fits:** "You slept 3 h 35", "No full cycle fits before 08:30", nap card (alarm rings 20 min after you fall asleep), tonight-so-far total, "I'm up, end night".
- **Night finished (the FINISHED amendment):** the deadline passed, or the two nap alarms the whole night allows (mid-night or after the wake alarm alike) are both spent with no deadline left to fall back on - not band-detected wake by itself any more. Reason text plus an "End night" button; the alarm is not re-armed from here, it already rang. If the two naps are spent but a deadline is still ahead, the screen does not reach this state at all: it shows state B instead, with the alarm held at the deadline.
- **D. Morning:** total slept, each stretch with from-to times, length and cycles in parentheses (one decimal), note that the night log was saved.

## Alarm ring screen

Shown full-screen, over the lock screen, whichever alarm just rang (the main wake alarm, a nap alarm, or the out-of-bed nudge 15 min after either). Two buttons:

- **Stop:** silences the sound and vibration only. The night keeps running - falling back asleep afterward still counts as a nap.
- **I'm awake:** silences it and ends the night right there, landing on the morning report (D above).

The out-of-bed nudge is the same screen with different wording ("Time to get up" instead of "Wake up"); both buttons behave identically either way.

## Logs

The night history, not just an export list. Saved nights newest first, each row showing the night's date and time, its size, and a "simulated" tag where it applies.

- **Tapping a row** reopens that night as its own screen (below).
- **The three-dot button on the right** of each row opens a menu: Share (the system share sheet) and Delete (one confirmation, then the file is gone). Chosen over long-press and over swipe-to-delete because it is the only one of the three you can see rather than have to guess.

## Past night

The same retrospective view D shows, for a night that ended earlier: total slept under "You slept", each stretch with from-to times, length and cycles, and below it that night's deadline ("Wake by") and picked length.

Nights logged before the app recorded the per-stretch detail cannot draw that card. They show the real total with one honest line saying how many stretches there were but not when - never invented or zeroed times. A night that was never ended has no summary at all, and says so.
