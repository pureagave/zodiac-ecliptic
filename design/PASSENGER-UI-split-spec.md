# PASSENGER-UI — automatic passenger role on the Fires + a live map card

**The Fire tablets become passenger displays automatically, by hardware; the
Samsungs keep the full cockpit untouched; the carousel gains an interactive,
track-up "you are here" city map — pan and zoom, auto-follow until touched,
recenter to return.**

Status: SPEC (contract), rev 2 — Phase 2 revised per Rob's review 2026-08-22
(the map card is now **interactive**, not passive; Phase 1 unchanged). Author:
planner session, 2026-08-22. Downstream: an implementation agent treats this
file as the source of truth. Decisions marked DECIDED were made with the
product owner — do not re-litigate them. If this doc and the code disagree
after implementation, the code wins — fix the doc and record the correction in
`SYNC.md`.

## Problem

Every tablet in the fleet boots into the driver cockpit — concept screens,
drive-to bar (HOME/MAN/TEMPLE/BATH/ADDR), the lot. The two Fires are mounted
for *passengers*: they should run the existing passenger carousel
(`ui/passenger/passengerScreen`), which already exists and already removes all
driver chrome — but today a human has to long-press the hidden top-right
corner on each Fire after every reflash or prefs wipe. And the carousel, built
for "where are we / what's that art", has no map: the one card passengers ask
for most is missing.

## Decisions (DECIDED — build exactly this)

1. **Passenger-by-default is a hardware fact, not a setting.** Amazon
   manufacturer ⇒ passenger. The signal is the one the project already trusts
   twice: `BurnInDeviceProfile.visualModulationSupported()`
   (`burnin/BurnInDeviceProfile.kt:17`, `!Amazon`) — the same check that gates
   burn-in visuals and that `MainActivity.kt:135` uses for nav authority.
   The symmetry is deliberate and worth documenting in code: **non-OLED =
   Fire = follower = passenger; OLED = Samsung = authority = cockpit.** The
   S9+ (hero) and A54 (driver) see zero behaviour change; the Fires stop
   rendering the concept screens, which is what removes the five drive-to
   buttons for them — no per-button gating anywhere.
2. **The hidden top-right long-press stays, as an override that wins.**
   (`CockpitScreen.kt:149-160`.) An explicit operator toggle must beat the
   device default *and survive reboots*. Storage must distinguish "never
   toggled ⇒ follow the device default" from "operator said X" — a tri-state:
   persisted `Boolean?`, `null` = auto.
