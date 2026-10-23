use std::f64::consts::PI;
use tunar_core::api::*;
fn config() -> TunarConfig {
    TunarConfig {
        sample_rate: 44100.,
        frame_hop_samples: 1024,
        a4_hz: 440.,
        noise_gate_dbfs: -45.,
        solfege: SolfegeSystem::Numbered,
        key: KeyMode {
            tonic_pc: 0,
            mode: ModeKind::Major,
        },
        temperament: 12,
    }
}
fn pcm(hz: f64, position: u64) -> Vec<f32> {
    (0..2048)
        .map(|i| (0.4 * (2. * PI * hz * (position + i) as f64 / 44100.).sin()) as f32)
        .collect()
}
fn ctx(position: u64) -> InputFrameContext {
    InputFrameContext {
        sample_position: position + 2048,
        capture_generation: 1,
        reference_active: false,
        metronome_active: false,
    }
}
fn feed(engine: &TunarEngine, session: &PracticeSession, hz: f64, start: u64, count: u64) {
    for i in 0..count {
        let p = (start + i) * 1024;
        let frame = engine.analyze_input(pcm(hz, p), ctx(p)).unwrap();
        session.observe(frame, 1, 440., 12, false).unwrap();
    }
}
#[test]
fn production_raw_measurement_is_independent_of_display_response() {
    let mut saved = Vec::new();
    for response in [
        ResponseMode::Fast,
        ResponseMode::Balanced,
        ResponseMode::Steady,
    ] {
        let e = TunarEngine::new(config());
        let mut policy = default_input_policy();
        policy.response = response;
        e.set_input_policy(policy).unwrap();
        let s = PracticeSession::new();
        s.lock_reference(0).unwrap();
        s.start();
        feed(&e, &s, 440. * 2_f64.powf(3. / 1200.), 0, 60);
        feed(&e, &s, 440. * 2_f64.powf(-7. / 1200.), 60, 60);
        s.finish();
        let page = s.read_page(0, 64);
        assert!(page.iter().any(|p| p.valid));
        saved.push(
            page.into_iter()
                .map(|p| (p.frame.measured_freq_hz, p.cents, p.valid))
                .collect::<Vec<_>>(),
        );
    }
    assert_eq!(saved[0], saved[1]);
    assert_eq!(saved[0], saved[2]);
}
#[test]
fn held_spectrum_and_pitch_share_the_original_measurement() {
    let e = TunarEngine::new(config());
    e.set_input_policy(default_input_policy()).unwrap();
    let s = PracticeSession::new();
    s.start();
    feed(&e, &s, 440., 0, 60);
    let before = s.display_point().unwrap();
    let frame = e.analyze_input(vec![0.; 2048], ctx(60 * 1024)).unwrap();
    s.observe(frame, 1, 440., 12, false).unwrap();
    let p = s.presentation();
    assert_eq!(p.source, PresentationSource::Held);
    let held = p.measurement.unwrap();
    assert_eq!(held.frame.sample_position, before.frame.sample_position);
    assert_eq!(held.frame.spectrum_db, before.frame.spectrum_db);
    assert!(p.live_input.unwrap().input_level_dbfs < -100.);
    assert!(p.summary.is_some());
}
#[test]
fn genuine_octave_change_is_not_pulled_back_to_the_target() {
    let e = TunarEngine::new(config());
    let s = PracticeSession::new();
    s.lock_reference(0).unwrap();
    s.start();
    feed(&e, &s, 440., 0, 50);
    feed(&e, &s, 880., 50, 50);
    let p = s.display_point().unwrap();
    assert!((p.cents.unwrap() - 1200.).abs() < 0.6);
    assert!((p.frame.measured_freq_hz.unwrap() - 880.).abs() < 1.);
}
#[test]
fn schema_two_restores_policy_basis_and_rejects_tampering() {
    let e = TunarEngine::new(config());
    let s = PracticeSession::new();
    s.start();
    feed(&e, &s, 440., 0, 50);
    let json = s.export_json().unwrap();
    let data: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(data["schema_version"], 2);
    let reopened = PracticeSession::new();
    reopened.import_json(json).unwrap();
    assert_eq!(
        reopened.display_point().unwrap().measurement_basis,
        MeasurementBasis::AcceptedRaw
    );
    let mut corrupt = data;
    corrupt["points"][4]["frame"]["measured_freq_hz"] = serde_json::json!(1234.);
    assert!(reopened.import_json(corrupt.to_string()).is_err());
    assert!(reopened.status().count > 0);
    assert!(
        reopened
            .export_csv(None, None)
            .unwrap()
            .contains("measurement_basis")
    );
}
#[test]
fn legacy_schema_one_is_labelled_without_recalculating() {
    let e = TunarEngine::new(config());
    let s = PracticeSession::new();
    s.start();
    for i in 0..30 {
        let f = e.analyze(pcm(440., i * 1024));
        s.observe(f, 1, 440., 12, false).unwrap();
    }
    let mut data: serde_json::Value = serde_json::from_str(&s.export_json().unwrap()).unwrap();
    data["schema_version"] = serde_json::json!(1);
    for p in data["points"].as_array_mut().unwrap() {
        p.as_object_mut().unwrap().remove("measurement_basis");
        p["frame"]
            .as_object_mut()
            .unwrap()
            .remove("measured_freq_hz");
        p["frame"]
            .as_object_mut()
            .unwrap()
            .remove("input_diagnostics");
    }
    let r = PracticeSession::new();
    r.import_json(data.to_string()).unwrap();
    assert_eq!(
        r.display_point().unwrap().measurement_basis,
        MeasurementBasis::LegacySmoothed
    );
}
#[test]
fn reference_output_and_calibration_never_add_valid_measurements() {
    let e = TunarEngine::new(config());
    let s = PracticeSession::new();
    s.start();
    for i in 0..30 {
        let mut c = ctx(i * 1024);
        c.reference_active = true;
        let f = e.analyze_input(pcm(440., i * 1024), c).unwrap();
        s.observe(f, 1, 440., 12, true).unwrap();
    }
    assert!(s.trace_page(0, 6000).iter().all(|p| !p.valid));
    assert!(e.begin_noise_calibration(false).is_err());
    for i in 30..48 {
        let f = e.analyze_input(vec![0.; 2048], ctx(i * 1024)).unwrap();
        s.observe(f, 1, 440., 12, false).unwrap();
    }
    e.begin_noise_calibration(false).unwrap();
    for i in 48..58 {
        let f = e.analyze_input(vec![0.; 2048], ctx(i * 1024)).unwrap();
        assert_eq!(
            f.input_diagnostics.unwrap().reason,
            InputReason::Calibrating
        );
    }
}
#[test]
fn real_harmonics_do_not_replace_the_fundamental() {
    let e = TunarEngine::new(config());
    let mut last = None;
    for j in 0..50 {
        let p = j * 1024;
        let wave = (0..2048)
            .map(|i| {
                let t = 2. * PI * 440. * (p + i) as f64 / 44100.;
                (0.04 * t.sin()
                    + 0.16 * (2. * t).sin()
                    + 0.16 * (3. * t).sin()
                    + 0.08 * (4. * t).sin()) as f32
            })
            .collect();
        last = e.analyze_input(wave, ctx(p)).unwrap().measured_freq_hz;
    }
    assert!((last.unwrap() - 440.).abs() < 0.5);
}
#[test]
fn reopening_input_loss_and_expired_hold_do_not_fake_live() {
    let e = TunarEngine::new(config());
    let mut policy = default_input_policy();
    policy.hold = HoldMode::Seconds5;
    e.set_input_policy(policy).unwrap();
    let s = PracticeSession::new();
    s.start();
    feed(&e, &s, 440., 0, 30);
    let f = e.analyze_input(vec![0.; 2048], ctx(300 * 1024)).unwrap();
    s.observe(f, 1, 440., 12, false).unwrap();
    assert!(s.presentation().measurement.is_none());
    s.set_input_available(false);
    assert!(s.presentation().live_input.is_none());
}

