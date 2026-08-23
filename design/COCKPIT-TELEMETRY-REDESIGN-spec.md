# COCKPIT-TELEMETRY-REDESIGN — the transport box becomes a telemetry surface

**Replace the debug control strip on RADAR + MAP with an always-honest TELEMETRY
box (source status line + live sci-fi ticker); move every bench control behind a
hidden gesture; make the throttle trace a real 30-minute speed graph; make
CELL A this display's real battery and CELL B a labelled Victron placeholder;
replace the speed gauge with real ambient + shock sensors; give passengers the
same telemetry feed as a card.**

Status: SPEC (contract), **rev 2** — all open questions resolved by Rob
2026-08-22 (§Open questions, now closed) and **Phase D revised** from
pseudo-random prop gauges to real-device-battery + Victron placeholder. Author:
planner session, 2026-08-22. Downstream: an implementation agent treats this
file as the source of truth. Decisions marked DECIDED were made with the
product owner — do not re-litigate them. If this doc and the code disagree
after implementation, the code wins — fix the doc and record the correction in
`SYNC.md`.

## Problem

The `conceptControlStrip` (`ui/concepts/ConceptControls.kt:36-195`) is a bench
tool wearing a cockpit costume: TRANSPORT chips for transports that are all
fake, HDG/SPD debug steppers, FAKE-GPS nudges, and zoom chips the touch map
made redundant — rendered four times (RADAR + MAP × portrait + landscape). On a
real drive it is dead weight in prime screen space, and it hides the one thing
a driver actually needs from that box: **which position source am I really on?**
`FailoverLocationSource` deliberately presents itself as NET
(`data/sensor/FailoverLocationSource.kt:52`), and its `usingFallback` signal —
already plumbed as far as `RoutedLocationSource.usingFallback`
(`data/sensor/RoutedLocationSource.kt:56-64`) — is **collected by nothing**.
A beacon death today is invisible on screen (a deliberate early call, now
reversed by the product owner: surface it).

Meanwhile MAP's instrument wall lies politely: `throttleTrace` is a hardcoded
21-point float array (`InstrumentBayScreen.kt:366-385`), `cellsTile` is two
literals (`:287-296`), and `speedTile` duplicates a number three other surfaces
already show.

## Decisions (DECIDED with the product owner — build exactly this)

1. **TELEMETRY box replaces `conceptControlStrip` on RADAR + MAP** (both
   orientations). Top: an always-visible source status line — `ON BEACON` /
   `LOCAL BACKUP` / no-signal — state-coloured within the semantic system
   (amber banned). Below: a scrolling "sci-fi" ticker of real live telemetry
   (lat/lon/fix, heading, speed, lux, shock, sats, beacon health) — decorative
   in *presentation*, real in *values*. The "TRANSPORT" label becomes
   "TELEMETRY".
2. **All manual/test controls move behind a hidden gesture** (dev panel):
   transport select + connect, GPS-source chips + status, HDG/SPD steppers,
   FAKE-GPS nudge, and whichever of ZOOM/RECENTER/TOP-TILT survive (per-control
   verdicts below). Fully functional, just off the normal cockpit. Trigger
   (RESOLVED 2026-08-22): **5 quick taps on the TELEMETRY box header** via the
   pure `MultiTapCode` — not a corner, not a long-press.
3. **`throttleTrace` becomes a real graph**: last 30 min of
   `effectiveSpeedKph`, label stays "THROTTLE TRACE", fixed Y 0–160 kph
   (`CockpitUiState.MAX_SPEED_KPH`). Pure, tested ring buffer with time-based
   downsampling, fed by the ViewModel, exposed on state; the composable only
   draws. **No coasting/interpolation scheme.**
4. **`cellsTile` becomes half-real (REVISED by Rob 2026-08-22)**: **CELL A =
   the real battery level of *this* Android device** — a glance tells the
   operator a mounted display's charger has failed (permanent vehicle power is
   a hard requirement; the A54 died in days on battery). **CELL B = a static
   50% placeholder** for the future Victron pack, visibly marked pending so it
   cannot read as a live reading next to the now-real A. Green bars (Rob's
   call). Unknown battery ⇒ "—", never a fake number. Shaped so real Victron
   SoC/voltage drops into the B slot later. (The earlier pseudo-random
   `CellGauge` prop model + `SIM` tag are **dropped**.)
5. **`speedTile` → AMBIENT + SHOCK tile**: live `ambientLux` (`$ZENV`) and
   shock peak-g (`$ZSHK`) — real sensors. Absence shows "—", never a fake 0.
6. **New `PassengerCard.TELEMETRY`** — the same ticker + status feed for
   riders, reusing the core model, gated on genuinely having data. **Lands
   after `design/PASSENGER-UI-split-spec.md` Phase 2** (same files).

## Grounding facts (measured against the code, 2026-08-22)

These bound the implementation; several are traps.

- **The failover leg is *almost* surfaced.** `FailoverLocationSource.usingFallback`
  (`FailoverLocationSource.kt:54-63`) → `RoutedLocationSource.usingFallback`
  (`RoutedLocationSource.kt:56-64`) exists and is tested, but no collector in
  `CockpitViewModel.init` reads it and `CockpitUiState` has no field for it.
  The minimal plumbing is one new state field + one new collector — no new
  source-layer code at all (§Phase A).
- **`GpsFix` has no satellite count** (`core/sensor/GpsFix.kt:17-22`: location,
  headingDeg?, speedKph?, fixQualityM? only). Satellites/battery/uptime come
  from `$ZBCN` → `BeaconHealth` (`core/telemetry/BeaconHealth.kt`), already on
  `CockpitUiState.beaconHealth`. The ticker's SATS line is beacon-sourced and
  therefore honestly absent on SYSTEM/FAKE.
