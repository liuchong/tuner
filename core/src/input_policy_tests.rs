use super::*;
use crate::signal::PitchSample;
fn context(ms: u64) -> InputFrameContext {
    InputFrameContext {
        sample_position: ms,
        capture_generation: 1,
        reference_active: false,
        metronome_active: false,
    }
}
fn tone() -> Option<PitchSample> {
    Some(PitchSample {
        freq_hz: 440.,
        clarity: 0.98,
    })
}
fn runtime() -> InputRuntime {
    InputRuntime::new(1000., 20, -45.)
}
#[test]
fn profiles_resolve_separate_open_and_close_thresholds() {
    let mut r = runtime();
    assert_eq!(r.resolved().open_dbfs, -45.);
    let mut p = InputPolicy {
        profile: InputProfile::Soft,
        ..InputPolicy::default()
    };
    r.set_policy(p).unwrap();
    assert_eq!(r.resolved().open_dbfs, -55.);
    p.profile = InputProfile::Noisy;
    r.set_policy(p).unwrap();
    assert_eq!(r.resolved().close_dbfs, -41.);
}
#[test]
fn invalid_policy_is_atomic() {
    let mut r = runtime();
    let old = r.policy();
    let mut p = old;
    p.manual_hysteresis_db = f32::NAN;
    assert!(r.set_policy(p).is_err());
    assert_eq!(r.policy(), old);
}
#[test]
fn confirmation_is_audio_time_and_dropouts_never_measure() {
    let mut r = runtime();
    for ms in [0, 20, 40] {
        assert!(
            r.process(tone(), -20., 0., context(ms))
                .unwrap()
                .measured
                .is_none()
        );
    }
    assert!(
        r.process(tone(), -20., 0., context(60))
            .unwrap()
            .measured
            .is_some()
    );
    let out = r.process(None, -100., 0., context(80)).unwrap();
    assert!(out.measured.is_none());
    assert_eq!(out.diagnostics.reason, InputReason::Dropout);
}
#[test]
fn duplicate_and_backward_contexts_do_not_mutate_state() {
    let mut r = runtime();
    r.process(tone(), -20., 0., context(20)).unwrap();
    assert!(r.process(tone(), -20., 0., context(20)).is_err());
    assert!(r.process(tone(), -20., 0., context(19)).is_err());
    assert!(
        r.process(tone(), -20., 0., context(40))
            .unwrap()
            .measured
            .is_none()
    );
}
#[test]
fn response_changes_do_not_change_decision_revision() {
    let mut r = runtime();
    let rev = r.resolved().policy_revision;
    let mut p = r.policy();
    p.response = ResponseMode::Steady;
    p.hold = HoldMode::Seconds5;
    r.set_policy(p).unwrap();
    assert_eq!(r.resolved().policy_revision, rev);
}
#[test]
fn quiet_calibration_uses_percentile_and_keeps_hysteresis() {
    let mut r = InputRuntime::new(1000., 50, -45.);
    r.begin_calibration(false).unwrap();
    for ms in (0..=2050).step_by(50) {
        r.process(None, -60., 0., context(ms)).unwrap();
    }
    assert_eq!(r.calibration_status().state, CalibrationState::Ready);
    assert_eq!(r.resolved().open_dbfs, -50.);
    assert_eq!(r.resolved().close_dbfs, -56.);
}
#[test]
fn persistent_tone_requires_explicit_environment_confirmation() {
    let mut r = InputRuntime::new(1000., 50, -45.);
    r.begin_calibration(false).unwrap();
    for ms in (0..=2050).step_by(50) {
        r.process(tone(), -50., 0., context(ms)).unwrap();
    }
    assert_eq!(
        r.calibration_status().state,
        CalibrationState::NeedsQuietConfirmation
    );
    r.confirm_environment().unwrap();
    assert_eq!(r.calibration_status().state, CalibrationState::Ready);
}
#[test]
fn cancelled_and_output_blocked_calibration_preserve_context() {
    let mut r = runtime();
    assert!(r.begin_calibration(true).is_err());
    r.begin_calibration(false).unwrap();
    r.cancel_calibration();
    assert_eq!(r.resolved().open_dbfs, -45.);
    assert_ne!(r.calibration_status().state, CalibrationState::Measuring);
}
#[test]
fn generation_change_invalidates_noise_estimate() {
    let mut r = InputRuntime::new(1000., 50, -45.);
    r.begin_calibration(false).unwrap();
    for ms in (0..=2050).step_by(50) {
        r.process(None, -60., 0., context(ms)).unwrap();
    }
    let mut next = context(0);
    next.capture_generation = 2;
    r.process(None, -80., 0., next).unwrap();
    assert!(r.resolved().noise_floor_dbfs.is_none());
    assert_eq!(r.resolved().open_dbfs, -45.);
}
#[test]
fn clipping_is_not_accepted_and_metronome_is_not_blanket_rejected() {
    let mut r = runtime();
    for ms in [0, 20, 40, 60] {
        r.process(tone(), -20., 0., context(ms)).unwrap();
    }
    assert!(
        r.process(tone(), -1., 0.02, context(80))
            .unwrap()
            .measured
            .is_none()
    );
    let mut r = runtime();
    for ms in [0, 20, 40, 60] {
        let mut c = context(ms);
        c.metronome_active = true;
        let out = r.process(tone(), -20., 0., c).unwrap();
        if ms == 60 {
            assert!(out.measured.is_some());
            assert!(out.diagnostics.metronome_risk);
        }
    }
}
#[test]
fn hold_visibility_is_independent_from_valid_time() {
    assert!(hold_visible(HoldMode::UntilNext, 3600.));
    assert!(!hold_visible(HoldMode::Off, 0.));
    assert!(hold_visible(HoldMode::Seconds5, 4.99));
    assert!(!hold_visible(HoldMode::Seconds5, 5.));
    assert!(!hold_visible(HoldMode::Seconds15, 15.));
}
#[test]
fn silence_cannot_train_thresholds_during_ordinary_playing() {
    let mut r = runtime();
    for ms in (0..2000).step_by(20) {
        r.process(None, -85., 0., context(ms)).unwrap();
    }
    assert_eq!(r.resolved().open_dbfs, -45.);
    assert!(r.resolved().noise_floor_dbfs.is_none());
}

