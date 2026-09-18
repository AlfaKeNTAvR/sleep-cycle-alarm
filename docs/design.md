# Design

Mockup: https://claude.ai/artifact/9caJhL63zpQnX9oeweSbpV
Final screens: "Before bed" plus the "with cycles" row (B2, C2, D2), as edited by the user on 2026-09-17. The first night-screen row is kept only for comparison.

Look: dark, low-glare (used at bedtime). Fraunces for large numbers, Manrope for text, warm amber accent.

## Before bed

- Band connection status.
- "Wake me by" deadline with on/off switch.
- "Sleep up to": 4.5 / 6 / 7.5 / 9 h. Options that cannot fit before the deadline (from now plus an estimated time to fall asleep) are disabled and covered with thin "/" lines at 45 degrees, about 1 mm apart, same stroke weight as the text. No warning text.
- Phone backup switch (only without a deadline).
- Start night.

## Night screen

One screen whose content depends on the moment you look at it. Opening it syncs first.

- **A. Going to bed:** "If you fall asleep by 00:25", big planned alarm, "7.5 h of sleep", timeline of possible wake-ups (time + hours), phone safety alarm, Stop night.
- **B. Woke up, more sleep fits:** "You slept 1 h 59" (hours only, no from-to times, no cycles). Timeline "Fall back asleep by 02:45" with options, band alarm estimate, phone safety alarm.
- **C. Woke up, only a nap fits:** "You slept 3 h 35", "No full cycle fits before 08:30", nap card (band wakes you 20 min after you fall asleep), tonight-so-far total, phone safety alarm, "I'm up, end night".
- **D. Morning:** total slept, each stretch with from-to times, length and cycles in parentheses (one decimal), note that the night log was saved.

## Logs

The night history, not just an export list. Saved nights newest first, each row showing the night's date and time, its size, and a "simulated" tag where it applies.

- **Tapping a row** reopens that night as its own screen (below).
- **The three-dot button on the right** of each row opens a menu: Share (the system share sheet) and Delete (one confirmation, then the file is gone). Chosen over long-press and over swipe-to-delete because it is the only one of the three you can see rather than have to guess.

## Past night

The same retrospective view D shows, for a night that ended earlier: total slept under "You slept", each stretch with from-to times, length and cycles, and below it that night's deadline ("Wake by") and picked length.

Nights logged before the app recorded the per-stretch detail cannot draw that card. They show the real total with one honest line saying how many stretches there were but not when - never invented or zeroed times. A night that was never ended has no summary at all, and says so.
