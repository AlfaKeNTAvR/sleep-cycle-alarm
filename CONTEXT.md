# Sleep Cycle Alarm

A personal Android alarm that reads a sleep band through Gadgetbridge and wakes the sleeper at the end of a sleep cycle, then follows the morning through naps, the out-of-bed nudge and a sleep rating.

## Language

### Nights

**Night**:
One tracked sleep, from Start night to End night, with its own log.
_Avoid_: session, run

**No-alarm night**:
A night with no morning alarm: it tracks sleep, controls bedtime media and asks "Are you up?" once the sleeper is awake in the morning.
_Avoid_: track-only night, open night, sleep tracker mode

**Are you up? ask**:
The question a No-alarm night puts on the lock screen and the Night screen once the band sees a long morning awakening; it stays until answered with I'm up or Still sleeping.
_Avoid_: wake check, morning prompt

**Deadline**:
The optional latest time the sleeper must be woken ("Wake me by"); nothing may ring after it.
_Avoid_: hard alarm, cutoff

### After the alarm

**Morning alarm**:
The night's main wake alarm, timed to a cycle end.
_Avoid_: wake alarm (in user-facing text), main alarm

**Nap**:
A fixed-length extra sleep the sleeper asks for after an alarm (napMinutes, 20 min by default).
_Avoid_: snooze

**One more cycle**:
An extra sleep of one fixed cycle (90 min) from the tap, asked for after an alarm, offered only when it ends before any deadline.
_Avoid_: extra cycle, long nap

**Out-of-bed nudge**:
The alarm that rings a set delay after the sleeper says they are up, in case they fell back asleep.
_Avoid_: follow-up alarm, re-alarm