3. **New carousel card: an interactive live map.** Track-up, auto-following,
   centred on the vehicle — **until the passenger touches it**. Touch pan +
   pinch zoom are wanted (riders are not the driver; the "no head-down study"
   rule binds the DRIVER HUD only). Starting view = **2,500 m of ground across
   the short edge** (half the city) — a *starting* zoom, not a clamp. A
   recenter button returns to centred follow, and the card resets to centred
   follow + starting zoom each time it comes back around in the rotation. The
   carousel **holds while the passenger is touching** and resumes a few
   seconds after release. NO drive-to controls, NO route overlay. It obeys the
   card-availability rule (`core/passenger/PassengerData.kt:23`): no GPS fix
   or no loaded map ⇒ the card leaves the rotation, never shows empty.
   Decisions (starting zoom, hold/resume, gating) live in `core/` with tests;
   the camera itself **reuses the cockpit's bounded, tested map-camera stack**
   (see Phase 2's reuse decision) rather than reinventing it.

## Non-goals

- No route overlay, no drive-to affordances, no POI/discovery contacts on the
  passenger map (the GIS-baked map features — streets, fence, plazas, toilets,
  CPNs — are part of the base map and do render).
- **No two-finger twist-rotate on the passenger map.** Heading drives the
  track-up rotation; a manual rotate would fight auto-follow the moment the
  vehicle turns. Pan + zoom only. (The cockpit keeps its rotate untouched.)
- No TILT mode on the passenger map — always top-down (`allowTilt = false`,
  the RADAR precedent).
- No new wire formats, no beacon/Jetson changes, no new `CockpitUiState`
  fields — the map card *reuses* the existing (otherwise idle on a passenger
  device) camera fields; it adds none.
- No UI to *clear* an override back to auto (adb `pm clear` / prefs wipe does
  it; a triple-state gesture is not worth the confusion). Note it in the KDoc.
- No per-card dwell times — dwell stays a uniform 25 s; the touch-hold below
  is a pause, not a per-card duration.

---

## Phase 1 — automatic passenger mode on the Fires (override-preserving)

Wiring only; every piece of new logic is pure and tested. Leaves the app fully
runnable: Samsungs unchanged, Fires boot into the (map-less) carousel.

### Design

The store resolves `override ?: deviceDefault`. The persisted key
`passenger_mode` (`DataStoreCockpitPreferences.kt:159`) is today written
**only** by the operator long-press (`DisplayRoleStore.setPassengerMode`, sole
call site `CockpitScreen.kt:157` via `MainActivity.kt:98`), so any value
already on disk *is* an operator decision — reading "key absent = null = auto"
is migration-free and backwards-compatible:

| Device | Key on disk | Result |
|---|---|---|
| Fire, fresh flash | absent | passenger (auto) |
| Fire, operator forced cockpit | `false` | cockpit (override wins) |
| Samsung, fresh | absent | cockpit (auto) |
| Samsung, operator made it a passenger display | `true` | passenger |
| Any, corrupt prefs (corruption handler → empty) | absent | auto default |

### Files — exact changes

1. **`core/passenger/DisplayRoleStore.kt`** (modify; currently lines 20-37)
   - Constructor becomes:
     ```kotlin
     class DisplayRoleStore(
         private val scope: CoroutineScope,
         private val deviceDefault: Boolean,
         private val read: suspend () -> Boolean?,
         private val write: suspend (Boolean) -> Unit,
     )
     ```
   - `_passengerMode = MutableStateFlow(deviceDefault)` — **not** `false`. The
     DataStore read is async; until it lands, a Fire must already be in
     passenger mode so it never flashes the driver cockpit at riders on boot.
     (The rare inverse — a Fire the operator forced to cockpit shows the
     carousel for the few ms until the read lands — is the right side of that
     trade; say so in the KDoc.)
   - `init { scope.launch { _passengerMode.value = read() ?: deviceDefault } }`
   - `setPassengerMode` unchanged (writes an explicit value — that write is
     what *creates* the override).
   - KDoc: document the tri-state contract — `null` = never toggled = follow
     `deviceDefault`; an explicit toggle persists and wins forever after.

2. **`data/prefs/CockpitPreferences.kt`** (modify)
   - `CockpitPrefsSnapshot.passengerMode: Boolean` →
     `passengerModeOverride: Boolean?`, `DEFAULT` value `null`
     (currently lines 28 / 44). Update the field KDoc (lines 20-27): persisted
     per device; `null` means the role follows the hardware default.
   - Interface `setPassengerMode(enabled: Boolean)` (line 69) unchanged.

3. **`data/prefs/DataStoreCockpitPreferences.kt`** (modify)
   - `read()` line 44: `passengerModeOverride = prefs[KEY_PASSENGER_MODE]` —
     no `?: default` fallback; absence must surface as `null`. **This is the
     load-bearing line of the whole phase.** Key name stays `"passenger_mode"`.

4. **`ZodiacApplication.kt`** (modify; lines 195-201)
   ```kotlin
   val displayRole: DisplayRoleStore by lazy {
       DisplayRoleStore(
           scope = applicationScope,
           // Same hardware signal as nav authority (MainActivity): non-OLED
           // (Amazon/Fire) = follower = passenger display by default. The
           // operator long-press writes an explicit override that wins.
           deviceDefault = !BurnInDeviceProfile.visualModulationSupported(),
           read = { preferences.read().passengerModeOverride },
           write = { preferences.setPassengerMode(it) },
       )
   }
   ```
   (add the `BurnInDeviceProfile` import).

5. **No changes** to `CockpitScreen.kt` / `MainActivity.kt` — the long-press
   already flips from the *effective* value (`onSetPassengerMode(!passengerMode)`,
   `CockpitScreen.kt:157`), which against the new store means "override to the
   opposite of whatever you are now": exactly the desired semantics. The
   hidden corner gestures (log viewer, kiosk exit, role toggle) sit outside
   the passenger/cockpit branch (`CockpitScreen.kt:134-160`) and keep working
   on passenger Fires — required, since the log viewer is the only way into a
   Fire's logs.

6. **`app/src/test/.../CockpitViewModelTest.kt`** — the `NoOpCockpitPreferences`
   fakes (~lines 2015, 2065) implement the interface; they compile unchanged
   (the snapshot field is only renamed, and they build via `DEFAULT.copy`).
   Touch only if the compiler objects.

### Tests (JUnit4 + kotlinx-coroutines-test; every negative gets a positive control)

**New: `app/src/test/.../core/passenger/DisplayRoleStoreTest.kt`** — none
exists today. `runTest` + `advanceUntilIdle`; `read`/`write` are plain lambdas
over local vars, no mocking framework.

| Test | Kills the mutant that… |
|---|---|
| `never_toggled_fire_defaults_to_passenger` (read→null, default=true ⇒ flow true) | drops the `?: deviceDefault` |
| `never_toggled_samsung_defaults_to_cockpit` (read→null, default=false ⇒ false) | hardcodes `true` / inverts the default (positive+negative pair with the row above) |
| `explicit_cockpit_override_beats_fire_default` (read→false, default=true ⇒ **false**) | ignores the override — **the headline behaviour** |
| `explicit_passenger_override_beats_samsung_default` (read→true, default=false ⇒ true) | swaps override/default precedence (control for the row above) |
| `initial_value_is_device_default_before_read_completes` (read parks on a `CompletableDeferred`; assert flow == deviceDefault before completing, resolved value after) | keeps the old `MutableStateFlow(false)` seed (the boot-flash bug) |
| `toggle_writes_explicit_value_and_a_restart_keeps_it` (setPassengerMode(false) ⇒ write recorded; new store whose read returns that value + default=true resolves false) | forgets to persist / re-derives from hardware on reboot |

**Extend `data/prefs/DataStoreCockpitPreferencesTest.kt`** (existing harness:
`TemporaryFolder` + `PreferenceDataStoreFactory`, see lines 23-33):

- `passenger_override_is_null_when_unwritten` — fresh store ⇒
  `read().passengerModeOverride == null`. (The existing
  `returns_default_snapshot_when_unwritten` test at line 28 keeps passing
  because `DEFAULT` now carries `null`.)
- `passenger_override_round_trips_true_and_false` — `setPassengerMode(true)` ⇒
  override `true`; then `setPassengerMode(false)` ⇒ override `false`. The
  false-vs-null distinction is the tri-state; this is the test that a plain
  boolean-with-default implementation cannot pass.

### On-device verification (after gates)

- Samsung (S9+ or A54): boots to cockpit, drive-to bar present, unchanged.
- Fire: boots straight into the carousel, no cockpit flash.
- Fire: top-right long-press ⇒ cockpit; reboot ⇒ **still cockpit** (override
  survived); long-press again ⇒ passenger; reboot ⇒ passenger.
- Fire in passenger mode: bottom-right long-press still opens the log viewer.

### Gates + commit

```bash
JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
```
Unscoped (covers `:beacon` too); paths per `docs/BUILD.md` / the build-env
memory note if not on PATH. Commit
(`feat(app): PASSENGER-UI 1 — Fires default to passenger role, operator override wins`),
append a dated `SYNC.md` entry (newest on top), push `origin/main`.

---

## Phase 2 — the interactive live map card

### Reuse decision: the cockpit's camera stack, wholesale (DECIDED direction, justified here)

The card **reuses** `CockpitViewModel`'s camera API + `MapCameraController` +
`playaMapPanel` + `cockpitTouchInput`, rather than building a parallel
passenger camera. Four reasons:

1. **The camera state is idle on a passenger device.** Passenger tablets
   bypass the concept screens (`CockpitScreen.kt:97-113`), so
   `CockpitUiState.camera` (`core/model/MapCameraState.kt:29`) and
   `MapCameraController` have no other consumer there — the map card becomes
   their sole owner on that device. No contention, no new state.
2. **Every bound is already enforced and tested at the ViewModel layer** —
   the project's stated home for input validation: zoom clamped 0.05–5.0 px/m
   (`MapCameraController.kt:72-80`), pan capped ±5,000 m of the ego
   (`MapCameraController.kt:94-106`), FREE/TRACK_UP invariants grouped in
   `MapCameraState` precisely so they can't drift apart. Reimplementing means
   duplicating tested bounds — the drift this project keeps getting bitten by.
3. **Auto-follow-until-touched already exists — it is `FollowMode`.** In
   TRACK_UP the camera centres on the fix and the ViewModel glues
   `viewRotationDeg` to the ego heading on every fix
   (`CockpitViewModel.kt:253-262`); the first pan flips to FREE with an
   absolute `cameraOverride` (`panBy`, `MapCameraController.kt:90-106`);
   `recenterPan()` (`MapCameraController.kt:154-163` →
   `MapCameraState.recentredOn`) snaps back. Nothing to invent — Rob's
   follow/touch/recenter model is a description of the existing machinery.
4. **`playaMapPanel` already renders all of it** given `(inputs, viewModel,
   style)`: cached viewport honouring `cameraOverride ?: ego`
   (`PlayaMapPanel.kt:469-491`), the projected-map cache, the ego overlay,
   and the touch modifier. The gesture math is pure and already tested
   (`MapGestureMathTest`, `PinchSessionTest` in `app/src/test/.../ui/playamap/`)
   — reuse, do not duplicate.

**One additive extension to shared code** (cockpit call sites unchanged):
`PlayaMapPanelStyle.allowRotate: Boolean = true`; when false the panel wires
`onRotate = {}` — the exact pattern `lockCameraToEgo` already uses to no-op
pan (`PlayaMapPanel.kt:294-299`). The passenger card sets it false: heading
drives track-up, and a manual twist would fight auto-follow the moment the
vehicle turns. (The 5° twist-commit filter in
`MapCameraController.nudgeViewRotation` is not enough on its own — it filters
*incidental* pinch twist, but a deliberate twist would still commit to FREE
rotation.) Detekt-safe: `ignoreDefaultParameters: true` in
`config/detekt/detekt.yml`, so the defaulted flag doesn't count against
`LongParameterList`.

**Tradeoffs accepted (call them out in code comments too):**

- The rev-1 "structurally passive: `(state, theme)` only, no ViewModel, no
  `pointerInput`" enforcement is **gone by design** — the card now needs the
  camera API and gestures. The compensating control is narrower: the card
  reaches the ViewModel *only* through `playaMapPanel`'s wiring (pan/zoom)
  plus `recenterPan` and `setPixelsPerMeter` for reset — never the nav, GPS,
  or concept APIs — and renders no control other than the recenter button.
  Review-enforced, stated in the file's KDoc.
- **Zoom persists as the cockpit zoom preference.** `setPixelsPerMeter`
  writes `KEY_PIXELS_PER_METER` (`MapCameraController.kt:79`). A rider's
  pinch on a passenger Fire therefore rewrites that device's persisted
  cockpit zoom. Accepted: harmless on a dedicated passenger tablet, bounded
  always, recoverable by one pinch if the operator ever flips it to cockpit —
  and not worth a parallel non-persisting zoom path.
- The cockpit's 60 s idle auto-revert (`AUTO_RECENTER_MS`,
  `core/model/FollowMode.kt:20`) runs on the passenger card too. It is
  harmless-to-helpful (a walked-away-from card re-follows within a minute
  even mid-dwell) and is *not* the carousel-resume mechanism — that has its
  own, much shorter grace below.
