# Engineering notes

Things that were measured rather than assumed, and mistakes worth not repeating. If you change code near any of these, read the relevant section first — most of them look like arbitrary constants and aren't.

---

## Calibration constants

### `OnsetDetector.GROUP_DELAY_HOPS = 0.60`

The analysis chain reports onsets **early**, from two independent causes that together put every beat 57 ms ahead of the music — most of the accuracy budget, spent on nothing.

1. **Window centring.** Timestamping an STFT frame from its window *start* reports each onset half a window early: 46 ms at a 2048-point window and 22,050 Hz. Frames are now centred (`Stft.compute` reads from `f*HOP - N_FFT/2` with reflect padding at the edges), so frame `f` genuinely sits at `f*hopMs`.
2. **Backward difference.** Spectral flux is `mag(f) - mag(f-1)`, which peaks while magnitude is still *rising* — before the transient reaches the window centre. This constant compensates for the remainder.

The value is measured with **isolated impulses** in `ChainLatencyTest`, deliberately not fitted to music, so it cannot encode a quirk of the drum fixtures. After correction, mean absolute beat error on clean material is **6–8 ms** and the mean chain latency is 0.01 ms. That test fails if a change to the window shape, hop size or flux definition moves the delay.

### `TempoEstimator.COVERAGE_TARGET = 0.70`

Autocorrelation reliably prefers **half-time** on any pattern that alternates timbres. Kick on 1 and 3 with snare on 2 and 4 correlates better at two beats than at one, because at two beats kick lands on kick. A log-normal prior centred at 120 BPM does not overcome it — the result is a show that flashes on every other beat and reads as broken rather than merely wrong.

Octave correction picks the **slowest** period whose grid covers at least 70% of above-median onset peaks, on the principle that a beat grid should not systematically leave strong onsets unexplained. Scanning slowest-first corrects errors in **both** directions: a half-time pick fails coverage and falls through to the true period, and a double-time pick is beaten by the slower candidate tested before it.

Measured separation across the fixtures:

| Ground truth | Half-time | Correct | Double |
| --- | --- | --- | --- |
| 90 BPM | 0.40 | 0.79 | 1.00 |
| 120 BPM | 0.50 | 0.98 | 1.00 |
| 128 BPM | 0.50 | 0.98 | 1.00 |
| 140 BPM | 0.50 | 0.98 | 1.00 |

Half-time never exceeds 0.50; correct never falls below 0.79.

**A rejected approach, recorded so it isn't retried:** comparing onset strength on the grid against the midpoints between grid positions. Its separation was 0.26 (correct) versus 0.33 (half-time) — a real gap, but one that depended on the relative loudness of kick and snare in the fixtures rather than on anything about music. It would have been a threshold fitted to a synthetic quirk.

### Comb sampling must use float periods

500 ms is 21.53 frames at the default hop, not 22. Sampling a grid at an integer frame lag accumulates rounding error and slides completely off the music within a few hundred beats — which produced nonsense measurements (a grid scoring 5× higher at half-time than at the true tempo) before it was spotted. Every grid position is now computed as `phase + k*period` in floats and rounded independently.

---

## Bugs worth remembering

### Screen output was exempt from the flash cap

`Cue.isFlash` originally counted only `Curve.STEP`, but the show compiler emits `Curve.DECAY` for every screen cue — instant on, then fade. Harding criteria measure the **rising** luminance transition, and a decay's rise is a hard step, so those always should have counted.

This was invisible while the torch was enabled, because the torch cue at the same instant was being counted instead. It became reachable the moment a user disabled the torch and ran screen-only. Now only `Curve.RAMP` is exempt.

### Flash rate must be counted in instants, not cues

Fixing the above naively would count a torch cue and a screen cue on the same beat as two flashes, and the limiter would delete half of every show while changing nothing about what a person sees. Rate is now measured over **flash instants** — cues within `FLASH_MERGE_MS` (25 ms) are one flash — and when an instant has to be dropped, every channel in it goes together. Dropping only the torch and leaving the screen firing would look like a rate reduction without being one.

### The PRD specified a safety rule that was wrong

An early draft of the PRD called for a hard limit of 5 flashes per burst followed by a rest. Implementing it deleted **235 of 240 cues at 180 BPM**. Checking the source showed it was a mis-transcription of the *moderate-flash allowance* in the guidance, read as a restriction. WCAG 2.3.1 permits three flashes per second with no limit on duration. The limiter now reports the longest at-cap stretch as advice and deletes nothing on account of it.

### `AudioTrack` buffer sizes are in bytes and must be frame-aligned

`getMinBufferSize` returns **bytes**. A fallback written as `rate / 4` is a *frame* count, and off by a factor of four. Worse, `AudioTrack.Builder` rejects any size that is not a whole number of frames with an opaque `UnsupportedOperationException: Invalid audio buffer size` — 22050 bytes is 5512.5 frames of mono float, and that is a hard crash at `build()`, not a degraded stream. The arithmetic now lives in `PcmBuffer` with no Android dependencies so it is covered by host tests.

### Timebases: `AudioTimestamp` is not `elapsedRealtimeNanos`