#[test]
fn input_loss_and_condition_changes_show_only_labelled_old_results() {
    let e = TunarEngine::new(config());
    let s = PracticeSession::new();
    s.start();
    feed(&e, &s, 440., 0, 30);
    s.set_input_available(false);
    assert_eq!(s.presentation().source, PresentationSource::Held);
    assert!(s.presentation().live_input.is_none());
    s.lock_reference(1).unwrap();
    let p = s.presentation();
    assert!(p.measurement.is_none());
    assert!(p.previous_measurement.is_some());
    s.return_to_live();
    feed(&e, &s, 440., 30, 30);
    let id = s.trace_page(0, 6000)[3].id;
    s.select_point(id).unwrap();
    let f = e.analyze_input(vec![0.; 2048], ctx(60 * 1024)).unwrap();
    s.observe(f, 1, 440., 12, false).unwrap();
    assert_eq!(s.presentation().source, PresentationSource::Review);
}

#[test]
fn name_hysteresis_keeps_cents_relative_to_the_visible_note() {
    let e = TunarEngine::new(config());
    let s = PracticeSession::new();
    feed(&e, &s, 440., 0, 40);
    feed(&e, &s, 440. * 2_f64.powf(54. / 1200.), 40, 40);
    let p = s.display_point().unwrap();
    let event = p.frame.tuner.unwrap();
    assert_eq!(event.note_name, "A4");
    assert!((event.cents_off - 54.).abs() < 0.6);
}
#[test]
fn schema_rejects_incoherent_quality_and_resolved_thresholds() {
    let e = TunarEngine::new(config());
    let s = PracticeSession::new();
    s.start();
    feed(&e, &s, 440., 0, 40);
    let data: serde_json::Value = serde_json::from_str(&s.export_json().unwrap()).unwrap();
    let mut corrupt = data.clone();
    corrupt["segments"][0]["input_policy"]["open_dbfs"] = serde_json::json!(-20.);
    for p in corrupt["points"].as_array_mut().unwrap() {
        p["frame"]["input_diagnostics"]["policy"]["open_dbfs"] = serde_json::json!(-20.);
    }
    assert!(s.import_json(corrupt.to_string()).is_err());
    let mut corrupt = data;
    let point = corrupt["points"]
        .as_array_mut()
        .unwrap()
        .iter_mut()
        .find(|p| p["valid"] == true)
        .unwrap();
    point["frame"]["input_diagnostics"]["clipped_fraction"] = serde_json::json!(0.1);
    assert!(s.import_json(corrupt.to_string()).is_err());
}