- **`CockpitViewModel` has 21 functions; detekt `TooManyFunctions
  thresholdInClasses: 22` fires at `>=`** (`config/detekt/detekt.yml:49`, and
  its own comment: "If a new cockpit action would push past it, put it on the
  delegate that owns its state — do not bump this again"). **This feature must
  add zero functions to `CockpitViewModel`.** Everything below fits in `init`
  collectors/tick loops and defaulted constructor params
  (`ignoreDefaultParameters: true`, `detekt.yml:37` — required ctor params are
  6 of the 11 threshold).
- **The strip's ZOOM/RECENTER/TILT rows are already largely redundant**:
  both screens pass `showTiltToggle = false` at all four call sites
  (`InstrumentBayScreen.kt:121,150`; `MotionTrackerScreen.kt:194,223`); MAP's
  map tile has its own TOP/TILT chips (`InstrumentBayScreen.kt:234-237`); and
  both screens overlay an always-visible `recenterButton`
  (`InstrumentBayScreen.kt:193-199`, `MotionTrackerScreen.kt:265-271`).
- **All four screen corners are taken** (`CockpitScreen.kt:130-160` +
  burn-in scaffold): top-left park, bottom-left burn-in tuning, bottom-right
  log viewer, top-right passenger toggle — and the two right corners also feed
  the kiosk-exit tap code (`core/kiosk/KioskExitCode.kt`). The dev-panel
  trigger must not touch any corner.
- **`shockAlertG` is transient** — cleared 2 s after each event
  (`CockpitViewModel.kt:349-353`, `SHOCK_ALERT_MS` at `:511`). A persistent
  "last shock" readout needs a new state field fed from
  `BeaconSensors.lastShockG`/`shockCount` (already delivered to the VM,
  `CockpitViewModel.kt:337-355`).
- **`effectiveSpeedKph`** (`CockpitUiState.kt:256`) is the single-owner speed
  (measured GPS wins, debug chip only under FAKE) — the graph samples it, per
  the decision. The GPS collector holds the last known `gpsSpeedKph`
  (`CockpitViewModel.kt:251`), so the sampled value is exactly what the
  cockpit already presents as "how fast are we going".
- **Passenger doctrine**: "no collision flags, no braking, no alarm red"
  (`ui/passenger/PassengerScreen.kt:63-66`); a card with no data leaves the
  rotation (`core/passenger/PassengerData.kt:23-41`); `PassengerCards.kt` is
  ~250 lines and the split-don't-grow precedent is set
  (PASSENGER-UI spec, "new file, not an addition").
- **`ConceptTheme` roles** (`ui/concepts/ConceptTheme.kt`): `primary` green
  chrome, `secondary` StatusBlue, `accent` DataPurple (live data), `dim`
  DimGreen, `error` AlertRed. `gpsStatusLabel` (`core/sensor/
  LocationSourceError.kt:39-56`) is the pure-status-line precedent, glyphs and
  all.
- The ops footer already shows TRIP/ODO/BATT/SATS/UP/shock
  (`ui/ops/OpsReadout.kt:164-228`). The ticker overlaps it on those channels
  **by decision** (the decided channel list includes sats + beacon health);
  the footer is fixed and scannable, the ticker is ambient and rotating — both
  render the same true values from the same state, so they cannot disagree.
- `:app` has **no Compose UI harness** — every composable here (ticker scroll,
  status line, gauges, graph) is **screenshot-verified on device**, and every
  decision is pure core with JUnit tests. Same doctrine as RES-P2-1 /
  `SurroundRing` / PASSENGER-UI.

## Colour assignments (semantic system, justified; amber banned)

| Element | Colour | Justification |
|---|---|---|
| Box border, "> TELEMETRY" header, ticker labels | `theme.primary` (green) | Chrome/labels — the system's structural colour. |
| Status `● ON BEACON` | `theme.secondary` (StatusBlue) | This *is* link/GPS state — blue's exact charter ("link/GPS state" — CLAUDE.md colour system). Nominal must read calm. |
| Status `◐ LOCAL BACKUP` | `theme.secondary` (StatusBlue) | **Rob's call (resolved 2026-08-22): blue, NOT purple.** Both source states are link/GPS *status* — blue's exact charter — and purple stays exclusively a live-data colour. The distinction is carried by glyph + wording (`◐ LOCAL BACKUP` vs `● ON BEACON`), never by colour alone. Red stays wrong here: navigation still works. |
| Status `◇ SRC FAKE` / `◇ SRC SYSTEM` / … (manual override via dev panel) | `theme.secondary` (StatusBlue) | Same resolution extended: a deliberately-selected source is still source *status*, and the operator just chose it in the dev panel. The `◇` glyph + explicit source name do the talking. |
| Status `⋯ SEARCHING` | `theme.dim` (DimGreen) | Transitional, non-fault (mirrors `gpsStatusLabel`'s non-fault SEARCHING); dim = "in progress, nothing to act on". |
| Status `✕ NO SIGNAL` | `theme.error` (red) | No usable position from any source **is** a fault — the one state here that demands action. |
| Ticker values | `theme.accent` (purple) | Live data values — purple's charter. These are the actual live numbers. |
| Speed-graph polyline | `theme.accent` (purple) | A live data value drawn as a line. Grid/frame in `theme.dim`/`theme.primary` (chrome). |
| Lux / last-shock values | `theme.accent`; shock flashes `theme.error` only while `shockAlertG != null` | Live values; a fresh jolt is the ops footer's established red-shock precedent (`OpsReadout.kt:185-194`). |
| **CELL A bar + percent (real device battery)** | `theme.primary` (green) | Rob's explicit call: the cell wall keeps its established green-bar chrome look even though A is now a real value — the bar *is* the tile's chrome, and the low-battery fault case gets red (below), which is the reading that matters. |
| CELL A when low (charger likely failed) | `theme.error` (red) fill/percent at ≤ 20% | The whole point of the tile: a mounted display quietly discharging IS a fault. Threshold mirrors the beacon's `BEACON_BATTERY_LOW_PCT = 20` (`OpsReadout.kt:50`) — one convention for "battery low is a fault". |
| **CELL B bar (Victron placeholder, static 50%)** | `theme.primary` green, **dimmed treatment + pending label** | A static value beside a real one must not read as live: the bar renders dimmed (e.g. `theme.dim` fill) with a `VEHICLE · PENDING` marker (exact treatment screenshot-tunable, §Phase D). Never purple, never full-brightness-identical to A. |
| Passenger card fault states | `theme.dim`, never red | Passenger screen's "no alarm red" doctrine (`PassengerScreen.kt:63-66`) outranks the cockpit mapping on that surface. |

## Cross-cutting constraints (bind every phase)

- **Zero new functions on `CockpitViewModel`** (21/22, fires at `>=`). New
  behaviour = `init` collectors/tickers + defaulted ctor params only.
- No new logic in a composable: ring buffer, downsampling, cell model, ticker
  formatting/selection, source-status derivation, tap-code recognition — all
  pure `core/` with mutation-killing tests (positive + negative controls).
  Composables draw and forward.
- Both orientations reflow (`screenOrientation="fullUser"`): the TELEMETRY box
  replaces the strip at all four call sites; the graph exists in both MAP
  orientations.
- Detekt fires at `>=`: new files (`TelemetryBox.kt`, `DevControlPanel.kt`,
  `PassengerTelemetryCard.kt`, one core file per model) rather than growing
  `ConceptControls.kt` / `PassengerCards.kt` / `NmeaParser`-style objects.
  `thresholdInFiles: 19` bounds each new file.
- All number formatting `Locale.US`-pinned (project convention, `Nmea.kt`
  precedent) — the tests pin exact strings.
- Nothing fabricated presented as real: absent sensor ⇒ "—" or an omitted
  ticker line, never 0; the cells are labelled as simulation; the ticker
  renders only actual state values.
- Gate before every commit (unscoped — covers `:beacon`):

  ```bash
  JAVA_HOME=/usr/local/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME=/usr/local/share/android-commandlinetools \
  ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
  ```

  Then a dated `SYNC.md` entry (newest on top) and push `origin/main`.
- After the final MAP phase, update CLAUDE.md's "What is fake" bullet ("MAP's
  cell gauges and throttle trace are hard-coded literals" — the trace becomes
  real; **CELL A becomes this display's real battery; CELL B stays a static,
  visibly-labelled Victron placeholder**, not random) and give `README.md` its
  one-line mention when the feature is deemed shipped.

## Phasing rationale

Five phases, each a standalone runnable commit. **A** first because it removes
the strip and stands up the box + dev panel + failover plumbing everything else
sits beside; **B/C/D** are independent MAP-tile swaps ordered by plumbing depth
(graph from state already held, then beacon sensors already folded, then the
new device-battery source); **E** last because it touches the same three
passenger files as PASSENGER-UI Phase 2 and must land after it.

---

## Phase A — TELEMETRY box + hidden dev panel (RADAR + MAP)

Commit as two runnable sub-commits if preferred: **A1** core models + state
plumbing (no visible change), **A2** UI swap.

### Core decisions (pure, no Android imports)

**New `core/sensor/TelemetrySourceStatus.kt`** — the status-line derivation,
sibling to `gpsStatusLabel`:

```kotlin
enum class TelemetrySource { ON_BEACON, LOCAL_BACKUP, MANUAL_SOURCE, SEARCHING, NO_SIGNAL }

data class SourceStatusLine(val text: String, val source: TelemetrySource)

fun telemetrySourceStatus(
    selected: LocationSourceType,
    state: LocationSourceState,
    usingFallback: Boolean,
): SourceStatusLine
```

Truth table (pin every row):

| selected | state | usingFallback | result |
|---|---|---|---|
| NET | Active | false | `● ON BEACON` / ON_BEACON |
| NET | Active | true | `◐ LOCAL BACKUP` / LOCAL_BACKUP |
| FAKE/SYSTEM/BLE/USB | Active | (any) | `◇ SRC <NAME>` / MANUAL_SOURCE |
| any | Searching | (any) | `⋯ SEARCHING` / SEARCHING |
| any | Disconnected / Error | **(any — including true)** | `✕ NO SIGNAL` / NO_SIGNAL |

The last row's "(any)" is deliberate: `usingFallback` may only upgrade an
*Active* state to LOCAL_BACKUP — a stale fallback flag over a dead source must
never read as a working backup. KDoc why the driver-facing line collapses
Disconnected/Error/Searching-nuance to three actionable answers ("can I trust
the position: yes / yes-but / no"); the error *category* detail stays on the
dev panel's `gpsStatusLabel` line and in the log.

**New `core/telemetry/TelemetryTicker.kt`** — line formatting + selection:

```kotlin
enum class TickerRole { CHROME, VALUE }

data class TickerSegment(val text: String, val role: TickerRole)

data class TickerInputs(
    val lat: Double? = null, val lon: Double? = null, val fixQualityM: Double? = null,
    val headingDeg: Int? = null, val gpsSpeedKph: Double? = null,
    val lux: Double? = null, val lastShockG: Double? = null,
    val health: BeaconHealth? = null, val odometer: Odometer? = null,
)

/** Every line that currently has real data behind it, in pinned order. */
fun tickerLines(inputs: TickerInputs): List<List<TickerSegment>>

/** Cyclic selection for the ticker's next emission; the NO-TELEMETRY line when empty. */
fun tickerLineAt(inputs: TickerInputs, seq: Long): List<TickerSegment>
```

Pinned line formats (`Locale.US`; labels CHROME, values VALUE):

- `POS 40.78644 -119.20632` — 5 decimals (~1.1 m — matches the fix accuracy
  class, no fake precision)
- `FIX ±4.2M` — from `fixQualityM`
- `HDG 273°` · `SPD 12.4KPH` — heading from state; speed is **`gpsSpeedKph`
  (measured) only** — the ticker claims "raw telemetry", so the commanded debug
  speed never appears here (the graph's `effectiveSpeedKph` choice is a
  separate, decided call)
- `LUX 84` / `LUX 8.4` (<10, one decimal) / `LUX 12.4K` (≥1000) — shared
  `formatLux` used again by Phase C
- `SHK 1.2G` — last shock peak
- `BCN BATT 78% SATS 11 UP 3H07`
- `ODO TRIP 3.1KM TOTAL 812.4KM`
- Fallback when *nothing* has data: `NO TELEMETRY` (single CHROME segment) —
  shown, not hidden; an empty bus is a true statement.

**Absence rule:** a channel with no data contributes **no line** (mirrors "a
card with no data leaves the rotation") — the ticker never pads with dashes;
the per-value dash rule is for fixed tiles (Phase C), not the rotating feed.

**New `core/model/MultiTapCode.kt`** — the dev-panel unlock, `KioskExitCode`'s
pure-clock-injected shape (`core/kiosk/KioskExitCode.kt:27-57`) reduced to one
zone: `class MultiTapCode(requiredTaps: Int = 5, windowMs: Long = 2_000L)` with
`fun tap(nowMs: Long): Boolean` — true exactly on the Nth tap where every
inter-tap gap ≤ `windowMs`; a late tap restarts the count at 1. (Not a reuse of
`KioskExitCode` itself: that class encodes two zones and must stay exactly as
provisioned tablets expect.)

### State plumbing (`CockpitUiState` + `CockpitViewModel` — no new functions)

- `CockpitUiState` gains two fields (36 → 38; the A5 per-frame copy note in
  `tasks/open.md` absorbs +2 references — negligible):
  - `gpsUsingFallback: Boolean = false` — KDoc: mirrors
    `RoutedLocationSource.usingFallback`; only meaningful alongside
    `selectedLocationSource`/`locationState` via `telemetrySourceStatus`.
  - `lastShockG: Double? = null` — the *persistent* last `$ZSHK` peak (vs the
    transient `shockAlertG`); null until the first event this session.
- `CockpitViewModel.init` gains one collector inside the existing outer launch
  (pattern of `CockpitViewModel.kt:318-326`):

  ```kotlin
  launch {
      locationSource.usingFallback.collect { fb ->
          _uiState.update { it.copy(gpsUsingFallback = fb) }
      }
  }
  ```

- The existing `beaconSensors` collector (`CockpitViewModel.kt:337-355`) also
  folds `lastShockG = if (sensors.shockCount > 0) sensors.lastShockG else null`
  in its first `update`.

### UI (draw + plumb only)

**New `ui/concepts/TelemetryBox.kt`:**

```kotlin
@Composable
fun telemetryBox(
    state: CockpitUiState,
    theme: ConceptTheme,
    onOpenDevPanel: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- `Column` bordered `1.dp theme.primary` (the strip's chrome), padding 8dp:
  1. Header `> TELEMETRY` — green, bold, 11sp mono (the `sectionLabel` voice,
     `ConceptControls.kt:198-209`). The header row carries a
     `remember { MultiTapCode() }` and feeds `tap(System.currentTimeMillis())`
     from `detectTapGestures(onTap)`; completion → `onOpenDevPanel()`.
  2. Status line — `telemetrySourceStatus(state.selectedLocationSource,
     state.locationState, state.gpsUsingFallback)`, drawn 13sp mono bold in the
     colour table's mapping. Glyph + text, so colour is never the only channel.
  3. Ticker — fills the remainder, clipped. Local
     `remember { mutableStateListOf<...>() }` of the last ≤ 14 emitted lines;
     a `LaunchedEffect` ticks every `TICKER_STEP_MS = 800` ms, appends
     `tickerLineAt(inputs, seq++)`, culls the oldest. Newest at the bottom,
     lines step upward — terminal feel, no smooth-scroll animation (cheaper,
     and stepped motion is period-correct). 10sp mono; segments coloured
     CHROME→`theme.primary`, VALUE→`theme.accent`; the composable may dim the
     oldest third to `theme.dim` (draw-only flourish). Recomposition is
     confined to the box (local state), ~1.25 Hz.
  4. `TickerInputs` built from `state` fields right here — field-copying is
     plumbing, not logic (the `passengerInputs` precedent,
     `PassengerScreen.kt:446-458`).

**New `ui/debug/DevControlPanel.kt`** (sits beside `LogViewerPanel.kt` — this
is bench tooling, not cockpit chrome):

```kotlin
@Composable
fun devControlPanel(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
    onClose: () -> Unit,
)
```

Full-screen scrim overlay (the `logViewerPanel` pattern) titled
`DEV / BENCH CONTROLS`, with a visible `CLOSE` chip (hidden to *enter*, obvious
to *leave*). Content = the strip's sections moved verbatim, calling the same
ViewModel entry points, plus the per-control verdicts:

| Control | Verdict | Rationale |
|---|---|---|
| `> TRANSPORT` chips + CONNECT/DISCONNECT + phase text (`ConceptControls.kt:54-79`) | **keep** | Bench-only by definition — every transport is fake (CLAUDE.md), but the gateway plumbing is exercised by tests and demos. |
| `> GPS` source chips + `gpsStatusLabel` line (`:81-102`) | **keep** | The manual source override and the error-category line — the operator's playa diagnostic. |
| `> HDG` / `> SPD` steppers (`:104-126`) | **keep** | They steer the FAKE source and the outbound `SetHeading`/`SetSpeed` commands — the bench drive. |
| `> FAKE GPS NUDGE` + `GPS RESET` (`:162-193`) | **keep**, still gated on `selectedLocationSource == FAKE` | Bench positioning. |
| `ZOOM-`/`ZOOM+` (`:128-142`) | **keep** | Stepped, repeatable zoom for screenshot comparisons and a misbehaving digitizer; two chips calling an existing bounded API — nearly free. |
| `RECENTER` chip (`:142`) | **drop** | An always-visible `recenterButton` already overlays both screens (`InstrumentBayScreen.kt:193-199`, `MotionTrackerScreen.kt:265-271`). |
| `TOP`/`TILT` row (`:145-160`) | **drop** | Dead at every current call site (`showTiltToggle = false` × 4); MAP's map-tile header has its own live pair (`InstrumentBayScreen.kt:234-237`). |

Constants `HEADING_STEPS`/`SPEED_STEPS`/`ZOOM_STEP`/`NUDGE_STEP_M`
(`ConceptControls.kt:256-259`) move with the panel.

**Modify `ui/concepts/ConceptControls.kt`:** delete `conceptControlStrip` +
`sectionLabel` moves or stays as needed; **keep** `themedChip`/`actionChip`
(shared by `bayMapTile`, `driveToBar`, the panel). File shrinks — never grows.

**Modify `ui/concepts/MotionTrackerScreen.kt`:** both `conceptControlStrip`
call sites (`:189-195` portrait, `:218-224` landscape) become `telemetryBox`
with the same modifiers; screen-local
`var devPanelOpen by remember { mutableStateOf(false) }`,
`onOpenDevPanel = { devPanelOpen = true }`, and
`if (devPanelOpen) devControlPanel(state, viewModel, theme) { devPanelOpen = false }`
as the last child of the root `Box` (`:112`) so it overlays scanlines and all.

**Modify `ui/concepts/InstrumentBayScreen.kt`:** same swap at `:116-122`
(portrait, keeps `height(170.dp)`) and `:144-152` (landscape, keeps
`weight(1f)`); same `devPanelOpen` overlay pattern in the root `Box` (`:81`).

DRIVER is untouched (it never rendered the strip). Panel visibility is
deliberately screen-local ephemeral UI state — not `CockpitUiState`, not
persisted (a reboot must never come up with bench controls open).

### Tests (`app/src/test/...`; every negative gets a positive control)

**New `core/sensor/TelemetrySourceStatusTest.kt`:**

| Test | Kills the mutant that… |
|---|---|
| `net_active_is_on_beacon` | inverts/ignores the fallback flag (positive control) |
| `net_active_fallback_is_local_backup` | drops the `usingFallback` branch — **the headline** |
| `fallback_over_dead_source_is_no_signal_not_backup` (Disconnected + usingFallback=true) | orders fallback above liveness |
| `manual_sources_name_themselves` (FAKE⇒`◇ SRC FAKE`, USB⇒`◇ SRC USB`) | hardcodes NET / drops the name |
| `searching_and_no_signal_rows` (Searching ⇒ SEARCHING; Error + Disconnected ⇒ NO_SIGNAL) | merges the transitional state into the fault |

**New `core/telemetry/TelemetryTickerTest.kt`:** full-inputs ⇒ all lines in
pinned order with exact pinned strings; each nullable channel removed ⇒ exactly
that line absent (one test per channel — a dropped-guard mutant fails a
specific row); empty inputs ⇒ single `NO TELEMETRY`; `tickerLineAt` cycles
`seq % size` (seq = size ⇒ line 0) and survives an inputs shrink between calls;
`formatLux` boundary rows (9.9⇒`9.9`, 10⇒`10`, 999⇒`999`, 1000⇒`1.0K`).

**New `core/model/MultiTapCodeTest.kt`:** 5 in-window taps ⇒ true on exactly
the 5th (false on 1-4 — the positive+negative pair); a gap > window between
taps 3 and 4 ⇒ that tap restarts at 1 and 4 more are needed; after firing, the
count resets (5 more taps needed); a 4-tap sequence never fires (kills
`>= requiredTaps - 1`).

**Extend `ui/viewmodel/CockpitViewModelTest.kt`:** `usingFallback` flow
emitting true ⇒ `uiState.gpsUsingFallback` true, then false ⇒ false (the fold
exists and isn't latched); beacon sensors with `shockCount = 1, lastShockG =
1.2` ⇒ `lastShockG == 1.2` persists after `SHOCK_ALERT_MS` elapses (vs
`shockAlertG` which clears — the pair proves the two fields are distinct).

### On-device verification (S9+ hero; sleep the display after screenshots)

- RADAR + MAP, both orientations: TELEMETRY box where the strip was; `● ON
  BEACON` blue with the beacon live; ticker lines stepping with real values.
- Kill the beacon (or its Wi-Fi): S9+/A54 flip to `◐ LOCAL BACKUP` — still
  blue, but the glyph + wording change must be legible at a glance — within
  ~10.5 s (`LocationFailoverPolicy` drop window); restore ⇒ back to
  `● ON BEACON` after the ~10 s recovery. On a Fire (no GNSS): `✕ NO SIGNAL`
  red.
- 5 taps on the header ⇒ dev panel; every moved control works (switch to FAKE,
  nudge, HDG/SPD, transport CONNECT); status line reads `◇ SRC FAKE` while on
  FAKE; CLOSE returns; 4 slow taps do nothing.
- No corner gesture regressed (park, tuning, logs, passenger toggle, kiosk
  code).

Commit: `feat(app): COCKPIT-TLM 1 — telemetry box + failover status, bench controls behind hidden panel`.

---

## Phase B — the throttle trace becomes a real 30-minute speed graph (MAP)

### Core (pure)

**New `core/telemetry/SpeedTrail.kt`:**

```kotlin
data class SpeedTrail(
    val samples: List<SpeedSample> = emptyList(),   // (bucket: Long, kph: Float), ascending
) {
    fun record(nowMs: Long, kph: Double): SpeedTrail
    fun window(nowMs: Long): List<Float?>            // exactly WINDOW_BUCKETS slots, oldest→newest
    companion object {
        const val BUCKET_MS: Long = 10_000L
        const val WINDOW_BUCKETS: Int = 180          // 30 min
        val EMPTY = SpeedTrail()
    }
}
```

- `record` keys the sample to `nowMs / BUCKET_MS` (last write per bucket wins),
  clamps kph into 0–160 (`CockpitUiState.MIN/MAX_SPEED_KPH` — the graph's fixed
  axis), evicts anything older than the window. Immutable value ⇒ `.copy()`
  state semantics and list-equality diffing for free.
- `window(nowMs)` lays samples into 180 fixed slots by bucket index; a bucket
  with no sample is `null` — **a gap in the data is a gap in the line**
  (process asleep, app restarted). No interpolation, no coasting — DECIDED.
- Session-scoped, not persisted: an app restart starts an empty trail (leading
  nulls fill left-to-right over 30 min). Honest and cheap; KDoc it.

**Cadence: 10 s (180 points) — justification (measure, don't guess):** the
landscape trace tile draws ~264dp wide (280dp column − 2×8dp padding,
`InstrumentBayScreen.kt:137-142`); at the Fire HD 10's ~1.5 density that is
~400 px, so 180 segments ≈ 2.2 px each — at the drawing resolution; more
samples add copy cost (A5: each fold copies the whole `CockpitUiState`) but no
visible information. 10 s also cleanly undersamples the ~1 Hz GPS speed the
state already holds, and a 30-minute *trend* graph has no business resolving
sub-10 s events. (5 s/360 pts is the fallback if Rob wants finer — §Open
questions.)

### State + ViewModel (no new functions)

- `CockpitUiState` gains `speedTrail: SpeedTrail = SpeedTrail.EMPTY` (38 → 39).
- `CockpitViewModel` gains a defaulted ctor param
  `nowMs: () -> Long = System::currentTimeMillis` (defaulted ⇒ detekt-free via
  `ignoreDefaultParameters`; factory passes it through, also defaulted) and one
  tick loop in `init`'s outer launch:

  ```kotlin
  launch {
      while (true) {
          delay(SpeedTrail.BUCKET_MS)
          _uiState.update { it.copy(speedTrail = it.speedTrail.record(nowMs(), it.effectiveSpeedKph)) }
      }
  }
  ```

  Sampling `effectiveSpeedKph` at the tick is exactly "graph the speed the
  cockpit already claims" — DECIDED, and deliberately *not* a per-fix
  bucket-max (that would be a second speed-aggregation policy to defend).

### UI

`InstrumentBayScreen.kt`:

- **Landscape** (`:139-142`): `throttleTrace(theme)` →
  `throttleTrace(theme, state.speedTrail.window(nowMsNow), modifier)` — keep
  the composable name and the `"THROTTLE TRACE"` label (DECIDED). The
  composable takes the slot list, maps index→x, `kph/160f`→y (inverted), draws
  polyline segments **breaking at nulls**, plus dim horizontal gridlines at
  40/80/120 kph (chrome). Delete the literal float array (`:368-372`).
  For `nowMsNow` the draw site passes `System.currentTimeMillis()` — windowing
  by now-time is presentation alignment, the decisions (bucketing, eviction,
  clamping) already happened in core.
- **Portrait** (`:111-115`): the 3-tile row becomes 4 — `headingTile`,
  `speedTile` (until Phase C), `cellsTile`, new trace tile
  (`tile` + label + `throttleTrace`, same 120dp row) — portrait finally gets a
  speed representation without stealing map height (4 × ~190dp fits the Fire's
  800dp portrait width).

### Tests

**New `core/telemetry/SpeedTrailTest.kt`:**

| Test | Kills the mutant that… |
|---|---|
| `window_is_always_exactly_180_slots` (empty, partial, full) | returns a variable-length list the renderer would mis-scale |
| `record_lands_in_the_right_slot_and_newest_is_last` | reverses order / off-by-ones the bucket index |
| `same_bucket_last_write_wins` (two records in one bucket) | duplicates slots |
| `samples_age_out_after_30_minutes` (record at t; window at t+30min+1bucket ⇒ all null) | drops eviction |
| `missed_ticks_leave_null_gaps` (records at t, t+30s ⇒ slots between are null) | fabricates continuity — **the anti-interpolation pin** |
| `kph_clamped_to_axis` (200 ⇒ 160f; −5 ⇒ 0f; 80 passes through as control) | unclamped values escape the fixed axis |

**Extend `CockpitViewModelTest`:** `advanceTimeBy(30_000)` ⇒ 3 samples in
`uiState.speedTrail` carrying the current `effectiveSpeedKph` (with the test
`nowMs` clock injected); with a live `gpsSpeedKph` the sampled value is the
measured one, not the chip (kills a `speedKph`-instead-of-`effectiveSpeedKph`
mutant).

### On-device verification

Drive (or FAKE-drive via the dev panel — Phase A's payoff): the trace grows
left→right, tracks real speed excursions, holds shape across a concept switch,
shows a visible gap after backgrounding the app a minute (Fire). Screenshot
both orientations.

Commit: `feat(app): COCKPIT-TLM 2 — throttle trace graphs real 30-min speed history`.

---

## Phase C — AMBIENT + SHOCK tile replaces the speed gauge (MAP)

### Core

**New `core/telemetry/EnvGaugeModel.kt`** (formatters shared with the Phase A
ticker live wherever `formatLux` landed — one definition, both callers):

```kotlin
/** 0..1 fill for a log-scale lux bar spanning 0.1..100_000 lx; null lux ⇒ null (draw nothing). */
fun luxBarFraction(lux: Double?): Float?

/** 0..1 fill for a 0..2 g shock bar; null ⇒ null. Clamps above 2 g. */
fun shockBarFraction(g: Double?): Float?

fun formatLuxValue(lux: Double?): String   // "84" / "8.4" / "12.4K" / "—"
fun formatShockValue(g: Double?): String   // "1.2G" / "—"
```

Log scale for lux because the sensor spans five decades (moonless playa ≈ 0.1,
noon ≈ 100 k) and a linear bar would be a binary day/night flag. 2 g shock span
brackets the beacon's observed event range (`bumpVerdict` constants: 0.6
"playa", 1.5 "hold on" — `PassengerScreen.kt:532-533`); a chosen UX span, not a
datasheet number — say so in the KDoc.

### UI

`InstrumentBayScreen.kt`: `speedTile` (`:272-284`) and `speedGauge`
(`:332-363`) are **deleted**; a new `ambientShockTile(theme, lux, lastShockG,
shockAlertActive, modifier)` takes their two slots (portrait row `:113`,
landscape left column `:132`). Layout: two stacked mini-readouts —
`LUX` label (chrome) + `formatLuxValue` (purple, 20sp) + a thin log bar
(purple fill, green frame; absent bar when null); `SHOCK` label +
`formatShockValue` (purple; **red while `state.shockAlertG != null`** — the
2 s fresh-jolt window) + 0–2 g bar. Nulls render "—" and no bar fill — never a
fake 0 (DECIDED; the beacon itself emits nothing for absent sensors).

Inputs: `state.ambientLux`, `state.lastShockG` (Phase A's field),
`state.shockAlertG != null`.

### Tests

**New `core/telemetry/EnvGaugeModelTest.kt`:** log-bar anchor rows (0.1 ⇒
0.0f, 100_000 ⇒ 1.0f, 100 ⇒ 0.5f — pins the decade span; a shifted-span mutant
fails the midpoint), clamp above/below with an in-range control; null ⇒ null
for both fractions (kills a `?: 0f` fake-zero mutant — **the honesty pin**);
`shockBarFraction` 1.0 ⇒ 0.5f, 3.0 ⇒ 1.0f; formatter dash rows (`null ⇒ "—"`)
with non-null controls.

### On-device verification

Beacon live: lux tracks a hand over the beacon's sensor (value + bar move);
thump the beacon ⇒ SHOCK shows the peak red for ~2 s then settles purple and
*persists* (not "—"). Kill the beacon ⇒ both read "—" (and the Phase A status
line explains why). Both orientations.

Commit: `feat(app): COCKPIT-TLM 3 — live ambient+shock tile replaces the speed gauge`.

---

## Phase D — CELL A = this display's real battery; CELL B = Victron placeholder (MAP)

**REVISED by Rob 2026-08-22** (supersedes the earlier pseudo-random `CellGauge`
prop design — that model, its seed, and the `SIM` tag are **dropped**).

The purpose is operational, not decorative: every tablet must live on
permanent vehicle power (the A54 HUD flattened in days on battery —
`SYNC.md`/memory, commit `147b71a`), and a failed charger is currently
invisible until the screen dies. CELL A makes it a glance. These tiles are
**MAP-only (cockpit)**, so CELL A reflects the *hero/driver device's own*
battery — each cockpit tablet shows its own pack; note that in the tile KDoc.

### Core (pure)

**New `core/model/DeviceBattery.kt`:**

```kotlin
/**
 * Percent from ACTION_BATTERY_CHANGED extras, or null when the platform
 * reports garbage (level < 0 or scale <= 0 — seen on emulators and some
 * vendor builds). Null means UNKNOWN and renders "—", never a fake number.
 */
fun batteryPercent(level: Int, scale: Int): Int?

/** The charger-failure threshold: at or below this, CELL A renders as a fault. */
const val DEVICE_BATTERY_LOW_PCT: Int = 20
```

- `batteryPercent` = `level * 100 / scale` (integer arithmetic), result
  coerced into 0..100; invalid inputs ⇒ null. Pure, mutation-tested.
- `DEVICE_BATTERY_LOW_PCT` mirrors the beacon's `BEACON_BATTERY_LOW_PCT = 20`
  (`OpsReadout.kt:50`) — one project-wide convention for "battery low is a
  fault, not a status nicety". (A plugged-in display on vehicle power sits at
  ~100%; anything drifting to 20% has been off charge for a long time.)

### Data (the Android boundary)

**New `data/power/DeviceBatterySource.kt`** — the same thin-source pattern as
`data/sensor/*LocationSource`:

```kotlin
class DeviceBatterySource(private val context: Context) {
    /** Latest device battery percent; null until first reading / on garbage extras. */
    val percent: StateFlow<Int?>   // backed by a MutableStateFlow(null)

    fun start()   // idempotent
    fun stop()
}
```

- `start()` registers a `BroadcastReceiver` for `Intent.ACTION_BATTERY_CHANGED`
  (**no permission needed**; the intent is sticky, so registration returns the
  current battery intent synchronously — fold that first value immediately,
  then each subsequent broadcast, a few per minute). Each intent's
  `BatteryManager.EXTRA_LEVEL` / `EXTRA_SCALE` (defaulting to −1 when absent)
  go through the pure `batteryPercent`; the receiver itself computes nothing.
- Wire in `ZodiacApplication` as `by lazy` (manual-DI convention), `start()`
  alongside the other always-on sources; thread `deviceBatterySource.percent`
  into the ViewModel factory. Broad `catch (Exception)` at the
  register/unregister boundary gets the usual local `@Suppress` + reason.

### State + ViewModel (no new functions — the 21/22 detekt ceiling holds)

- `CockpitUiState` gains `deviceBatteryPct: Int? = null` (39 → 40 fields;
  KDoc: *this device's* battery from `ACTION_BATTERY_CHANGED`; null =
  unknown, render "—").
- `CockpitViewModel` gains a defaulted ctor param
  `deviceBatteryPct: StateFlow<Int?> = MutableStateFlow(null)` (defaulted ⇒
  detekt-free; factory threads it, also defaulted) and one `init` collector:

  ```kotlin
  launch {
      deviceBatteryPct.collect { pct ->
          _uiState.update { it.copy(deviceBatteryPct = pct) }
      }
  }
  ```

### UI

`InstrumentBayScreen.kt` `cellsTile` (`:287-296`) + `cellBar` (`:388-412`):

- **CELL A** — real: `cellBar` fill fraction + percent text from
  `state.deviceBatteryPct`. Green fill/percent (Rob's call — colour table);
  **red fill/percent when `pct <= DEVICE_BATTERY_LOW_PCT`** (the fault the
  tile exists to catch); `null` ⇒ label `CELL A —` with an empty (unfilled)
  bar — never a fake number.
- **CELL B** — placeholder: static 50% fill, **dimmed treatment** so it cannot
  read as live next to the real A: fill in `theme.dim` (not full
  `theme.primary`), and the label carries a pending marker — proposed
  `CELL B · VEHICLE PENDING` at the bar's usual label position, no percent
  text (a number invites belief). **Exact label wording and dimming treatment
  are screenshot-tunable** — verify legibility + the live/pending contrast on
  the S9+ and a Fire and adjust wording (`VEHICLE · PENDING` / `AWAITING
  VICTRON` / dim-outline-only) without re-opening this spec.
- **Victron drop-in shape:** `cellBar` (or its small successor) takes
  `(label, pct: Int?, live: Boolean)` — A calls it `(…, deviceBatteryPct,
  live = true)`, B `(…, 50, live = false)`. When the Victron GX feed lands
  (Modbus-TCP/MQTT per the power thread), B's call site becomes
  `(…, victronSocPct, live = true)` and the pending treatment disappears —
  one call-site change, no tile redesign.
- No animation machinery: CELL A moves only when the battery actually moves
  (the `cellBar` redraw on state change is enough), CELL B never moves. The
  earlier 3-minute animation tick is gone with the random model.

### Tests

**New `core/model/DeviceBatteryTest.kt`:**

| Test | Kills the mutant that… |
|---|---|
| `percent_is_level_over_scale` (50/100 ⇒ 50; 33/200 ⇒ 16 — integer division pinned; 100/100 ⇒ 100) | swaps operands / floats the arithmetic |
| `garbage_inputs_are_null_not_zero` (level −1 ⇒ null; scale 0 ⇒ null; scale −1 ⇒ null) | returns 0 for unknown — **the honesty pin** (a fake 0% would scream dead battery) |
| `result_clamped_into_0_100` (level 120/scale 100 ⇒ 100; valid mid-range passes unchanged as control) | lets a vendor's >scale level escape the axis |
| `low_threshold_is_20` (compile-time pin of `DEVICE_BATTERY_LOW_PCT`; the ≤ boundary exercised at 20 ⇒ low, 21 ⇒ not) | drifts the fault threshold or flips `<=` to `<` |

**Extend `CockpitViewModelTest`:** battery flow emits 78 ⇒
`uiState.deviceBatteryPct == 78`; then emits null ⇒ null again (the fold isn't
latched — an unplug/replug glitch must not freeze a stale percent).

`DeviceBatterySource` itself is the thin Android boundary — receiver
registration is not unit-tested here (no Robolectric harness in `:app`; the
pure percent math and the VM fold carry the tests), it is device-verified.

### On-device verification

- S9+ (or A54) on charger: CELL A tracks the status-bar percent (compare
  numbers), green. Unplug: percent drifts down over a session, still green.
  Drain (or temporarily lower the threshold on a scratch build) to confirm the
  ≤ 20% red state.
- CELL B: pinned at half, visibly dimmer than A, pending label legible at
  arm's length — screenshot A-vs-B side by side; if B reads as a live gauge,
  tune the treatment (that's the screenshot-tunable).
- Fire cockpit-mode sanity: same tile, that Fire's own battery. Both
  orientations.

Commit: `feat(app): COCKPIT-TLM 4 — CELL A = this display's real battery, CELL B = labelled Victron placeholder`.

Update CLAUDE.md's "What is fake" bullet in this commit: throttle trace now
real; **CELL A real (device battery); CELL B a static, labelled Victron
placeholder** — no random values anywhere.

---

## Phase E — `PassengerCard.TELEMETRY` (AFTER PASSENGER-UI Phase 2)

**Hard dependency:** `design/PASSENGER-UI-split-spec.md` Phase 2 adds
`PassengerCard.MAP`, `PassengerInputs.hasMap`, and a `RotationHold` migration
in the same three files this phase touches (`PassengerCard.kt`,
`PassengerData.kt`, `PassengerScreen.kt`). **Do not start Phase E until that
feature's Phase 2 is on `main`;** rebase against its landed shape. The
exhaustive `when`s in `availablePassengerCards` (`PassengerData.kt:24`) and
`passengerCard` (`PassengerScreen.kt:248`) make the compiler enforce both
additions — a merge that drops either branch fails to build, which is the
collision detector.

### Core

- `core/passenger/PassengerCard.kt`: add `TELEMETRY` **after `TRIP`** (rotation
  order = declaration order): the data-flavoured cards (BUMP, TRIP, TELEMETRY)
  cluster, and the ambient SUN/ART close the loop. KDoc: "the vehicle's raw
  feed, for the rider who asks what all the numbers are."
- `core/passenger/PassengerData.kt`: `PassengerInputs` gains
  `hasBeacon: Boolean = false` (post-PASSENGER-UI count 8 ctor params;
  threshold 11 with defaults ignored — fine). Availability branch:
  `PassengerCard.TELEMETRY -> inputs.hasFix || inputs.hasBeacon` — the ticker
  has lines to show if *either* GPS or any beacon channel is alive; with
  neither, the card leaves the rotation (never an empty panel, never a dead
  `NO TELEMETRY` loop for riders).
- `passengerInputs(...)` (`PassengerScreen.kt:446-458`): `hasBeacon =
  state.beaconHealth != null || state.odometer != null || state.ambientLux != null`.

### UI

**New `ui/passenger/PassengerTelemetryCard.kt`** (new file — `PassengerCards.kt`
stays split-don't-grow):

```kotlin
@Composable
fun telemetryCard(state: CockpitUiState, theme: ConceptTheme)
```

- Reuses Phase A's core (`tickerLineAt`, `telemetrySourceStatus`) and — if
  Phase A extracted the inner ticker column as
  `telemetryTicker(inputs, colors, modifier)` (recommended) — reuses that
  composable with a **passenger colour policy**: CHROME→`theme.primary`,
  VALUE→`theme.accent`, and every fault-mapped status (NO_SIGNAL) renders
  `theme.dim`, never red — the passenger screen's "no alarm red" doctrine
  (`PassengerScreen.kt:63-66`) outranks the cockpit mapping here; KDoc says so.
- Framed in `passengerCardFrame` voice: label `VEHICLE TELEMETRY`, the status
  line as the big value (`ON BEACON` etc.), the ticker as the body, footnote
  `LIVE FROM THE FLEET BUS` — true by construction (multicast consumers).
  Larger type than the cockpit box (~14sp lines, ~8 visible) — riders read at
  distance.
- Dispatch: `PassengerCard.TELEMETRY -> telemetryCard(ctx.state, theme)` in
  `passengerCard` (`PassengerScreen.kt:248-257`).
- The passenger state lacks `gpsUsingFallback`? It does not — passenger
  screens render from the same `CockpitUiState` (`CockpitScreen.kt:98-106`),
  so status derivation is identical. On a Fire with the beacon dead the card
  gates itself out via `hasFix`/`hasBeacon` before NO SIGNAL could linger.

### Tests

**Extend `core/passenger/PassengerDataTest.kt`:**

| Test | Kills the mutant that… |
|---|---|
| `telemetry_available_with_fix_only / beacon_only / both / neither` (full 2×2: present, present, present, absent) | turns `\|\|` into `&&` (fails the single-source rows) or hardcodes availability (fails `neither`) |
| `telemetry_rotates_after_trip` (all-available order pins `[..., BUMP, TRIP, TELEMETRY, SUN, ...]`) | enum-position drift — and double-checks the PASSENGER-UI MAP position survived the merge |
| sweep existing all-cards enumeration tests | they must gain TELEMETRY and fail loudly until they do |

Core ticker/status behaviour is **not** re-tested here — Phase A owns it;
reuse means reusing the tests too.

### On-device verification

Fire in passenger mode: TELEMETRY card appears after TRIP with live stepping
values; beacon killed mid-card ⇒ next rotation drops it (with GPS also absent);
no red anywhere on the passenger surface; carousel timing (25 s dwell,
interrupts) unaffected. Samsung cockpit: unchanged.

Commit: `feat(app): COCKPIT-TLM 5 — passenger TELEMETRY card (post PASSENGER-UI)`.

---

## What NOT to do

- Do not add a function to `CockpitViewModel` (21/22, detekt fires at `>=`).
- Do not touch DRIVER, `NmeaParser`'s deliberate VTG/HDG/HDM rejection, the
  routers' lifecycle policies, or anything in `kiosk/`.
- Do not persist the speed trail or the dev-panel visibility.
- Do not interpolate speed gaps, default an absent sensor (or an unknown
  battery) to 0, or render CELL B's static placeholder with the same live
  treatment as the real CELL A.
- Do not start Phase E before PASSENGER-UI Phase 2 is on `main`.

## OPEN QUESTIONS FOR ROB — ALL RESOLVED 2026-08-22 (build these values)

Rob's calls, now encoded throughout the spec body — build exactly as written
above:

1. **Dev-panel trigger** = **5 quick taps on the TELEMETRY box header**
   (≤ 2 s between taps), via the pure `MultiTapCode` — as recommended. Not a
   corner, not a long-press.
2. **Ticker** = **synthesized techy lines from live state** — as recommended.
   Always populated, works on every source including SYSTEM/FAKE; raw NMEA
   stays a possible later dev-panel verbosity toggle, out of scope here.
3. **Speed-graph cadence** = **10 s buckets / 180 points** — as recommended.
4. **Failover plumbing** = **the one-field state fold** of the existing
   `RoutedLocationSource.usingFallback` (one `CockpitUiState` field + one
   `CockpitViewModel.init` collector; no source-layer work, no second source
   of truth) — as recommended.
5. **LOCAL BACKUP colour** = **BLUE with distinct text/glyph** — resolved
   *against* the spec's original purple recommendation. ON BEACON and LOCAL
   BACKUP are both StatusBlue, differing by wording (`● ON BEACON` vs
   `◐ LOCAL BACKUP`); NO SIGNAL stays red, SEARCHING stays dim green. Purple
   remains exclusively a live-data colour. The colour table reflects this;
   manual `◇ SRC <NAME>` follows the same blue-status reading.
6. **The cells** — superseded by Rob's Phase D revision (2026-08-22): the
   pseudo-random prop model and `SIM` tag are gone entirely. **CELL A = this
   device's real battery** (charger-failure watchdog), **CELL B = static 50%
   Victron placeholder with a pending marker** (exact wording/dimming is
   screenshot-tunable). See the revised Phase D.

(Original forks and their reasoning kept in git history — rev 1 of this file —
for the record.)
