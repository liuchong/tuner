# spec-audio — audio pipeline (Android / iOS / macOS)

## 1. Capture for tuning and analysis

- Android uses `AudioRecord`; iOS uses `AVAudioEngine`.
- Mono floating-point PCM, device-negotiated rate (44.1 kHz preferred), 1024-sample
  hop and 2048-sample analysis window.
- The actual hop is written to `TunerConfig.frame_hop_samples` so core and native
  layers share one analysis cadence.
- A dedicated capture path sends PCM to `TunerEngine.analyze`; UI delivery is
  non-blocking and may discard stale frames.
- Capture-to-display latency target is ≤100 ms.
- Microphone permission is requested at runtime. Denial shows guidance and never crashes.
- Tuner, instrument tuner, and professional analysis share one capture hub. A second
  microphone session is forbidden.

On iOS the input tap writes into a construction-time ring buffer and forms each
2048-sample window without `append`, slicing, or `removeFirst` in the callback.
Every asynchronous start owns a generation token. Releasing the final subscriber
invalidates that token; a stale completion may clean up its local engine but may not
publish itself as the active session. A zero input sample rate or zero channel count
causes a quiet start failure and session release, followed by retry on the next acquire.

## 2. Metronome playback

- Android uses streaming `AudioTrack`; iOS uses a native audio engine/player node.
- Timing comes from core `Metronome.render`; tick samples are mixed at exact sample
  offsets. Audible scheduling jitter target is <1 ms.
- Twelve programmatically synthesized sounds are ordered by common use: Mechanical,
  Wood Block, Electronic Beep, Claves, Rimshot, Snare, Cowbell, Hi-hat, Clap, Shaker,
  Kick, Bell.
- Strong and weak beat sounds are independently selectable. Defaults are Bell for
  strong beats and Mechanical for weak beats.
- Every synthesized waveform is non-empty, finite, and peaks at or below 0.95.
  Android and iOS use the same frequencies, durations, envelopes, and gains.
- Native playback keeps at least two buffers queued. Tick UI presentation compensates
  for already queued samples before emitting the visual beat.
- Android uses a media-playback foreground service while running. Notification denial
  does not stop playback.

## 3. Reference-tone playback

- Frequency comes only from core `ReferenceTone`; native layers do not recalculate
  temperament steps.
- Only one mono sine tone plays at once. Peak gain is 0.65 and start/stop/change uses
  an approximately 20 ms fade with no clipping.
- Android creates its `AudioTrack` lazily on first play and releases it after fade-out.
- iOS uses capture-compatible play-and-record routing and speaker output.
- Opening or dismissing the tone sheet never stops capture. Dismissing the sheet keeps
  the tone playing so the microphone can recapture it for calibration.
- Leaving the tuner page, entering the background, or an audio interruption stops tone
  playback. No additional permission is required.

## 4. Real-time discipline

- Audio callbacks perform no network or file I/O.
- Audio callbacks do not lock, block the UI, grow collections, allocate large objects,
  or call panic/exception paths.
- Cross-thread delivery uses bounded/non-blocking channels or state streams with stale
  frame dropping.
- UI updates may conflate events above 30 fps.
- Sample rate is always negotiated and passed to core; it is never assumed.
- Professional spectrum, pitch trace, and waveform consume the same capture window and
  one core `analyze` result. No view opens a second microphone, performs a second FFT,
  or allocates display history inside the audio callback.

## 5. macOS device boundary

- macOS 14+ uses `AVAudioEngine` input and playback nodes without the iOS-only
  `AVAudioSession`.
- iOS and macOS share the preallocated `AudioFrameRing`, analysis worker, startup token,
  and UniFFI state model. Platform conditions are limited to device/session behavior
  such as opening System Settings.
- Rust builds `aarch64-apple-darwin` and `x86_64-apple-darwin`, merges them into one
  universal macOS slice, and adds it to the same XCFramework. Both architectures export
  the identical UniFFI contract.
- The microphone stops when the window is inactive or the final capture section leaves.
  Background capture, manual device routing, recording, and export are not supported.

## 6. Change log

- 2026-07-20: shared capture cadence and queued-sample visual synchronization.
- 2026-07-28: twelve cross-platform synthesized metronome timbres.
- 2026-07-30: documentation localized under the TUNAR / 吐呐 brand; technical audio
  behavior is unchanged.
