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
- **Night finished (the FINISHED amendment):** the deadline passed, or the two nap alarms the whole night allows (pre-wake or after the wake alarm alike) are both spent with no deadline left to fall back on - not band-detected wake by itself any more. Reason text plus an "End night" button; the alarm is not re-armed from here, it already rang. If the two naps are spent but a deadline is still ahead, the screen does not reach this state at all: it stays on the ordinary live-night layout, with the alarm held at the deadline. **N2 (owner-reported, 2026-09-21): this state is only ever shown when nothing at all is armed.** A finished plan whose out-of-bed nudge is still pending (L2 keeps the chain alive past the deadline) is drawn as the ordinary live-night layout naming that nudge, with "I'm up, end night" underneath - saying "Night finished" over a still-armed alarm read as the deadline having cancelled it, which it never does.
- **Band not syncing (P1, owner decision 2026-09-30, "warn, still arm"):** while the last sync failed or returned only stale data, a red banner under the sync line reads "Band not syncing - alarm is only an estimate. Disconnect and reconnect the band in Gadgetbridge." The night keeps running and the estimated alarm stays armed as a backup; the banner disappears with the first sync that succeeds. On 2026-09-30 every sync timed out all night and only the small status line said so; Gadgetbridge showed the band connected (with no battery level), and reconnecting it was the fix.
- **After an alarm rings (P3, owner spec, 2026-09-30).** Once the ring is stopped (or stops itself), the screen shows the out-of-bed nudge in the N1 layout - "Out-of-bed nudge", its time big, "in 10 min" - and at the bottom two stacked buttons: a filled amber **Nap for 20 min**, then the outlined **I'm up, end night**. Pressing Nap swaps the nudge for a nap alarm 20 min from the press (never later than a wake-by deadline still ahead) and the screen then reads "Nap alarm", its time, "in 20 min", with only **I'm up, end night** underneath. When that nap rings and is stopped, the nudge and the Nap button come back - as many times as he likes, no cap. Nothing here watches the band: he says when he naps. The alarm block stays centred on the screen exactly where it sits on every other Night screen; the buttons never push it up. Pressing Nap never ends the night; only **I'm up, end night** does.
- **D. Morning:** total slept, each stretch with from-to times, length and cycles in parentheses (one decimal), note that the night log was saved. A night with no stretch at all says "No sleep recorded tonight" - unless its syncs were failing when it ended (P1), when it says "No band data reached the app: the band's syncs were failing", because the app never saw the band's data and cannot claim there was no sleep.
- **D. Morning, rating (owner spec, 2026-10-02):** under the stretches, a "How did you sleep?" card with three faces, Good / Okay / Bad in green #7BC89A, amber #F0B75A and red #E0806E. Optional: Done without a tap skips it; tapping another face changes it. It replaces the "night log saved" note, which comes back when the rating is switched off or the night was started before the rating existed.

## Settings (owner spec, 2026-10-02, option A)

Section titles, no descriptions. Every change is saved at once.

- **After the alarm:** Out-of-bed nudge, 5, 10 or 15 min (default 10), measured from the ring's end. Nap length, 10 to 30 min in 5 min steps (default 20), used by both the Nap button and the engine's naps between cycles. Both apply from the next Start night.
- **Bedtime audio:** Pause media when asleep (on/off), Fade media volume (on/off), Starting volume 10 to 50% in 5% steps (default 25%), Ending volume 5 to 45% in 5% steps (default 5%; at or above the starting volume the fade just holds). The fade's 10 min hold and 5 min step stay hidden. The fade starts about 1 s after media starts playing following Start night (the night service watches playback and the media volume, and also checks on every sync), and again each time you wake in the night with media playing (owner request, 2026-10-02). From Start night to the morning alarm the fade owns the volume: the fade only ever lowers the volume: one you turn up yourself is pulled back to the fade's schedule on the next sync, one you turn down below it stays where you put it, falling asleep pauses the media and parks the volume at the starting volume (or where the fade already got to, if lower; turning it up while parked is pulled back to the starting volume), and every awakening starts again at exactly the starting volume, even when that means raising it (Start night too). Once the morning alarm has rung there is no fading: the next sync puts your own volume back (End night does too, for a night that ends before its alarm).
- **Sleep rating:** Rate the night (on/off), Ask again later (on/off), Ask at (default 15:00).
- **More:** Setup, Debug.

A row whose switch above it is off stays visible, dimmed and inert. The fade needs the pause: with "Pause media when asleep" off, the fade rows dim and the fade switch reads off.

## Later rating notification

At the "Ask at" time on the day the night ended, a notification: "Still feel the same about last night?" with "This morning you said Good." - or "How do you feel about last night?" when the morning rating was skipped. Three buttons, Good / Okay / Bad, rate without opening the app. Asked once per night; not asked when the night ended after that time or the time already passed.

## Alarm ring screen

Shown full-screen, over the lock screen, whichever alarm just rang (the main wake alarm, a nap alarm - the owner's own "Nap for 20 min" included - or the out-of-bed nudge). One button:

- **Stop:** silences the sound and vibration only. The night keeps running, and the out-of-bed nudge rings 10 min after the Stop (P3; 10 min after the ring's own 9 min auto-stop if nobody touches it). Napping is chosen on the Night screen, not here: N1 keeps this screen to the one button. (Before P3: "falling back asleep afterward still counts as a nap", detected by the band - gone.)

N1 (decided 2026-09-21) removed the second button, **I'm awake**, which silenced the ring and ended the night on the spot. A mis-tap half asleep therefore cancelled every alarm left in the night, the repeating out-of-bed nudge included. Ending a night now takes the Night screen's own button, behind its own confirmation, which is deliberately harder to reach without being awake.

The out-of-bed nudge is the same screen with different wording ("Time to get up" instead of "Wake up"); Stop behaves identically either way.

## Logs

The night history, not just an export list. Saved nights newest first by when they started, each row showing the night's start date and time (owner request, 2026-10-02: not when its log was last written), its size, and a "simulated" tag where it applies.

- **Tapping a row** reopens that night as its own screen (below).
- **The three-dot button on the right** of each row opens a menu: Share (the system share sheet) and Delete (one confirmation, then the file is gone). Chosen over long-press and over swipe-to-delete because it is the only one of the three you can see rather than have to guess.

Rating chips (owner spec, 2026-10-02): after the size, one small colored chip per rating the night has - the one after End night first, then the later one. Nights from before the rating have none.

## Past night

A "Your rating" card (owner spec, 2026-10-02) between the report and the Wake by card: two rows, "After End night, 07:52" and "Later in the day, 15:02", each three pill buttons that change that rating. A night from before the rating has no card.

The same retrospective view D shows, for a night that ended earlier: total slept under "You slept", each stretch with from-to times, length and cycles, and below it that night's deadline ("Wake by") and picked length.

Nights logged before the app recorded the per-stretch detail cannot draw that card. They show the real total with one honest line saying how many stretches there were but not when - never invented or zeroed times. A night that was never ended has no summary at all, and says so. A night that ended with no band data (P1, flagged in its `night_end`) says so with D's same no-band-data wording; a log written before that flag existed keeps "No sleep recorded tonight".