- `playaMapPanel` includes the map-load-error RETRY overlay
  (`PlayaMapPanel.kt:365-372`). Unreachable here: a failed load means
  `playaMap == null`, and the `hasMap` gate has already removed the card from
  the rotation. Assert that reasoning in a comment, don't add code.

### Core decisions (pure, tested, no Android imports)

**Enum: `core/passenger/PassengerCard.kt`** — add `MAP` immediately after
`WHERE` (rotation order = enum declaration order, `PassengerData.kt:24`):
address card, then the map of it — the two "where are we" answers run
back-to-back. KDoc it. The exhaustive `when`s in `availablePassengerCards` and
`passengerCard` make the compiler enforce the dispatch additions below.

**Gating: `core/passenger/PassengerData.kt`** —
- `PassengerInputs` gains `hasMap: Boolean = false` (7 ctor params; detekt
  threshold 11, fine).
- Availability branch: `PassengerCard.MAP -> inputs.hasFix && inputs.hasMap`.
  Both gates are load-bearing: no fix ⇒ nowhere to centre (mirrors WHERE);
  no loaded map (`CockpitUiState.playaMap == null`) ⇒ a blank rectangle. A
  failed map load removes the card rather than showing a retry affordance.
- Note in the KDoc: `hasFix` is `state.egoFix != null`
  (`PassengerScreen.kt:453`), which is **source-agnostic** — a Fire with no
  GNSS of its own running on the beacon's NET fix counts, by construction.
  That is the normal fleet case and requires no code.