#[test]
fn missing_fundamental_and_above_gate_random_noise_are_distinguished() {
    let e = TunarEngine::new(config());
    let mut last = None;
    for j in 0..20 {
        let p = j * 1024;
        let wave = (0..2048)
            .map(|i| {
                let t = 2. * PI * 440. * (p + i) as f64 / 44100.;
                (0.16 * (2. * t).sin() + 0.16 * (3. * t).sin() + 0.08 * (4. * t).sin()) as f32
            })
            .collect();
        last = e.analyze_input(wave, ctx(p)).unwrap().measured_freq_hz;
    }
    assert!((last.unwrap() - 440.).abs() < 0.5);
    let e = TunarEngine::new(config());
    let mut seed = 8731u32;
    for j in 0..20 {
        let noise = (0..2048)
            .map(|_| {
                seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                ((seed >> 8) as f32 / 16777216. - 0.5) * 0.1
            })
            .collect();
        assert!(
            e.analyze_input(noise, ctx(j * 1024))
                .unwrap()
                .measured_freq_hz
                .is_none()
        );
    }
    let mut impact = vec![0.; 2048];
    impact[30] = 0.95;
    assert!(
        e.analyze_input(impact, ctx(20 * 1024))
            .unwrap()
            .measured_freq_hz
            .is_none()
    );
}
#[test]
fn calibrated_fifty_and_sixty_hz_hum_stays_below_the_environment_gate() {
    for hz in [50., 60.] {
        let e = TunarEngine::new(config());
        e.begin_noise_calibration(false).unwrap();
        for i in 0..90 {
            e.analyze_input(
                pcm(hz, i * 1024).into_iter().map(|v| v * 0.03).collect(),
                ctx(i * 1024),
            )
            .unwrap();
        }
        assert!(matches!(
            e.calibration_status().state,
            CalibrationState::NeedsQuietConfirmation
        ));
        e.confirm_noise_environment().unwrap();
        assert_eq!(e.calibration_status().state, CalibrationState::Ready);
        for i in 90..100 {
            let f = e
                .analyze_input(
                    pcm(hz, i * 1024).into_iter().map(|v| v * 0.03).collect(),
                    ctx(i * 1024),
                )
                .unwrap();
            assert!(f.measured_freq_hz.is_none());
        }
    }
}

#[test]
fn production_analysis_reports_per_window_cost() {
    let e = TunarEngine::new(config());
    let mut durations = Vec::new();
    for j in 0..64 {
        let samples = pcm(440., j * 1024);
        let started = std::time::Instant::now();
        e.analyze_input(samples, ctx(j * 1024)).unwrap();
        durations.push(started.elapsed().as_secs_f64() * 1000.);
    }
    println!(
        "production_window_ms={}",
        serde_json::to_string(&durations).unwrap()
    );
    if !cfg!(debug_assertions) {
        assert!(durations.iter().sum::<f64>() / (durations.len() as f64) < 1024. / 44100. * 1000.);
    }
}