#[test]
fn production_time_contract_is_sample_rate_independent() {
    for rate in [44100., 48000.] {
        let mut r = InputRuntime::new(rate, (rate / 100.) as u32, -45.);
        for i in 0..=6 {
            let mut c = context(0);
            c.sample_position = (rate * i as f64 / 100.).round() as u64;
            assert_eq!(
                r.process(tone(), -20., 0., c).unwrap().measured.is_some(),
                i == 6
            );
        }
    }
}
#[test]
fn manual_gate_continues_below_open_then_releases_below_close() {
    let mut r = runtime();
    r.set_policy(InputPolicy {
        gate_mode: GateMode::Manual,
        manual_open_dbfs: -40.,
        manual_hysteresis_db: 6.,
        ..InputPolicy::default()
    })
    .unwrap();
    for ms in [0, 20, 40, 60] {
        r.process(tone(), -30., 0., context(ms)).unwrap();
    }
    assert!(
        r.process(tone(), -44., 0., context(80))
            .unwrap()
            .measured
            .is_some()
    );
    assert!(
        r.process(tone(), -47., 0., context(100))
            .unwrap()
            .measured
            .is_none()
    );
    assert!(
        r.process(tone(), -44., 0., context(120))
            .unwrap()
            .measured
            .is_none()
    );
}
#[test]
fn octave_outlier_is_invalid_but_sustained_octave_is_accepted() {
    let mut r = runtime();
    for ms in [0, 20, 40, 60] {
        r.process(tone(), -20., 0., context(ms)).unwrap();
    }
    let high = Some(PitchSample {
        freq_hz: 880.,
        clarity: 0.98,
    });
    assert_eq!(
        r.process(high, -20., 0., context(80))
            .unwrap()
            .diagnostics
            .reason,
        InputReason::AmbiguousPitch
    );
    assert_eq!(
        r.process(tone(), -20., 0., context(100))
            .unwrap()
            .measured
            .unwrap()
            .freq_hz,
        440.
    );
    for ms in [120, 140, 160, 180] {
        assert!(
            r.process(high, -20., 0., context(ms))
                .unwrap()
                .measured
                .is_none()
        );
    }
    assert_eq!(
        r.process(high, -20., 0., context(200))
            .unwrap()
            .measured
            .unwrap()
            .freq_hz,
        880.
    );
}
#[test]
fn clipping_and_discontinuous_calibration_preserve_previous_floor() {
    let mut r = InputRuntime::new(1000., 50, -45.);
    r.begin_calibration(false).unwrap();
    for ms in (0..=2050).step_by(50) {
        r.process(None, -60., 0., context(ms)).unwrap();
    }
    r.begin_calibration(false).unwrap();
    r.process(None, -20., 0.02, context(2100)).unwrap();
    assert_eq!(r.calibration_status().state, CalibrationState::Failed);
    assert_eq!(r.resolved().noise_floor_dbfs, Some(-60.));
    r.begin_calibration(false).unwrap();
    r.process(None, -60., 0., context(2150)).unwrap();
    r.process(None, -60., 0., context(2300)).unwrap();
    assert_eq!(r.calibration_status().state, CalibrationState::Failed);
    assert_eq!(r.resolved().noise_floor_dbfs, Some(-60.));
}

#[test]
fn calibration_sampling_keeps_twenty_hz_with_non_divisible_hops() {
    let mut r = InputRuntime::new(44100., 1024, -45.);
    r.begin_calibration(false).unwrap();
    let mut elapsed = 0.;
    for i in 0..100 {
        let mut c = context(0);
        c.sample_position = 2048 + i * 1024;
        r.process(None, -60., 0., c).unwrap();
        if r.calibration_status().state == CalibrationState::Ready {
            elapsed = r.calibration_status().elapsed_seconds;
            break;
        }
    }
    assert!((2. ..2.024).contains(&elapsed));
}

#[test]
fn calibration_rejects_an_environment_that_would_require_unsafe_high_gate() {
    let mut r = InputRuntime::new(1000., 50, -45.);
    r.begin_calibration(false).unwrap();
    for ms in (0..=2050).step_by(50) {
        r.process(None, -15., 0., context(ms)).unwrap();
    }
    assert_eq!(r.calibration_status().state, CalibrationState::Failed);
    assert!(r.resolved().noise_floor_dbfs.is_none());
    assert_eq!(r.resolved().open_dbfs, -45.);
}
