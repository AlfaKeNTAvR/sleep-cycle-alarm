# Autonomous decisions, 2026-10-02 11:19: pause media on sleep, faster checks while falling asleep

## Decisions
- **Pause mechanism**: a "pause" media-button press via `AudioManager.dispatchMediaKeyEvent` (the same one open-source sleep timers use). No new permission. Uses PAUSE, never PLAY_PAUSE, so it can never start playback.
- **Only when something plays**: skipped when `AudioManager.isMusicActive` is false. 1.5 s after the press the app checks again and logs `media_pause_on_sleep` with `result` = `paused`, `still_playing` (the app ignored the key, e.g. maybe YouTube) or `nothing_playing`.
- **When it fires**: on the tick where the band's state changes INTO asleep (from not-yet-asleep or awake). So it also pauses again if you wake mid-night, play something, and fall back asleep. Never after the morning alarm has rung.
- **No settings toggle**: always on. Settings is a navigation-only screen by design, and Before bed is already busy. Add a toggle later if a night needs audio to keep going.
- **Faster checks**: every 5 min instead of 15 while the band has seen no sleep yet, for the first 60 min after Start night (`fallingAsleepWatch`, engine config). The upper end of your 45-60 min, since the band itself lags 5-24 min.
- **Also applies to debug simulated nights**: a simulated falling asleep will pause real media on the phone. Left as is, useful for testing.

## Not verified
- On-device behaviour: phone was disconnected, so not installed or tried. Needs a real test with your audiobook app and YouTube; the night log line says whether it worked.
- Bluetooth headphones: media keys normally still route to the playing app; untested.

## Open questions
- Should faster checks also run after a mid-night awakening (to pause sooner when falling back asleep)? Not done: you asked for the first 45-60 min.