**New file `core/passenger/PassengerMapCardModel.kt`** — the card's pure
decisions (the viewport itself now comes from `playaMapPanel`; what's left
pure is the starting zoom and the touch-hold):

```kotlin
/** Ground metres across the canvas's short edge at card entry — a START, not a clamp. */
const val PASSENGER_MAP_START_SPAN_M: Double = 2_500.0

/** Carousel resumes this long after the last touch ends. See open question 1. */
const val MAP_TOUCH_RESUME_GRACE_MS: Long = 10_000L

/** null while the canvas is unsized; else min(w,h)/span, coerced into the camera's zoom bounds. */
fun passengerMapStartZoom(widthPx: Int, heightPx: Int): Double?

/** Hold the carousel: a finger is down, or one lifted less than [graceMs] ago. */
fun mapTouchHoldActive(
    pointerDown: Boolean,
    lastTouchMs: Long?,
    nowMs: Long,
    graceMs: Long = MAP_TOUCH_RESUME_GRACE_MS,
): Boolean
```

- Start zoom on a Fire HD 10 (1920×1200): `1200 / 2500 = 0.48 px/m` — half
  the city across the short edge, inside the camera bounds
  (`MapCameraState.MIN/MAX_PIXELS_PER_METER`), and above the plaza/art-major
  label zoom gates (`ui/playamap/MapLabels.kt:24-28`); street/CPN names
  appear as the rider zooms in. Coerce with the *same* constants the
  controller clamps with — one source of bounds.
- `mapTouchHoldActive` semantics: `pointerDown` alone holds (a finger resting
  motionless produces no pan/zoom callbacks — the raw pointer state is the
  honest signal); after release, the hold persists while
  `nowMs - lastTouchMs < graceMs` (strict `<`: at exactly the grace boundary
  the hold is over). Time is the carousel's own tick clock, not wall time.

**Engine: `core/passenger/CardRotation.kt` — graded hold.** The engine
*already has* the hold input this feature needs (`view(nowMs, available,
hold)`, `CardRotation.kt:59-101`: pauses the dwell clock, resumes the
remaining dwell on release — exactly the resume behaviour Rob asked for). But
an existing test **deliberately pins that interrupts barge through a hold**
(`CardRotationTest.an_event_still_interrupts_a_held_card`, ~line 176: a bump
the passenger just felt still gets its moment over the parked-art hold). A
map being actively touched must NOT be yanked away by a street-crossing
interrupt — so the two holds are genuinely different strengths, and the
extension is to grade the existing mechanism, not bolt on a parallel one:

```kotlin
enum class RotationHold {
    /** No hold — normal rotation. */
    NONE,

    /** Pause the dwell clock; interrupts still barge in (the parked-art hold, pinned behaviour). */
    SOFT,

    /** Pause the dwell clock AND defer interrupts — a finger is on the glass. */
    HARD,
}
```

- `view(nowMs, available, hold: RotationHold = RotationHold.NONE)` replaces
  the boolean. Existing call sites and tests migrate mechanically
  (`hold = true` → `RotationHold.SOFT`) — a visible, deliberate migration
  that keeps the pinned SOFT semantics intact for ART.
- Under HARD, the pending-interrupt block is skipped **without clearing the
  interrupt**: it stays armed with its original expiry. Release within the
  interrupt's window ⇒ it shows (the event is still fresh); expiry during the
  hold ⇒ it never shows (nobody re-raises stale events). The dwell-pause
  behaviour of HARD is identical to SOFT.

### Rendering + wiring (draw and plumb — no decisions)

