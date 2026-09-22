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

- **A live night (N1, decided 2026-09-21 - one layout, replacing the separate A, B and C states below):** three centred lines and nothing else. Which alarm is coming, in plain text ("Morning alarm" / "Nap alarm" / "Out-of-bed nudge", or "No alarm armed" when nothing is); its own time as the big amber numeral; and how long until then ("in 20 min", "in 5 h 38"). Same layout going to bed, asleep, awake again and napping - the mode changes the words in those three lines, never their number or arrangement. When the morning alarm has already rung while the band still reads asleep, there is no next alarm, so the numeral is a dash and the line underneath reads "Morning alarm rang at 07:00" instead of a countdown.
- **What A, B and C used to add, all removed by N1, none of it moved elsewhere:** the onset captions ("If you fall asleep by 00:25", "Asleep since 01:12", "Napping, asleep since 07:06"), "7.5 h of sleep", "Deadline HH:mm", "Alarm rings at the deadline", "You slept 1 h 59" as the hero number, "Fall back asleep by 02:45", "No full cycle fits before 08:30", the nap explainer card, the tonight-so-far row, and the engine's own one-sentence reason under the mode header. The owner's verdict on the result: "way too much information". Total slept is on the morning report, which is where he reads it. (An earlier owner request had already removed the "possible wake-ups" timeline these states carried, for the same reason: not worth reading at 3am.)
- **Night finished (the FINISHED amendment):** the deadline passed, or the two nap alarms the whole night allows (pre-wake or after the wake alarm alike) are both spent with no deadline left to fall back on - not band-detected wake by itself any more. Reason text plus an "End night" button; the alarm is not re-armed from here, it already rang. If the two naps are spent but a deadline is still ahead, the screen does not reach this state at all: it stays on the ordinary live-night layout, with the alarm held at the deadline.
- **D. Morning:** total slept, each stretch with from-to times, length and cycles in parentheses (one decimal), note that the night log was saved.

## Alarm ring screen

Shown full-screen, over the lock screen, whichever alarm just rang (the main wake alarm, a nap alarm, or the out-of-bed nudge 15 min after either). One button:

- **Stop:** silences the sound and vibration only. The night keeps running - falling back asleep afterward still counts as a nap.

N1 (decided 2026-09-21) removed the second button, **I'm awake**, which silenced the ring and ended the night on the spot. A mis-tap half asleep therefore cancelled every alarm left in the night, the repeating out-of-bed nudge included. Ending a night now takes the Night screen's own button, behind its own confirmation, which is deliberately harder to reach without being awake.

The out-of-bed nudge is the same screen with different wording ("Time to get up" instead of "Wake up"); Stop behaves identically either way.

## Logs

The night history, not just an export list. Saved nights newest first, each row showing the night's date and time, its size, and a "simulated" tag where it applies.

- **Tapping a row** reopens that night as its own screen (below).
- **The three-dot button on the right** of each row opens a menu: Share (the system share sheet) and Delete (one confirmation, then the file is gone). Chosen over long-press and over swipe-to-delete because it is the only one of the three you can see rather than have to guess.

## Past night

The same retrospective view D shows, for a night that ended earlier: total slept under "You slept", each stretch with from-to times, length and cycles, and below it that night's deadline ("Wake by") and picked length.

Nights logged before the app recorded the per-stretch detail cannot draw that card. They show the real total with one honest line saying how many stretches there were but not when - never invented or zeroed times. A night that was never ended has no summary at all, and says so.
