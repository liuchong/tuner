use std::f64::consts::PI;

use tunar_core::api::{KeyMode, ModeKind, SignalState, SolfegeSystem, TunarConfig, TunarEngine};
use tunar_core::pitch::NOISE_GATE_DEFAULT_DBFS;

const SAMPLE_RATE: f64 = 44_100.0;
const WINDOW_SAMPLES: usize = 2_048;
const HOP_SAMPLES: u32 = 1_024;

fn config() -> TunarConfig {
    TunarConfig {
        sample_rate: SAMPLE_RATE,
        frame_hop_samples: HOP_SAMPLES,
        a4_hz: 440.0,
        noise_gate_dbfs: -50.0,
        solfege: SolfegeSystem::Numbered,
        key: KeyMode {
            tonic_pc: 0,
            mode: ModeKind::Major,
        },
        temperament: 12,
    }
}

fn sine(freq_hz: f64, rms_dbfs: f64, phase_samples: usize) -> Vec<f32> {
    let amplitude = 10.0_f64.powf(rms_dbfs / 20.0) * 2.0_f64.sqrt();
    (0..WINDOW_SAMPLES)
        .map(|i| {
            let sample = phase_samples + i;
            (amplitude * (2.0 * PI * freq_hz * sample as f64 / SAMPLE_RATE).sin()) as f32
        })
        .collect()
}

fn silence() -> Vec<f32> {
    vec![0.0; WINDOW_SAMPLES]
}

#[test]
fn default_noise_gate_is_minus_45_dbfs() {
    assert!((NOISE_GATE_DEFAULT_DBFS + 45.0).abs() < f32::EPSILON);
}

#[test]
fn requires_two_consecutive_valid_frames_before_showing_pitch() {
    let engine = TunarEngine::new(config());

    let first = engine.analyze(sine(440.0, -30.0, 0));
    assert_eq!(first.signal_state, SignalState::Acquiring);
    assert!(first.tuner.is_none());

    let second = engine.analyze(sine(440.0, -30.0, HOP_SAMPLES as usize));
    assert_eq!(second.signal_state, SignalState::Tracking);
    assert!(second.tuner.is_some());
    assert!(!second.is_held);
    assert!((second.display_strength - 1.0).abs() < f32::EPSILON);
}

#[test]
fn gate_hysteresis_keeps_tracking_until_three_db_below_open_threshold() {
    let engine = TunarEngine::new(config());
    let _ = engine.analyze(sine(440.0, -49.0, 0));
    let tracking = engine.analyze(sine(440.0, -49.0, HOP_SAMPLES as usize));
    assert_eq!(tracking.signal_state, SignalState::Tracking);

    let inside_hysteresis = engine.analyze(sine(440.0, -52.0, HOP_SAMPLES as usize * 2));
    assert_eq!(inside_hysteresis.signal_state, SignalState::Tracking);

    let below_close = engine.analyze(sine(440.0, -54.0, HOP_SAMPLES as usize * 3));
    assert_eq!(below_close.signal_state, SignalState::Holding);
    assert!(below_close.is_held);
}

#[test]
fn holds_last_confirmed_pitch_indefinitely_at_full_strength() {
    let engine = TunarEngine::new(config());
    let _ = engine.analyze(sine(440.0, -20.0, 0));
    let acquired = engine.analyze(sine(440.0, -20.0, HOP_SAMPLES as usize));
    assert!(acquired.tuner.is_some());

    // 600 hops 约为 13.9 秒，远超过旧的 1200ms 超时。
    let mut held = engine.analyze(silence());
    for _ in 1..600 {
        held = engine.analyze(silence());
    }
    assert_eq!(held.signal_state, SignalState::Holding);
    assert!((held.display_strength - 1.0).abs() < f32::EPSILON);
    assert!(held.tuner.is_some());
    assert!(held.is_held);
}

#[test]
fn held_pitch_is_replaced_only_after_two_consecutive_valid_frames() {
    let engine = TunarEngine::new(config());
    let _ = engine.analyze(sine(440.0, -20.0, 0));
    let _ = engine.analyze(sine(440.0, -20.0, HOP_SAMPLES as usize));
    let holding = engine.analyze(silence());
    assert_eq!(holding.signal_state, SignalState::Holding);

    let candidate = engine.analyze(sine(493.883, -20.0, HOP_SAMPLES as usize * 3));
    assert_eq!(candidate.signal_state, SignalState::Holding);
    let still_a4 = candidate.tuner.expect("第一帧候选期间应继续显示旧读数");
    assert!((still_a4.freq_hz - 440.0).abs() < 1.0);
    assert!(candidate.is_held);

    let replaced = engine.analyze(sine(493.883, -20.0, HOP_SAMPLES as usize * 4));
    assert_eq!(replaced.signal_state, SignalState::Tracking);
    let b4 = replaced.tuner.expect("第二帧候选应替换旧读数");
    assert!(
        (b4.freq_hz - still_a4.freq_hz).abs() > 10.0,
        "应替换旧读数，实际仍为 {}Hz",
        b4.freq_hz
    );
    assert!(!replaced.is_held);
}

#[test]
fn reference_tones_follow_temperament_calibration_and_range() {
    let engine = TunarEngine::new(config());
    engine.set_temperament(19);
    let tones = engine.list_reference_tones();

    assert!(!tones.is_empty());
    assert!(
        tones
            .iter()
            .all(|tone| { (80.0..=1500.0).contains(&tone.frequency_hz) && tone.temperament == 19 })
    );
    assert!(
        tones
            .windows(2)
            .all(|pair| pair[0].frequency_hz < pair[1].frequency_hz)
    );

    let a4 = tones
        .iter()
        .find(|tone| tone.step_from_a4 == 0)
        .expect("A4 reference tone must exist");
    assert!((a4.frequency_hz - 440.0).abs() < 1e-9);
    assert_eq!(a4.note_name, "A4");
    assert!(a4.cents_from_note.abs() < 1e-9);

    let upper = tones
        .iter()
        .find(|tone| tone.step_from_a4 == 1)
        .expect("upper adjacent reference tone must exist");
    let expected = 440.0 * 2.0_f64.powf(1.0 / 19.0);
    assert!((upper.frequency_hz - expected).abs() < 1e-9);

    engine.set_a4(442.0);
    let calibrated = engine.list_reference_tones();
    let calibrated_a4 = calibrated
        .iter()
        .find(|tone| tone.step_from_a4 == 0)
        .expect("calibrated A4 reference tone must exist");
    assert!((calibrated_a4.frequency_hz - 442.0).abs() < 1e-9);
}
