# `docs/` — index

Reference documentation for the Zodiac vehicle software. Start at the
repository [`README.md`](../README.md) for what the system is.

The same material is published as a browsable site at
<https://pureagave.github.io/zodiac-ecliptic/> (source in [`site/`](site/)). The
site is hand-written from these Markdown files, so where the two disagree, the
Markdown is the reference and the site is the one to fix.

## Core

| Doc | What it answers |
|---|---|
| [`../README.md`](../README.md) | What Zodiac is, in brief — the portfolio front page |
| [`OVERVIEW.md`](OVERVIEW.md) | The detailed snapshot: what runs where, what is and is not built |
| [`../FIELD-NOTES.md`](../FIELD-NOTES.md) | What the hardware taught us, measured |
| [`../ARCHITECTURE.md`](../ARCHITECTURE.md) | How all three codebases are layered, and where the seams are |
| [`BUILD.md`](BUILD.md) | How to build, test and deploy each part; the exact CI gates |
| [`PROTOCOLS.md`](PROTOCOLS.md) | The wire formats between the parts — NMEA sentences, `ZTHREAT`/`ZCOVER`, `$ZNAV` and `$ZVER` |
| [`DEVICES.md`](DEVICES.md) | Which device plays which role, and the per-device gotchas |

## Operations

| Doc | What it answers |
|---|---|
| [`KIOSK.md`](KIOSK.md) | Locking a fleet tablet to the cockpit (device-owner provisioning; note: Fires can't be kiosked) |
| [`FAILURE-MODES.md`](FAILURE-MODES.md) | Graceful degradation — what still works when the beacon / Jetson / WiFi / a tablet dies |
| [`MOUNTING.md`](MOUNTING.md) | Vibration-proof tablet mounts — the RAM X-Grip shopping list + fiberglass fastening method |

## Elsewhere in the repository

| Location | Contents |
|---|---|
| [`../jetson/`](../jetson/) | Edge-box docs — `README`, `DEPLOY`, `HARDWARE`, `MOVING-HEAD`, `DETECTOR`, `TRAINING`, `DECK` |
| [`../protocol/`](../protocol/) | The owner-neutral `ZTHREAT` golden corpus and its rationale |
| [`../design/`](../design/) | UI concept work, burn-in design, performance backlog, surround-HUD design |

## Conventions

- `README.md` and `OVERVIEW.md` record *what is true now*. They are not a changelog.
- Everything in `docs/` is reference material that should match the code. Where a
  doc and the code disagree, the code wins — fix the doc.
- The dated decision log, task list and audits live in the private development
  repository and are not part of this tree.
- Where something cannot be verified from the repository (it needs hardware, a
  device, or the owner's knowledge), the doc says so rather than guessing.
