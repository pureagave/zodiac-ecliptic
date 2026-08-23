# BEACON GPS test/simulation mode — implementation spec

**Status:** spec, ready to implement (2026-08-22). Planning doc — if the code and
this doc disagree after implementation, the code wins; fix the doc and note it in
`SYNC.md`.

**Scope guard:** this feature touches **only `:beacon` files** (plus
`docs/PROTOCOLS.md` / `SYNC.md` doc updates). The working tree has uncommitted
`:app` changes from an unrelated in-flight feature — leave them alone. Run the
gate **unscoped** anyway so `:app` stays covered.

## Goal

An opt-in **GPS simulation mode** for the beacon: when armed (over adb), the
beacon broadcasts a *chosen* synthetic fix — stationary at a preset/arbitrary
point, or dead-reckoned along a heading at a speed — over the normal fleet bus
(`239.7.7.10:10110` + subnet broadcast), **instead of** the real GNSS fix. The
whole fleet (including GNSS-less Fire tablets on `NET`) follows it. This lets
map ego, WHERE card, street crossings, art approach, nav share, etc. be tested
off-playa without carrying six tablets around a parking lot.

This deliberately fabricates a fix — normally forbidden here ("an absent sensor
emits nothing rather than a fabricated 0.0"). It is allowed only because it is:

1. **Opt-in** — armed exclusively via an adb-only broadcast, guarded by
   `android.permission.DUMP` (shell holds it; ordinary apps cannot).
2. **Loud** — a red "GPS SIMULATED" banner on `BeaconActivity`, a `⚠ GPS
   SIMULATED` first line in the status readout, and **GGA fix-quality 8** (the
   NMEA 0183 "Simulation mode" quality indicator) on every synthetic fix, so the
   wire itself is marked in-band (see "Marking the wire" below).
3. **Non-persistent** — armed state lives *only* in process memory. It is never
   written to `SharedPreferences`; `start()` and `stop()` both clear it. A
   power-cycled beacon comes back on **real** GNSS, always.

When off, behavior is **byte-identical to today**: raw GNSS forwarded verbatim,
same synthesized channels, same cadences.

## Non-negotiables (pinned by tests)

- Armed **overrides** real GNSS (raw sentences are *suppressed*, not merged) —
  deterministic testing, no interleaving of real and fake positions.
- Off ⇒ exactly today's behavior. The verbatim-forwarding contract
  (`TelemetryBroadcaster.forward`, `TelemetryBroadcaster.kt:623-626`), the
  watchdog, the 250 ms tick loop and its integer divisors, and `Locale.US`
  formatting are untouched.
- Never emit `VTG`, `HDG`, `HDM` (rejected by design — `docs/PROTOCOLS.md`
  §2.3, `NmeaParser.parseHeadingDeg` doc at `app/.../nmea/NmeaParser.kt:40-72`).
- Arming writes **nothing** persistent. The only persistence surface the beacon
  has is the `zodiac_beacon` prefs file; a test pins that arm/disarm leaves it
  untouched.
- The real `$ZODO` odometer **never** accumulates simulated meters —
  `totalMeters` is a persisted lifetime odometer (`PREF_TOTAL_METERS`,
  `TelemetryBroadcaster.kt:294`, persisted at `:890-892`); inflating it with
  fake travel would corrupt real data permanently. The real `LocationListener`
  stays wired while armed; `$ZODO` keeps reporting real movement only.

## How the beacon works today (measured, file:line)

- `TelemetryBroadcaster` (object singleton, `TelemetryBroadcaster.kt:251`):
  - Raw GNSS: `OnNmeaMessageListener` → `forward(msg)` (`:484`, `:623-626`) →
    `send()` verbatim + `updateFixHealth()` (sniffs GGA quality/sats, `:901-907`).
  - Tick loop (`launchTickLoop`, `:534-564`): 250 ms (`HDT_INTERVAL_MS`, `:265`);
    every tick `$GPHDT` (compass, only if a heading exists — `hdtSentenceOrNull`,
    `:80`) + `$ZTLM`; divisors `ENV_EVERY_TICKS=8`, `ODO_EVERY_TICKS=8`,
    `HEALTH_EVERY_TICKS=20`, `STATUS_EVERY_TICKS=4` (`:276-279`).
  - `speedKph()` (`:772`) reads `lastLocation.speed` → `$ZTLM` field 3.
  - Deadman watchdog (`launchDeadmanWatchdog`, `:582-598`) — untouched by this
    feature; the sim rides the existing tick loop.
  - `stop()` (`:600-619`) clears listeners/scope/socket; `start()` (`:428`)
    resets all volatile state and bails if `_running.value`.
- `Nmea.kt` — sentence builders + XOR checksum (`checksum`, `Nmea.kt:140-144`),
  every format pinned `Locale.US`. The sim reuses these; **no hand-formatted
  sentences anywhere else**.
- `TelemetryService` — foreground service; `onStartCommand`
  (`TelemetryService.kt:51-77`) is idempotent-ish (broadcaster start guarded).
- `BeaconActivity` — plain-View status screen collecting
  `TelemetryBroadcaster.status` + `isRunning` (`BeaconActivity.kt:41-48`).
- `BootReceiver` — auto-start default **true** (`BootReceiver.kt:59`); reads
  only `PREF_AUTO_START`. **No change needed for the sim's auto-off** — the sim
  state is memory-only, so a boot start is a real-GNSS start by construction.
- Consumer contract (`app/.../nmea/NmeaParser.kt`, `docs/PROTOCOLS.md` §2):
  - GGA: ≥10 fields (`:160,273`); lat/lon `DDMM.MMMM` + hemisphere; **fix
    quality field 6 must be > 0** (`:161`) — quality **8 passes**; HDOP field 8
    → accuracy ×5 m.
  - RMC: ≥9 fields, status field 2 == `A` (`:176`); speed **knots** field 7
    (×1.852 → kph); course field 8 normalized [0,360).
  - HDT: heading field 1, any talker (dispatch on last 3 chars).

## Design

### 1. Pure sim model — new file `GpsSim.kt` (`beacon/src/main/java/org/pureagave/zodiac/beacon/GpsSim.kt`)

No Android imports. Everything deterministic, clock injected.

```kotlin
/** BRC presets, coordinates restated from :app (the modules share no code —
 *  FLEET-1 decision 10). Sources cited per value; re-check when the city moves:
 *  app/.../core/geo/GoldenSpike.kt (Y2026), core/ops/Camp.kt, core/ops/NavTarget.kt. */
internal enum class SimPreset(val lat: Double, val lon: Double) {
    MAN(40.783247448000054, -119.20788409599999),     // GoldenSpike.Y2026
    HOME(40.7832474, -119.2225761),                   // Camp.HOME_CAMP (7:30 & E)
    TEMPLE(40.78809942300006, -119.201499636),        // NavTarget.TEMPLE 2026
}

/** What the operator asked for. Immutable. */
internal data class SimSpec(
    val latDeg: Double,       // start position (validated ±90 / ±180)
    val lonDeg: Double,
    val headingDeg: Double,   // normalized [0, 360)
    val speedKph: Double,     // 0..MAX_SIM_SPEED_KPH (160, mirrors the app's speed bound)
    val label: String,        // preset name or "MANUAL" — for the readout/banner
)

/** A running simulation: a spec + when it started. Pure position function. */
internal class GpsSimulator(val spec: SimSpec, val startedAtMs: Long) {
    /** Dead-reckoned position after (nowMs - startedAtMs) at spec.heading/speed. */
    fun positionAt(nowMs: Long): Pair<Double, Double>
    /** New simulator continuing from the CURRENT dead-reckoned position with a
     *  new heading/speed — the "turn" primitive; a timed sequence of turns is a
     *  path. */
    fun turn(nowMs: Long, headingDeg: Double, speedKph: Double): GpsSimulator
}
```

Dead-reckoning math (equirectangular, matching the app's `PlayaProjection`
approach; cos at the *start* latitude — error negligible over test-scale
distances, document that):

```
d      = speedKph / 3.6 * elapsedSec              // meters
dLat   = toDegrees((d * cos(hdgRad)) / R)         // R = 6_371_000.0 m
dLon   = toDegrees((d * sin(hdgRad)) / (R * cos(latRad)))
```

Position is a **pure function of elapsed time** (no per-tick accumulation) — no
drift, fully deterministic, trivially testable.

Also in `GpsSim.kt`, the pure command parser (the receiver is a thin shell over
this):

```kotlin
/** Parse + validate an arm/adjust command. All inputs are the raw string extras
 *  (dot-decimal; String.toDoubleOrNull is locale-independent). Returns the new
 *  simulator, or null = reject the whole command (no partial application). */
internal fun simCommand(
    preset: String?, lat: String?, lon: String?,
    hdg: String?, spd: String?,
    current: GpsSimulator?, nowMs: Long,
): GpsSimulator?
```

Validation rules (reject ⇒ null ⇒ `Log.w`, state unchanged):
- `preset` (case-insensitive) XOR (`lat` AND `lon`) — both given, or lat
  without lon, is ambiguous ⇒ reject.
- Neither position given: valid **only if `current != null`** ⇒
  `current.turn(nowMs, hdg, spd)` from the current dead-reckoned position.
  Not armed + no position ⇒ reject.
- `lat` ∈ [−90, 90], `lon` ∈ [−180, 180], finite. `hdg` finite, normalized
  [0, 360), default `0`. `spd` finite, `0 ≤ spd ≤ 160`, default `0`.
- Any unparsable field ⇒ reject (never silently default a *present* extra).

Constants (named, in `GpsSim.kt` or companion): `MAX_SIM_SPEED_KPH = 160.0`,
`SIM_FIX_EVERY_TICKS = 4` (1 Hz on the 250 ms loop — integer divisor, matches
real GNSS `GPS_MIN_INTERVAL_MS = 1000`), `SIM_FIX_QUALITY = 8`,
`SIM_SATELLITES = 12`, `SIM_HDOP = 0.8`, `SIM_ALTITUDE_M = 1191.0` (playa
elevation, labeled as a chosen plausible value, not a measurement).

### 2. Sentence synthesis — additions to `Nmea.kt`

Two new builders + a coordinate formatter, same shape as the existing ones
(`Locale.US`, `checksum(body)`, `\r\n`). Talker `GP` (parser dispatches on the
last 3 chars, any talker works — `NmeaParser.kt:33`). Time/date injected as
`epochMillis` (UTC), never read from a clock inside the builder.

```
$GPGGA,<hhmmss.ss>,<DDMM.MMMM>,<N|S>,<DDDMM.MMMM>,<E|W>,8,12,0.8,1191.0,M,0.0,M,,*CC
$GPRMC,<hhmmss.ss>,A,<DDMM.MMMM>,<N|S>,<DDDMM.MMMM>,<E|W>,<knots %.1f>,<cog %.1f>,<ddMMyy>,,,A*CC
```

- Lat: `%02d%07.4f` (deg·100 + minutes, 4 decimal minutes ≈ 0.19 m resolution);
  lon `%03d%07.4f`. Hemisphere from sign.
- GGA field count 15 ≥ 10 ✓; fix quality field 6 = `8`; sats field 7 = `12`;
  HDOP field 8 = `0.8` → the tablet computes accuracy 4 m (`HDOP_TO_METERS`).
- RMC field count 13 ≥ 9 ✓; status `A`; speed field 7 in **knots** =
  `speedKph / 1.852`; course field 8 = the sim heading (COG == heading under
  straight-line dead reckoning; emitted even at speed 0 — deterministic, and
  the app prefers HDT for heading anyway).
- Heading: reuse the existing `Nmea.hdt()` unchanged.

**Marking the wire — GGA quality 8.** NMEA 0183 defines GGA quality indicator
`8` = *Simulation mode* (a datasheet fact, flagged as such). Measured against
the shipped consumer: `NmeaParser.parseGga` accepts any quality `> 0`
(`NmeaParser.kt:161`), so tablets ingest the fix normally, while `$ZBCN` (whose
fixQ comes from GGA sniffing) reports `q8` fleet-wide — an in-band, standard,
zero-format-change marker any NMEA-literate consumer can detect. An explicit
"SIMULATED" chip on the tablets would need `:app` work — out of scope here
(open question 3).

Worked example for the pinned test (MAN preset): lat `40.783247448` → 40°
46.99485′ → `4046.9948,N`; lon `−119.20788409599999` → 119° 12.47305′ →
`11912.4730,W`.

### 3. Broadcaster wiring — `TelemetryBroadcaster.kt`

State: one nullable flow, following the `gpsHandleOverride` internal-seam
precedent (`TelemetryBroadcaster.kt:71`) so the object's function count stays
inside the detekt `TooManyFunctions` budget (currently 21 member functions;
threshold 22):

```kotlin
/** Armed simulation, or null. MEMORY-ONLY by design: never persisted, cleared
 *  by start() and stop() — a rebooted beacon always comes back on real GNSS. */
internal val simState = MutableStateFlow<GpsSimulator?>(null)
```

(If the implementer prefers an `internal fun setSimulation(...)` wrapper and
detekt stays green, fine — but do **not** raise the detekt threshold for this;
extract per the `openTransmitSocket`/`sendToTargets` top-level precedent
instead, `TelemetryBroadcaster.kt:86-132`.)

Changes, each one small and guarded on `simState.value`:

1. **`forward(nmea)`** (`:623-626`) — first line: `if (simState.value != null)
   return` — raw GNSS suppressed *before* `send()` and *before*
   `updateFixHealth()` (a real GGA must not overwrite the sim's q8/12 stamps).
   This is the override, and the *only* touch on the verbatim path.
2. **Tick loop** (`:534-564`) — inside `body`, branch on `simState.value`:
   - armed: every tick emit `Nmea.hdt(sim heading)` **instead of** the compass
     HDT (deterministic heading, and works on a bench phone with no
     rotation-vector sensor); on `tick % SIM_FIX_EVERY_TICKS == 0L` compute
     `positionAt(now)` and emit GGA + RMC via the new builders, then stamp
     `fixQuality = SIM_FIX_QUALITY; satellites = SIM_SATELLITES; lastGgaAtMs =
     SystemClock.elapsedRealtime()` so `$ZBCN` and the readout stay coherent.
   - off: exactly today's `hdtSentenceOrNull(headingDeg)` path.
   Keep the branch logic in a **top-level pure function** so it's directly
   testable and stays out of the function budget:
   `internal fun simTickSentences(sim: GpsSimulator, tick: Long, epochMs: Long): List<String>`
   (returns `[HDT]` or `[HDT, GGA, RMC]`); the loop just sends what it returns.
3. **`speedKph()`** (`:772`) — `simState.value?.spec?.speedKph ?:` today's
   expression → `$ZTLM` field 3 is coherent with the sim.
4. **`statusText()`** (`:637-670`) — while armed: first line
   `⚠ GPS SIMULATED — <label> hdg <h>° spd <s> kph` (stacks with the tick-dead
   banner exactly as `tickHealthLine` does), and the GPS line shows
   `GPS    SIM %.5f, %.5f` from `positionAt(now)`. Build the banner text in a
   top-level `internal fun simStatusLine(sim, nowMs): String` (pure, testable).
5. **`start()`** (`:428`) — add `simState.value = null` to the reset block
   (`:436-448`). The singleton outlives a service restart within a living
   process; start must never inherit an armed sim. (`start()` early-returns if
   already running (`:432`) — a `START_STICKY` re-delivery while armed keeps
   the sim, which is correct: same process, deliberate state.)
6. **`stop()`** (`:600-619`) — add `simState.value = null`. STOP button and
   service death both disarm.

Everything else — `$ZENV`, `$ZAUD`, `$ZSHK`, `$ZODO`, `$ZBCN` cadence, ZTLM
pitch/roll (real IMU), the odometer, the watchdog, `maintainTransport`,
`send()` — untouched. Sim sentences flow through the same `send()` → both
targets, counted in the same `sentences` tally.

### 4. Control surface — new file `SimGpsReceiver.kt` + `TelemetryService` registration

**Mechanism: a context-registered `BroadcastReceiver`, registered by
`TelemetryService` while it runs, requiring senders to hold
`android.permission.DUMP`.**

Why this exact shape:
- **Context-registered, not manifest-declared:** (a) manifest receivers don't
  get implicit (`-a`-only) broadcasts on API 26+, forcing an ugly `-n
  component` in every adb command; (b) registration scoped to the service's
  lifetime means an arm command against a stopped beacon is inert by
  construction — no latent state.
- **`android.permission.DUMP` as the guard:** the adb shell uid holds DUMP
  (it's what makes `dumpsys` work); ordinary installed apps cannot get it
  (signature|privileged|development). So the receiver is exported yet only
  shell/system can deliver to it — the established adb-only-test-hook pattern.
  No custom permission, no secret token to leak into shell history. Residual
  exposure: another *adb-authorized* actor — which is the operator, on a
  provisioning phone on the vehicle's private LAN. Acceptable.

Actions + extras (**all extras are strings** — deliberately: a wrong `--ef` vs
`--es` type makes `getFloatExtra` silently return its default, which is exactly
the quiet-failure trap this project avoids; `--es` + `toDoubleOrNull` rejects
loudly instead):

```
org.pureagave.zodiac.beacon.SIM_GPS       extras: preset | lat+lon, hdg, spd
org.pureagave.zodiac.beacon.SIM_GPS_OFF   no extras
```

Receiver logic (thin — all decisions live in `simCommand`):

```kotlin
onReceive:
  if (!TelemetryBroadcaster.isRunning.value) { Log.w(tag, "sim: beacon not running, ignored"); return }
  when (intent.action) {
    SIM_GPS      -> simCommand(extras..., current = simState.value, nowMs = SystemClock.elapsedRealtime())
                      ?.let { simState.value = it; Log.i(...armed/adjusted...) }
                      ?: Log.w(tag, "sim: invalid command rejected: ${intent.extras}")
    SIM_GPS_OFF  -> { simState.value = null; Log.i(tag, "sim: OFF — real GNSS restored") }
  }
```

Registration in `TelemetryService.onStartCommand` (idempotent, mirroring the
`versionBroadcaster` guard at `TelemetryService.kt:98-99`), unregister in
`onDestroy`:

```kotlin
ContextCompat.registerReceiver(
    this, simReceiver,
    IntentFilter().apply { addAction(ACTION_SIM_GPS); addAction(ACTION_SIM_GPS_OFF) },
    "android.permission.DUMP",          // sender must hold DUMP → adb shell only
    null,
    ContextCompat.RECEIVER_EXPORTED,    // required explicit flag at targetSdk 35
)
```

### 5. adb command reference (goes in the spec, SYNC entry, and a KDoc on the receiver)

```bash
# park the fleet at the Man
adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS --es preset MAN
# drive from camp toward the Man-ish direction at 12 kph
adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS --es preset HOME --es hdg 292 --es spd 12
# arbitrary point, eastbound at 8 kph
adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS --es lat 40.78325 --es lon -119.20788 --es hdg 90 --es spd 8
# turn: continue from the CURRENT simulated position on a new heading/speed
adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS --es hdg 180 --es spd 8
# off — real GNSS resumes immediately
adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS_OFF
```

A timed sequence of turn commands is a path — no path DSL in the MVP (open
question 4).

### 6. On-phone indicator — `BeaconActivity`

- New banner `TextView` above the status readout: background `#FF5555` (the
  semantic fault red), black bold text, two lines:
  `⚠ GPS SIMULATED` / `<label>  hdg <h>°  spd <s> kph`. `visibility = GONE`
  unless armed. Driven by collecting `TelemetryBroadcaster.simState` alongside
  the existing `isRunning`/`status` combine (`BeaconActivity.kt:41-48`).
- The status readout itself already carries the `⚠ GPS SIMULATED` first line
  and the `GPS SIM lat, lon` line from Phase B — the banner is the
  can't-miss-it layer on top.
- **Also update the foreground notification text** while armed (visible without
  opening the app): on arm/disarm, re-post the same `NOTIFICATION_ID` with
  "⚠ GPS SIMULATED — <label>" / the normal text. Needs `CHANNEL_ID` /
  `NOTIFICATION_ID` widened to `internal` (they're `private companion`,
  `TelemetryService.kt:169-172`) or a small `internal fun
  TelemetryService.Companion.notifySimState(...)` helper. If this fights the
  service-owns-its-notification lifecycle in practice, it may be dropped —
  the activity banner + readout line are the required indicators.

### 7. Auto-off on reboot — the whole chain, explicitly

1. `simState` is a `MutableStateFlow` field of the process singleton — process
   death (reboot, crash, force-stop) clears it. Nothing ever writes it to disk.
2. `start()` nulls it (service restart inside a living process).
3. `stop()` nulls it (STOP button, `onDestroy`).
4. The receiver is only registered while the service runs, and ignores commands
   when `!isRunning`.
5. `BootReceiver` is untouched — a boot start is a real-GNSS start because
   there is no stored "armed" for it to read. Pinned by the
   arm-writes-no-prefs test (the beacon's only persistence surface).

## Phasing

Three phases, each a runnable, gated, committed step (data → integrate → UI).
Gate before **every** commit (unscoped — covers `:app` too):

```bash
JAVA_HOME=/usr/local/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/usr/local/share/android-commandlinetools \
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
```

### Phase A — pure sim model + sentence builders (no behavior change)

**Files:**
- NEW `beacon/src/main/java/org/pureagave/zodiac/beacon/GpsSim.kt` — `SimPreset`,
  `SimSpec`, `GpsSimulator` (positionAt / turn), `simCommand`,
  `simTickSentences`, `simStatusLine`, constants (§1, §2 cadence).
- EDIT `beacon/src/main/java/org/pureagave/zodiac/beacon/Nmea.kt` — `gga(...)`,
  `rmc(...)`, private `latLonFields(...)` formatter (§2).
- NEW `beacon/src/test/.../GpsSimTest.kt`
- NEW `beacon/src/test/.../NmeaSimSentenceTest.kt`

Nothing calls the new code yet; the shipped beacon is byte-identical.

**Test plan (plain JVM, JUnit4 — every test names its mutation target, house
style per `BootReceiverTest.kt:38`):**

`GpsSimTest`:
- Stationary: spd 0, any heading, 1 h elapsed → position bit-identical to
  start. *Mutation target: dead-reckoning applied unconditionally.*
- **Independent cross-check (the test must not agree with the code):** impl is
  equirectangular; the test verifies with **haversine** — north at 10 kph for
  600 s → haversine distance from start = 1666.7 m ± 0.1 %, initial bearing ≈
  0°; repeat at hdg 90 (east — pins the cos(lat) lon scaling; a missing cos is
  a ~32 % error at 40.78° N, far outside tolerance) and hdg 225.
- Determinism: same (spec, nowMs) twice → identical result (pure function of
  elapsed, no accumulation).
- `turn`: square path — 4 × (90 s at 8 kph, then turn +90°) → final position
  within a few meters of start (haversine). *Kills sign/axis mutants.*
- `simCommand` positive: preset (case-insensitive) resolves to the exact
  restated coordinates (assert the literal doubles — drift guard); lat/lon
  path; hdg normalized (`--es hdg 450` → 90); defaults hdg 0 spd 0; turn-form
  (no position, armed) continues from `positionAt(now)`, not the original
  start.
- `simCommand` negative (each must return null, and a **positive control**
  beside it proving the same input minus the defect is accepted): preset AND
  lat/lon; lat without lon; lat 91; lon −181; garbage lat `"abc"`; spd −1;
  spd 161; spd `"NaN"`; no position while not armed; unknown preset.

`NmeaSimSentenceTest` (mirror `NmeaTest`'s `Locale.GERMANY` comma-decimal
control, `NmeaTest.kt:22`):
- Pin the full GGA and RMC strings for the MAN preset at a fixed epoch —
  **hand-computed literals** (`4046.9948,N`, `11912.4730,W`, worked in §2),
  checksum verified by an **independent XOR loop written in the test**, not by
  calling `Nmea.checksum`.
- Contract-by-field (per `docs/PROTOCOLS.md` §2.4): split on `,` and assert GGA
  ≥ 10 fields, field 6 == `"8"`, field 7 == `"12"`, field 8 == `"0.8"`; RMC ≥ 9
  fields, field 2 == `"A"`, field 7 == knots (`spd/1.852` to 1 dp), field 8 ==
  heading.
- Southern/eastern hemisphere case (e.g. −33.9, 151.2 → `S`/`E`) — sign
  handling.
- Knots conversion spot check: 8.0 kph → `4.3`.
- Under `Locale.GERMANY`: no `,`-decimal anywhere in either sentence.
- `simTickSentences`: tick 0 → `[HDT, GGA, RMC]`; ticks 1–3 → `[HDT]`; tick 4 →
  all three. HDT carries the sim heading. *Mutation target: the `% 4` divisor
  and the HDT source swap.*

**Gate + commit:** `feat(beacon): GPS sim pure model + GGA/RMC builders (SIM-GPS 1/3)`.

### Phase B — wire into the broadcaster + adb receiver + auto-off guarantees

**Files:**
- EDIT `TelemetryBroadcaster.kt` — §3 items 1–5 (forward gate, tick branch,
  speedKph, statusText, start/stop clears, `simState`).
- NEW `SimGpsReceiver.kt` — §4.
- EDIT `TelemetryService.kt` — receiver register/unregister (§4).
- NEW `beacon/src/test/.../TelemetryBroadcasterSimTest.kt` (Robolectric, using
  the `gpsHandleOverride` seam per `TelemetryBroadcasterGpsTest`, with the
  mandatory `@After { stop(); gpsHandleOverride = null; simState.value = null }`
  singleton cleanup).
- NEW `beacon/src/test/.../SimGpsReceiverTest.kt` (Robolectric).
- EDIT `beacon/src/test/.../TelemetryServiceTest.kt` — registration assertions.

**Test plan:**

`TelemetryBroadcasterSimTest`:
- Arm → `statusText()` first line contains `GPS SIMULATED` and the GPS line
  shows the sim coordinates (call `statusText()` directly — the
  `TelemetryBroadcasterFixHealthTest` precedent — no tick-loop draining).
- Arm → `stop()` → `simState.value == null`. Arm (while stopped) is impossible
  via the receiver, but also: set `simState` manually → `start()` →
  `simState.value == null`. *Mutation targets: each clear individually.*
- **Persistence negative control:** snapshot the `zodiac_beacon` prefs key set,
  arm + disarm, assert the key set (and `PREF_TOTAL_METERS` value) unchanged.
  Positive control: `persistTotal()` does add/update its key.
- Forward suppression: with the sim armed, `forward("$GPGGA,...q1,real...")`
  must not disturb the q8/12 stamps (assert via `statusText()` fix line /
  `BeaconNet.reportedFixHealth` inputs); with sim off, the same call stamps
  normally. *Mutation target: delete the `forward()` guard.* (The send-path
  observable is the fix-health stamp, not the socket — deliberate: no flaky
  socket assertions; the wire itself is verified on hardware, below.)
- `speedKph`-into-ZTLM: armed at 12 kph → `Nmea.ztlm` input is 12.0 even with
  `lastLocation` null (assert via `statusText()` SPD or extract the value).

`SimGpsReceiverTest` (drive `onReceive` directly with a Robolectric context;
broadcaster started via the `gpsHandleOverride` seam):
- Valid preset intent while running → armed at MAN coordinates.
- lat/lon + hdg + spd intent → armed accordingly; `SIM_GPS_OFF` → null.
- Malformed (bad lat / spd 200 / preset+latlon) → stays off. Positive control
  adjacent.
- Not running → ignored (state stays null). *Mutation target: delete the
  isRunning guard.*
- Turn form while armed → new heading, position continuity.

`TelemetryServiceTest` additions:
- After `onStartCommand`: a receiver is registered for both actions **with
  `broadcastPermission == "android.permission.DUMP"`** (via
  `shadowOf(app).registeredReceivers`). After `onDestroy`: unregistered.
  *Honesty note: Robolectric does not enforce sender permissions, so the guard
  is pinned by asserting the registration argument, not by a simulated denial —
  the enforcement itself is Android's, verified once on hardware.*

**Hardware verification (post-commit, on the XCover + one tablet — measure,
don't guess):**
1. `adb shell am broadcast ... --es preset MAN` → hero map ego jumps to the
   Man; `nc`/`tcpdump` shows `$GPGGA,...,8,12,...` and no raw `$GxGGA`.
2. `--es hdg 90 --es spd 8` → ego tracks east at ~8 kph on the map.
3. `SIM_GPS_OFF` → real fix resumes within seconds.
4. **Reboot the phone while armed** → beacon auto-starts (BootReceiver) on
   **real** GNSS, banner absent. This is the safety property; verify it on the
   device, not just in Robolectric.
5. From a second (non-shell) app or `am broadcast` without shell: not
   reproducible in the field — skip; the DUMP guard is API-enforced.

**Gate + commit:** `feat(beacon): GPS sim wiring + adb receiver, auto-off on reboot (SIM-GPS 2/3)`.

### Phase C — the unmissable indicator + docs

**Files:**
- EDIT `BeaconActivity.kt` — red banner (§6).
- EDIT `TelemetryService.kt` — notification text on arm/disarm (§6, optional
  drop if it fights the lifecycle).
- NEW `beacon/src/test/.../BeaconActivitySimBannerTest.kt` (Robolectric
  `ActivityController`): banner GONE when off; set `simState` → VISIBLE, text
  contains `GPS SIMULATED` + the label; clear → GONE. *Mutation target: the
  visibility binding.*
- EDIT `docs/PROTOCOLS.md` — §2.2 note: while the beacon's sim mode is armed,
  raw GNSS passthrough is suppressed and the beacon synthesizes GGA/RMC with
  **fix quality 8** (NMEA simulation mode) + HDT at 1 Hz / 4 Hz; §2.3 table
  row note.
- EDIT `CLAUDE.md` `:beacon` section — one sentence: adb-armed GPS sim mode,
  memory-only, quality-8-marked, auto-off on reboot.
- APPEND `SYNC.md` entry (newest on top): what shipped, the adb commands, the
  safety chain.

**Gate + commit:** `feat(beacon): GPS SIMULATED banner + docs (SIM-GPS 3/3)`.
Push after each phase commit per the workflow.

## Risks / notes for the implementer

- **Detekt `TooManyFunctions`:** `TelemetryBroadcaster` has 21 member functions
  against a raised threshold of 22. Prefer the `simState` property +
  top-level-function shape (§3); do not raise the threshold.
- **Robolectric singleton hygiene:** every new broadcaster test must clear
  `simState` in `@After` (see `TelemetryBroadcasterGpsTest.kt:29-33`) or state
  leaks across test classes.
- **`ContextCompat.registerReceiver` needs the RECEIVER_EXPORTED flag** at
  targetSdk 35 — use the androidx-core overload exactly as in §4.
- Unbounded dead-reckoning is deliberate: an armed-and-forgotten moving sim
  visibly marches off the playa on every tablet, which *is* the indicator; no
  hidden travel cap to reason about.
- The compass keeps feeding `pitchDeg`/`rollDeg` (real `$ZTLM` tilt) and the
  light/shock/mic channels stay fully real while armed — only position,
  heading, speed, and fix health are simulated.

## Open questions for Rob (each has a recommended default; the spec above assumes the defaults)

1. **adb mechanism + guard** — context-registered receiver, actions
   `org.pureagave.zodiac.beacon.SIM_GPS` / `SIM_GPS_OFF`, all-string extras
   (`preset` | `lat`+`lon`, `hdg`, `spd`), sender must hold
   `android.permission.DUMP` (adb shell has it; apps can't). Alternative: a
   manifest receiver + custom signature permission — rejected because shell
   doesn't hold app signature permissions and manifest receivers force `-n
   component` into every command. **Default: as specced.**
2. **Preset list** — `MAN`, `HOME` (Camp Nebula, 7:30 & E), `TEMPLE`,
   coordinates restated from `:app` (the modules share no code). Want more —
   e.g. a gate-road point or deep-playa 90°? Cheap to add to the enum.
   **Default: the three.**
3. **Marking the wire as simulated** — decided in-spec: GGA fix-quality **8**
   (the NMEA-standard simulation-mode value) on every synthetic fix; tablets
   accept it today (measured: `NmeaParser.kt:161` takes any quality > 0) and
   `$ZBCN` reports `q8` fleet-wide. The real fork: also add an explicit 5th
   `sim` field to `$ZBCN` now (measured: the app's `parseBeaconHealth` reads
   fields 1–4 by index and ignores extras, so it's backward-compatible), or
   wait until an `:app` "SIMULATED" chip exists to read anything.
   **Recommended default: quality-8 only for the MVP; no `$ZBCN` format change
   until the tablet side renders it** (an unread field is contract risk with
   zero reward, and `:app` is out of scope this feature).
4. **How motion is specified** — heading + speed dead-reckoning, with
   "position-less re-broadcast = turn from the current simulated position", so
   a timed adb script of turns is a path. No waypoint/path DSL in the MVP.
   **Default: as specced; add a path DSL only if scripted turns prove clumsy in
   practice.**
5. **Odometer** — simulated travel deliberately does **not** feed `$ZODO`
   (persisted lifetime odometer stays real; a sim that inflates it corrupts
   real data). Consequence: the fleet sees motion while ODO holds still during
   tests. **Default: keep ODO real — confirm.**

## Appendix — preset coordinates (restated; re-verify on any city move)

| Preset | lat | lon | Source (`:app`) |
|---|---|---|---|
| `MAN` | 40.783247448000054 | −119.20788409599999 | `core/geo/GoldenSpike.kt:19` (Y2026, = ACTIVE) |
| `HOME` | 40.7832474 | −119.1908380 | `core/ops/Camp.kt:23` (Camp Nebula, 7:30 & E — geometric estimate; swap when the BM API geocode lands) |
| `TEMPLE` | 40.78809942300006 | −119.201499636 | `core/ops/NavTarget.kt:21` (2026 GIS CPN) |
