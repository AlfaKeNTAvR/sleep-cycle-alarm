# sleep-cycle-alarm

An Android smart alarm for the Honor Band 5. It wakes you at the end of a 90-minute sleep cycle, counted from the moment you actually fall asleep, and never later than your deadline.

The app does not talk to the band directly. It drives [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), which stays installed and unmodified, through Gadgetbridge's Intent API.

## Status

Design finished, nothing built yet (2026-09-17). Waiting on the mid-night sync test before implementation starts.

## Docs

- [docs/decisions.md](docs/decisions.md): every decision made so far, with the reason.
- [docs/plan.md](docs/plan.md): implementation phases and open questions.
- [docs/findings.md](docs/findings.md): what we measured and proved on the real band and phone.
- [docs/design.md](docs/design.md): the screens.

## Hardware

- Band: Honor Band 5 ("Honor Band 5", MAC `AA:BB:CC:DD:EE:FF`).
- Phone: Google Pixel 10.
- Bridge: Gadgetbridge 0.94.0 from F-Droid.