**New file `ui/passenger/PassengerMapCard.kt`** (new file, not an addition to
`PassengerCards.kt` at 248 lines — split, don't grow):

```kotlin
@Composable
fun mapCard(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
    /** Raw touch telemetry for the carousel hold; the decision is core's. */
    onMapTouch: (pointerDown: Boolean) -> Unit,
)
```

- Full-bleed `Box`; one small top-centre caption `"BLACK ROCK CITY"`
  (`theme.primary`, `RetroFont`, letter-spaced — the card-label voice without
  duplicating WHERE's "YOU ARE HERE").
- **The map is one `playaMapPanel` call** (`ui/concepts/PlayaMapPanel.kt:235`)
  with `MapUiInputs.from(state)` and a passenger style: palette copied from
  `InstrumentBayScreen.kt:53-68`'s green-family values but `pointStyle = DOT`,
  `labelsEnabled = true`, `crtBeam = true`; `egoStyle = TRIANGLE`,
  `egoColor = theme.accent` (purple = live data, per the colour system);
  `allowTilt = false` (always top-down, the RADAR precedent);
  `allowRotate = false` (the new flag); everything else default. Pan and
  zoom flow through the panel's existing wiring into the bounded
  `viewModel.panBy` / `setPixelsPerMeter` — the card adds no gesture math.
- **Touch telemetry**: a transparent observer `pointerInput` layered over the
  panel that watches raw pointer down/up (unconsumed observation — it must
  not steal events from `cockpitTouchInput`) and calls `onMapTouch(true)` on
  first finger down, `onMapTouch(false)` on last finger up. Plumbing only —
  it decides nothing; the hold decision is `mapTouchHoldActive` in core.
- **Recenter button**: reuse `recenterButton`
  (`ui/concepts/RecenterButton.kt:29`) as-is — it already renders FREE =
  blue-ringed (status: recenter available) / TRACK_UP = dim, which is the
  semantic colour system doing the "am I following?" talking. Overlay it
  bottom-end; `followMode = state.camera.followMode`; `onClick =` recenter
  reset (below) *plus* `onMapTouch(false)`-style touch stamp so the grace
  window runs from the recenter tap too (Rob: resume "after touch ends *and
  after it recenters*").
- **Reset-on-entry**: entering the MAP branch of the card dispatch composes
  `mapCard` fresh (a `when` branch is its own composition slot), so a
  `LaunchedEffect(Unit)` in `mapCard` runs once per carousel entry: call
  `viewModel.recenterPan()` immediately, and apply
  `viewModel.setPixelsPerMeter(passengerMapStartZoom(w, h))` once the panel's
  measured size is first known (`onSizeChanged`; latch so it applies exactly
  once per entry — `passengerMapStartZoom` returns null until sized). The
  recenter button's onClick performs the same two calls (see open question 3).

**Modify `ui/passenger/PassengerScreen.kt`:**
- `passengerScreen` gains a `viewModel: CockpitViewModel` parameter, threaded
  to `mapCard` only. (`CockpitScreen.kt:98` has it in scope — one-line pass.)
- `passengerInputs(...)` (line 446): add `hasMap = state.playaMap != null`.
- `passengerCard` dispatch (line 248): add
  `PassengerCard.MAP -> mapCard(ctx.state, viewModel, theme, onMapTouch)`.
- **Hold wiring in the existing ticker** (`PassengerScreen.kt:143-152` — the
  single place `rotation.view()` is legally called): hoist
  `mapPointerDown: Boolean` + `mapLastTouchMs: Long?` state; `onMapTouch`
  updates them, stamping `lastTouchMs` from the ticker's own `tickMs` clock
  (same clock `CardRotation` runs on — never wall time). Per tick:

  ```kotlin
  val hold = when {
      view?.card == PassengerCard.MAP &&
          mapTouchHoldActive(mapPointerDown, mapLastTouchMs, tickMs) -> RotationHold.HARD
      isStopped && hasArt && view?.card == PassengerCard.ART -> RotationHold.SOFT
      else -> RotationHold.NONE
  }
  view = rotation.view(tickMs, cards, hold = hold)
  ```

  The two arms are mutually exclusive by construction (they branch on the
  *current* card), so this is dispatch, not new logic. Street-crossing
  interrupts still target WHERE, untouched — under a HARD hold they defer.

### Tests

**Extend `core/passenger/CardRotationTest.kt`** — the engine change is the
mutation-sensitive heart of this phase:

| Test | Kills the mutant that… |
|---|---|
| `hard_hold_defers_an_interrupt` (interrupt during HARD ⇒ current card, not the interrupt card) | drops the `hold != HARD` guard — **the headline behaviour** |
| keep `an_event_still_interrupts_a_held_card`, migrated to SOFT | treats SOFT like HARD (the pinned parked-art behaviour is the positive control proving the guard discriminates) |
| `a_deferred_interrupt_shows_after_release_within_its_window` (interrupt at t, HARD until t+2 s, release ⇒ interrupt shows) | clears the interrupt on hold instead of deferring it |
| `an_interrupt_that_expires_during_a_hard_hold_never_shows` (HARD past `interruptUntilMs`, release ⇒ rotation card) | extends the interrupt window while held |
| `hard_hold_pauses_dwell_like_soft` (HARD across several dwells ⇒ same card; release ⇒ remaining dwell resumes, mirroring the existing SOFT tests at lines 140-173) | wires HARD as NONE for the dwell clock |

Existing `hold = true` tests migrate to `RotationHold.SOFT` mechanically; any
behaviour change there is a bug, not a migration.

**New `core/passenger/PassengerMapCardModelTest.kt`:**

| Test | Kills the mutant that… |
|---|---|
| `start_zoom_maps_short_edge_exactly` (1920×1200 ⇒ 0.48 exactly; 1200×1920 ⇒ 0.48 — orientation-independent) | keys the span to width/max, or edits `PASSENGER_MAP_START_SPAN_M` |
| `start_zoom_is_coerced_into_camera_bounds` (absurd canvas ⇒ result within MIN/MAX_PIXELS_PER_METER; plus an in-range value passes through *unchanged* as the positive control) | drops the coercion, or coerces everything |
| `start_zoom_null_until_sized` (0×n, n×0 ⇒ null) | lets a divide-by-zero zoom through |
| `finger_down_holds_regardless_of_history` (pointerDown=true, lastTouchMs=null ⇒ true) | derives hold only from callbacks (the motionless-finger case) |
| `grace_window_boundary_is_exclusive` (elapsed = grace−1 ⇒ true; elapsed = grace ⇒ false) | flips `<` to `<=` or off-by-ones the grace |
| `no_touch_no_hold` (down=false, lastTouchMs=null ⇒ false) | hardcodes true (negative control) |

**Extend `core/passenger/PassengerDataTest.kt`** (unchanged from rev 1):

- `map_requires_both_fix_and_map` — full 2×2 truth table over
  `(hasFix, hasMap)`: MAP present only for `(true, true)`, each false case
  asserted absent (an `||` mutant and a dropped-`hasMap` mutant each fail a
  different row).
- `map_rotates_directly_after_where` — all-available list starts
  `[WHERE, MAP, ...]`; pins the enum-order decision.
- Sweep existing tests that enumerate the all-available card list — they must
  gain MAP and will fail loudly until they do.

**Deliberately NOT re-tested:** pan/zoom bounds and follow-mode transitions
(pinned by the existing `CockpitViewModelTest` camera coverage), pinch/drag
math (`MapGestureMathTest`, `PinchSessionTest`) — reuse means reusing the
tests too. `mapCard` itself is not unit-testable (`:app` has no Compose UI
harness): its decisions live in the tested core above, its rendering is
screenshot-verified below. Same doctrine as RES-P2-1 and `SurroundRing`.

### On-device verification

- Fire, beacon live: MAP card appears right after WHERE; streets + labels +
  fence render; purple ego centred, pointing up; map rotates under it as the
  vehicle turns. Screenshot both orientations — ~2.5 km of ground across the
  short edge in each.
- **Pan**: drag — map detaches (recenter ring turns blue), ego slides free of
  centre; pan is capped ±5 km from the ego. **Zoom**: pinch in/out, bounded
  at 0.05/5.0 px/m; zoom persists across pan but resets on card re-entry.
- **No rotate**: a deliberate two-finger twist does nothing (cockpit still
  rotates — check on the S9+ that the flag didn't leak).
- **Hold**: touch and keep interacting past the 25 s dwell — the card stays.
  Stop touching — the carousel advances ~10 s after the last touch. Cross a
  street *while* touching — no interrupt yank; release and the WHERE
  interrupt appears only if its 8 s window hasn't lapsed.
- **Recenter**: tap the button — follow + track-up return (ring dims), grace
  window runs from the tap.
- **Re-entry reset**: pan/zoom somewhere odd, let the carousel move on, wait
  for MAP to come around — it returns centred, following, at start zoom.
- Kill the beacon: MAP and WHERE leave the rotation; restart: they return.
- S9+ sanity: cockpit unchanged (drive-to bar, map gestures incl. rotate);
  flip to passenger by long-press, confirm the card on OLED, flip back.

### Gates + commit

Same unscoped gate command as Phase 1. Suggested split into two runnable
commits if the implementer prefers: (2a) core — `RotationHold` +
`PassengerMapCardModel` + `MAP` enum/gating with all tests; (2b) UI — the
card, the panel's `allowRotate` flag, screen wiring. Commit
(`feat(app): PASSENGER-UI 2 — interactive track-up live-map card in the carousel`),
`SYNC.md` entry, push. If the passenger split is deemed shipped after this
phase, give `README.md` its one-line feature mention (public snapshot, not a
progress log).

**Amended 2026-08-22:** Phase 2's code exists uncommitted in the working tree
and must **ship together with the Phase 2b starvation fix below** — the map
card without 2b never reaches the screen in the city, which hardware
measurement proved same-day.

---

## Phase 2b — interrupt-starvation fix (ships WITH Phase 2)

### The measured problem

On hardware (2026-08-22, fake ego): parked at the Man among 275+ placed
pieces, the ART approach interrupt — 400 m radius, 160° cone
(`core/passenger/ArtApproach.kt:89-95`), **re-armed every tick** by design
(`PassengerScreen.kt:137-142`; the re-arm is what holds the card through a
pass) — keeps `interruptUntilMs` permanently ahead of the clock, so the ART
card shows *forever* (6+ consecutive captures, all ART). Driving the street
grid, each crossing re-arms WHERE the same way. Since the vehicle is in or
near the city essentially always, the steady-rotation cards — **MAP, SUN,
steady-WHERE** — get no airtime at all. The MAP card could only be reached by
driving ~6 km into empty deep playa or by touching the screen (the HARD hold,
which works as designed). An endless art/crossing slideshow is not the
carousel; a passenger must reliably *see* the map come around. (DECIDED —
Rob, 2026-08-22: cap the interrupts' monopoly; do not kill their timeliness.)

The bug is structural, not a tuning error: `interruptWith` re-arms extend the
display without bound, and nothing in `CardRotation` owes the steady rotation
anything. The fix therefore lives **entirely in `CardRotation`** — no changes
to the interrupt sources, `ArtApproach`, or the screen's ticker; ART's
every-tick re-arm *stays*, because it is the correct freshness signal (it is
what brings ART back after each guaranteed window while a piece is still
ahead).

### Chosen design: occupancy cap + guaranteed steady window

Two rules, two timestamps, on top of the existing (uncommitted Phase 2)
engine — the HARD-hold defer machinery is the model, and both grades of
`RotationHold` keep their exact semantics:

1. **Occupancy cap.** The interrupt *layer* (any card shown with
   `CardReason.INTERRUPT`, across re-arms **and across card switches** — the
   cap is on the layer, not per card, so an ART↔WHERE alternation cannot
   collude past it) may hold the screen continuously for at most
   `interruptMaxMs`. Default **12 s** = 1.5× the 8 s display: a run of bumps
   still "holds the gauge up" meaningfully longer than one display, but not
   forever.
2. **Guaranteed steady window.** On **every** transition of the layer from
   shown to not-shown — natural expiry, cap trip, data loss, any reason — the
   engine grants `steadyGuaranteeMs` of interrupt-free steady rotation.
   Default **25 s = `DEFAULT_DWELL_MS`**: one full card's dwell. During the
   window, arms are **deferred, not deleted** (identical semantics to the
   HARD hold): the pending interrupt keeps its own `interruptUntilMs` expiry,
   so at window end a still-fresh event shows and a stale one has silently
   expired — nobody re-raises stale events, nobody drops fresh ones.

Steady-state behaviour in the measured worst case (continuous re-arm at the
Man): 12 s ART → 25 s steady card → 12 s ART → … — interrupt duty ≤ ~32%,
steady ≥ ~68%; one-shot storms (street grid) bound at 8/33 ≈ 24%. The
rotation clock keeps running beneath interrupt displays (the existing
catch-up loop, `CardRotation.kt:116-122`), so successive windows land on
successive/precessing cards; with all eight cards available, MAP gets a full
uninterrupted dwell roughly every 4–5 minutes worst case — against **never**
today. First interrupt after quiet still shows on the next tick (no window
exists until an interrupt has actually displayed), so timeliness out of the
gate is unchanged.

**Alternatives considered and rejected:**

- **(c) same-source dedup** (don't re-arm for the same piece/street): fixes
  only the parked case. Driving the Esplanade, every piece and every street
  is *genuinely new* — the slideshow continues with dedup in place. It also
  needs event identity plumbed into the engine (`interruptWith` has no
  payload), and parked heading-jitter can flip the chosen piece anyway.
- **(b) sliding-window duty budget** (leaky bucket / ring buffer): reaches
  the same bound as the cap+window with strictly more machinery and a
  hysteresis nobody can eyeball from a log line. Two timestamps beat an
  accounting scheme that has to be explained twice.

### The `CardRotation` change (pure; nothing else touches)

- Constructor gains two defaulted params, mirroring `dwellMs`/`interruptMs`:
  `interruptMaxMs: Long = INTERRUPT_MAX_CONTINUOUS_MS` (new companion const,
  12 000) and `steadyGuaranteeMs: Long = STEADY_GUARANTEE_MS` (new companion
  const, = `DEFAULT_DWELL_MS`). Injectable for tests, like everything else
  in this class.
- Two private fields: `interruptShownSinceMs` (when the layer last *took*
  the screen; meaningful only while visible) and `steadyUntilMs` (end of the
  current guarantee window; 0 = none), plus an `interruptVisible` flag.
- `view()` — the pending-interrupt block (currently guarded by
  `hold != RotationHold.HARD`, `CardRotation.kt:93-106`) gains the layer
  gate. Show the pending interrupt only when **all** hold: not HARD-held
  (unchanged), `nowMs >= steadyUntilMs` (window over — `>=`: the boundary
  tick is free), fresh (`nowMs < interruptUntilMs`, unchanged), in
  `available` (unchanged), and `!interruptVisible || nowMs −
  interruptShownSinceMs < interruptMaxMs` (under the cap; at exactly the cap
  the layer releases).
- Bookkeeping is one uniform rule: when this call shows the layer and the
  previous call didn't ⇒ `interruptVisible = true; interruptShownSinceMs =
  nowMs`. When this call does *not* show the layer and the previous call did
  ⇒ `interruptVisible = false; steadyUntilMs = nowMs + steadyGuaranteeMs`.
  No transition, no bookkeeping. (A stale arm that never displayed grants
  **no** window — only actual occupancy buys the rotation its recovery time.
  The empty-`available` blackout path may fire the transition; harmless,
  untested by design.)
- One deliberate semantic nuance, documented in the KDoc: during a guarantee
  window, interrupts defer even past a **SOFT** hold — the window is a
  property of the layer, orthogonal to hold grades. The pinned
  `an_event_still_interrupts_a_held_card` behaviour is untouched *outside* a
  window (its setup has no prior interrupt display, so it keeps passing
  as-is — verify, don't edit it).
- `interruptWith` is unchanged. Re-arms during a window or past the cap
  land in the same pending slot with a fresh expiry — deferral does the rest.
- If `view()`'s cyclomatic count trips detekt, extract the gate into a
  private `interruptAllowed(nowMs, hold)` — split the decision, don't bump
  the threshold.

### Tests (`CardRotationTest` additions — every one paired)

| Test | Kills the mutant that… |
|---|---|
| `a_continuously_rearmed_interrupt_is_capped_then_yields_a_full_steady_window` (re-arm ART every tick; INTERRUPT until exactly the cap, then ROTATION for the full window despite re-arms, then INTERRUPT again) | drops the cap, drops the window, or lets re-arms pierce the window — **the measured Man-parking case, and the headline** |
| `alternating_interrupt_cards_share_one_occupancy_cap` (ART/WHERE alternating every 2 s cap out together at `interruptMaxMs` total) | resets occupancy on a card switch (the collusion loophole) |
| `a_first_interrupt_after_quiet_shows_immediately` (steady rotation, no prior display ⇒ interrupt shows next tick) | positive control — locks the layer out permanently or seeds a window at t=0 |
| `a_one_shot_interrupt_earns_a_window_when_it_expires` (single WHERE: 8 s display, expiry ⇒ ROTATION; a fresh arm during the window defers to window end) | grants the window only on cap trips (the street-grid storm would still monopolize) |
| `an_arm_that_never_displayed_grants_no_window` (arm during a window, let it go stale ⇒ at window end a new fresh arm shows immediately) | chains windows off stale drops, locking interrupts out forever — negative control for the window-granting rule |
| `a_deferred_arm_shows_at_window_end_iff_still_fresh` (arm at end−2 s ⇒ shows at window end; arm at start+1 s, one-shot ⇒ never shows) | deletes deferred arms, or extends their freshness while deferred |
| `window_boundary_is_inclusive_release` (at exactly `steadyUntilMs` a fresh pending shows; at exactly `interruptShownSinceMs + interruptMaxMs` the layer releases) | off-by-ones either boundary |
| `soft_hold_defers_during_a_guarantee_window` (capped burst, then SOFT hold + fresh arm ⇒ held card stays until window end) | exempts SOFT from the window — pairs with the untouched pinned barge test as its positive control |
| `hard_hold_and_cap_compose` (existing HARD tests pass unchanged; new: release from HARD after a window has lapsed with a fresh pending ⇒ it shows) | breaks HARD's defer while adding the second defer path |
| `the_rotation_lands_where_the_clock_says_after_a_capped_burst` (index after burst + window matches elapsed dwells — extends the existing catch-up tests) | freezes the dwell clock inside the new bookkeeping |

All pure, all on the injected clock — no Compose, no device, exactly why this
engine is a pure class.

### On-device verification (replaces nothing — adds to Phase 2's list)

- Park the fake ego at the Man: ART alternates with steady cards (~12 s /
  ~25 s), and the MAP card appears on its own within a few minutes —
  the capture that today shows 6+ consecutive ART frames must show the
  alternation.
- Drive the street grid: WHERE crossings still flash promptly, but steady
  cards visibly cycle between them.
- Touch the map mid-window and mid-burst: HARD hold still defers everything;
  release behaves per Phase 2.
- Deep-playa sanity: far from art and streets, the rotation is pure steady —
  the fix must be invisible when there is nothing to fix.

### Gates + commit

Phase 2 is uncommitted; **2 and 2b gate and commit together** (2b's engine
change is what makes 2's card reachable). Run the unscoped gate, then either
one commit or the 2a/2b/2c sequence — with the starvation fix folded into
the core commit — ending
(`feat(app): PASSENGER-UI 2 — interactive live-map card + interrupt airtime guarantee`),
`SYNC.md` entry (including the measured starvation numbers), push.

### Fork noted (decided by recommendation unless Rob overrides)

**Should a fresh *different-card* interrupt pierce the guarantee window?**
(E.g. a bump the passenger just physically felt, arriving mid-window.)
Piercing keeps bumps timelier — but in the city near-everything is "fresh"
(every street, every piece), which is precisely the measured failure:
ART↔WHERE alternation would re-create the slideshow through the loophole.
**Recommended and specced: the window is inviolable.** Worst-case cost: a
fresh event waits ≤ 25 s and shows only if still within its own 8 s
freshness — a bump card arriving late is garnish delayed; the map never
appearing is the product failing. One constant (`steadyGuaranteeMs`) tunes
the trade on the vehicle.

---

## Constraints recap (from CLAUDE.md — the implementer is bound by these)

- Colour system: black bg; green chrome/labels; blue status (the recenter
  ring in FREE is exactly this); **purple for live data** (the ego marker,
  the map's landmark family); red faults only; amber banned. The map palette
  above conforms; don't invent colours.
- `screenOrientation="fullUser"`: the card must reflow both orientations —
  the short-edge start-span rule is how.
- No new logic in a composable. The card intentionally holds a ViewModel
  reference now, but its own code is dispatch and plumbing: gestures go
  through the panel's tested wiring, the hold/zoom/defer *decisions* are the
  pure core functions above. If review finds a branch in `mapCard` that isn't
  "draw or forward what core decided", move it to `core/passenger` and test
  it. Raw pointer down/up observation is plumbing, not a decision.
- Detekt fires at `>=`; split files rather than bump thresholds (hence
  `PassengerMapCard.kt` and `PassengerMapCardModel.kt` as new files;
  `PlayaMapPanelStyle.allowRotate` rides on `ignoreDefaultParameters: true`).
- Tests must be able to fail: every table above pairs positives with
  negatives; run each new test against a deliberately broken implementation
  once if in doubt.
- Phased commits, each leaving the app runnable; gates green before each;
  push after each phase.

## OPEN QUESTIONS FOR ROB — ALL RESOLVED 2026-08-22 (every one = the recommended default)

Rob's calls: (1) resume grace **10 s**; (2) zoom-out floor **keep the cockpit
bounds** (~24 km, no passenger-only clamp); (3) recenter **also resets zoom** to
the start span; (4) recenter button **always visible**, state-coloured; (5)
deferred interrupts **show after release if their 8 s window survives**, else
never. The spec body already encodes these values — build exactly as written.

(Span and passivity were decided earlier — 2,500 m starting view, interactive
pan + zoom. Original open forks kept below for the record.)

1. **Resume grace after the last touch.** How long after the finger lifts
   before the carousel may advance? One constant
   (`MAP_TOUCH_RESUME_GRACE_MS`). *Recommended: 10 s — long enough to read
   the spot you panned to, short enough that an abandoned card doesn't stall
   the rotation; the dwell clock then resumes the card's remaining time, so
   the total lingering is bounded either way.*
2. **Zoom-out floor.** Reusing the cockpit bounds means a rider can zoom out
   to 0.05 px/m — ~24 km across a Fire HD 10, mostly blank playa. A
   passenger-only floor at roughly whole-city (~6 km span ⇒ ~0.2 px/m) would
   need a small clamp seam between the panel and `setPixelsPerMeter`.
   *Recommended: keep the cockpit bounds for v1 — pure reuse, no new seam;
   recenter/re-entry recovers any lost rider. Revisit only if it happens on
   the vehicle.*
3. **What the recenter button resets.** Cockpit semantics are pan + rotation
   only (zoom untouched). For riders the button is the escape hatch, and
   "back to normal" plausibly includes zoom. *Recommended: on the passenger
   card, recenter = pan + rotation + start zoom (one extra
   `setPixelsPerMeter` call in its onClick); the cockpit's own button is
   untouched either way.*
4. **Recenter button visibility.** The cockpit button is always visible —
   dim ring when following, blue when panned free. Auto-hiding it when
   centred is possible but costs discoverability (a rider who never saw it
   won't know the way back). *Recommended: keep the cockpit behaviour —
   always visible, state-coloured; it is already the tested look.*
5. **Deferred street/bump interrupts after release.** As specced, an
   interrupt raised while a rider is touching shows *after release if its
   8 s window hasn't lapsed*, else never. The alternative (drop all
   interrupts raised during a hold) is one line. *Recommended: as specced —
   a bump the vehicle just took is still worth its moment three seconds
   later; an expired one stays expired.*
6. **(Phase 2b) Is the guaranteed steady window inviolable, or may a fresh
   different-card interrupt pierce it?** See Phase 2b's "Fork noted".
   *Recommended (and specced): inviolable — in the city near-everything is
   "fresh", so piercing re-creates the measured slideshow; the 12 s / 25 s
   constants are the tuning knobs if the balance feels wrong on the
   vehicle.*
