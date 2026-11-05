# Autonomous decisions, 2026-11-05

Three changes, built in autonomous mode. Full reasoning: docs/decisions.md entries P6, S1, Q2.

## Diagnosis: bedtime heart-rate friction
- Since P4 (Nov 3), Before bed needs a real heart-rate value from the last 30 min.
- Band data from Nov 3 to Nov 5 shows full per-minute heart rate through each night. Outside nights, only a couple of empty (-1) rows appear around each sync. So at bedtime there is usually no recent real value, even with automatic heart rate on. Before P4 the empty rows counted as fresh, which is why it never used to be a problem.

## Decisions
- **Bedtime check setting (P6):** a switch, "Require a heart-rate reading", off by default (Warn only). Chosen as the default because the strict check is the friction the owner reported.
- **Night warning waits 30 min (P6):** the not-measuring banner and notification now wait until the night is 30 min old, in both modes, so Warn only does not open every night with a false warning.
- **Heavy head (S1):** one symptom offered in both the morning and later lists, not a second entry. Morning row: Still sleepy, Headache, Heavy head.
- **Change the alarm mid-night (Q2):** tap the alarm on the Night screen, same pickers as Before bed. Allowed until the morning alarm rings. The old alarm stays armed until the new one replaces it. A wake-by time already passed tonight is refused. When no whole cycle fits, the alarm rings at the wake-by time (same as Start night does).

## Open questions
1. Under Require a reading, should the night warning stay immediate instead of waiting 30 min?
2. Should switching to Require a reading cancel a pass already made under Warn only? Now it does not.
3. Switch, or two labelled options ("Warn only" / "Require a reading")?
4. When no whole cycle fits before a new wake-by time, accept (ring at wake-by) or refuse? Now accepted.
5. Should a mid-night change also update Before bed's remembered settings for the next night? Now it does not.
6. Should the Night screen hint that the alarm time is tappable? Now no visible hint.
7. Keep editing locked once the morning alarm has rung?
8. When the last cycle that fits has already ended, ring at now + 2 min (current) or at the wake-by time?
9. On the phone: six-tile morning dialog fit, Before bed note, editor layout.