`AudioTimestamp.nanoTime` is `CLOCK_MONOTONIC`; the show scheduler runs on `CLOCK_BOOTTIME` via `SystemClock.elapsedRealtimeNanos()`. They differ by however long the device has slept since boot. `TrackPlayer` converts the anchor rather than comparing across timebases.

### Two test fixtures were wrong in ways that nearly caused real code to be "fixed"

Both are worth flagging because in each case the production code was correct and the measurement was not.

1. **Vibrato synthesised as `sin(2π·f·(1 + d·sin(2π·fv·t))·t)`.** That looks like frequency modulation, but the phase derivative carries a term proportional to `t`, so deviation grows without bound — a few seconds in, the "vocal" is a broadband sweep, not a harmonic tone. HPSS was correctly declining to classify it as harmonic. Vibrato has to modulate the **phase**: `sin(2π·f·t − (f·d/fv)·cos(2π·fv·t))`.

2. **Measuring separation by summing the onset envelope.** The envelope is normalised to unit variance inside `OnsetDetector`, so the sum cannot show suppression however well separation works. It reported 101% retention for a signal that was in fact being masked to ~0.005. Measure **spectral** energy for this.

### A beatless drone was accepted with high confidence

Once HPSS correctly stripped a pure tone to near-zero, envelope normalisation divided by a near-zero standard deviation and amplified numerical noise to full scale. The beat tracker then fitted a perfectly regular grid to that noise and reported 0.81 confidence. Structure alone cannot catch this — every scale-free metric looked healthy. `BeatAnalyzer` now gates on **absolute** percussive energy (`MIN_PERCUSSIVE_RATIO = 0.05`) before anything scale-free runs.

---

### Phase 2: three timing traps, all the same shape

Every one of these is a systematic offset that looks like nothing in a unit test and like sloppiness on a device.

**Microphone input latency.** Treating the newest captured frame as "now" ignores the input buffer — typically 20–80 ms — and makes every cue late by that much. `MicCapture.captureNanosOf` anchors on `AudioRecord.getTimestamp` so the offset is measured, exactly as `TrackPlayer` does for output. Devices that decline to report timestamps fall back to a 40 ms estimate and say so.

**Timebases, again.** `AudioTimestamp.nanoTime` is `CLOCK_MONOTONIC`; the scheduler runs on `CLOCK_BOOTTIME`. Both the playback and the capture path convert rather than compare.

**A backward seek stranded the show.** The runner only ever walked its cue index forward, which is correct when a local file plays start to finish. Against a live source the position can jump — the operator skips, the track restarts, alignment re-seeks — and a backward jump left the index parked past cues that were now in the future, so the rest of the show never fired. The runner now re-indexes via `ArmedShow.indexAt` on any discontinuity over 400 ms.

### Alignment is ambiguous on repetitive material, by nature

On a loop, several offsets a bar apart match equally well, and no matcher can tell them apart — the audio genuinely is the same. `FingerprintTest` pins this down rather than pretending otherwise: any error on a looped fixture must be a whole number of bars. The resolution is temporal continuity, which is why `LiveAligner` constrains its search to a ±2 s window around the predicted position once locked. That constraint is also what makes a twice-a-second re-lock cheap enough to run continuously.

Measured on synthetic fixtures: a correct match scores 342 votes at 0.87 confidence against 2 votes for a foreign track, and a four-minute reference index is ~356 KB.

---

## Deviations from the PRD

| PRD says | Built as | Why |
| --- | --- | --- |
| DSP core in C++17 via the NDK | Plain Kotlin JVM module | The NDK isn't required to hit the target: a four-minute track analyses in ~2 s against an 8 s budget. The module has no Android dependency, so it still gets the host testing the C++ plan was for. Inner loops stay on primitive arrays with no allocation, so the port stays mechanical if a low-end device disagrees. |
| Beat map `peaks` field carries the waveform | Peaks passed alongside, not serialised | Nothing persists beat maps to disk yet. Revisit when the library screen lands. |
| Foreground service keeps the show running | Built | `ShowService` declares `mediaPlayback` for rehearsal and `microphone` for listening, because Android 14 requires the type to match what the service actually does. The show now survives backgrounding, with a stop control in the shade. |
| Beat maps cached by content hash | Not built | One track lives in memory at a time. Phase 2 works against the track you just imported, so a library is not required for it — but it is the next thing worth building. |

---

## Not yet verified on hardware

**Phase 2 has never heard a real speaker.** Every fingerprint and drift number here comes from synthetic fixtures fed straight into the matcher — no microphone, no room, no reverb, no crowd. The parts most likely to need adjusting on contact with reality are the lock thresholds (`MIN_VOTES = 12`, `MIN_CONFIDENCE = 0.35`) and the 4-second window length, since real rooms smear transients in ways clean audio does not. `AlignStatus` reports votes, confidence and rate specifically so those can be tuned against measurements rather than guesses.

Every timing number in this repo is host-measured. The **torch actuation latency is a default estimate**, not a measurement — `TorchDriver.measureLatency` times the software call round trip, which is a floor, not the optical truth. Confirming the ±20 ms end-to-end budget needs the photodiode rig described in the PRD: a photodiode over the LED into one scope channel, the audio output into the other, measuring the distribution of light-onset minus audio-beat. A 240 fps phone camera is an acceptable substitute at ~4 ms resolution.
