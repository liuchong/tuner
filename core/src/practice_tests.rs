use super::*;
use crate::api::{AnalysisFrame, TunarEvent};
use crate::signal::SignalState;

fn frame(ms: u64, cents: f64) -> AnalysisFrame {
    AnalysisFrame {
        tuner: Some(TunarEvent {
            freq_hz: 440.0 * 2_f64.powf(cents / 1200.0),
            note_name: "A4".into(),
            midi: 69,
            cents_off: cents,
            clarity: 0.98,
            solfege: "6".into(),
            temperament: 12,
            temperament_step: 0,
            temperament_cents: cents,
        }),
        spectrum_db: vec![-40.; 64],
        wide_spectrum_db: vec![-50.; 128],
        wide_spectrum_max_hz: 20_000.,
        waveform_min: vec![-0.1; 256],
        waveform_max: vec![0.1; 256],
        sample_position: ms * 48,
        sample_rate_hz: 48_000.,
        partials: vec![],
        chord: None,
        signal_state: SignalState::Tracking,
        input_level_dbfs: -24.,
        display_strength: 1.,
        is_held: false,
    }
}
fn push(s: &PracticeSession, ms: u64, cents: f64) -> PracticePoint {
    s.observe(frame(ms, cents), 1, 440., 12, false).unwrap()
}
fn session() -> Arc<PracticeSession> {
    let s = PracticeSession::new();
    s.lock_reference(0).unwrap();
    s.start();
    s
}

#[test]
fn locked_target_does_not_fold_semitones_or_octaves() {
    let s = session();
    assert!((push(&s, 0, 100.).cents.unwrap() - 100.).abs() < 1e-6);
    assert!((push(&s, 100, 1200.).cents.unwrap() - 1200.).abs() < 1e-6);
    assert_eq!(s.config().target_step, Some(0));
}
#[test]
fn cents_trace_retains_sub_semitone_precision() {
    let s = session();
    push(&s, 0, 3.);
    push(&s, 100, -7.);
    let trace = s.trace_page(0, 6000);
    assert_eq!(trace.len(), 2);
    assert!((trace[0].cents.unwrap() - 3.).abs() < 1e-7);
    assert!((trace[1].cents.unwrap() + 7.).abs() < 1e-7);
}
#[test]
fn auto_target_follows_selected_equal_temperament() {
    let s = PracticeSession::new();
    let p = s.observe(frame(0, 35.), 1, 440., 19, false).unwrap();
    let target = 440. * 2_f64.powf(1. / 19.);
    assert!((p.target_hz.unwrap() - target).abs() < 1e-7);
}
#[test]
fn invalid_configuration_is_atomic() {
    let s = session();
    let mut c = s.config();
    c.a4_hz = f64::NAN;
    assert!(s.configure(c).is_err());
    assert_eq!(s.config().a4_hz, 440.);
}
#[test]
fn incompatible_target_choices_are_rejected() {
    let s = session();
    let mut c = s.config();
    c.target_hz = Some(440.);
    assert!(s.configure(c).is_err());
}
#[test]
fn instrument_target_uses_current_a4() {
    let s = session();
    s.lock_instrument(69, "guitar".into(), "standard".into(), None)
        .unwrap();
    let p = s.observe(frame(0, 0.), 1, 442., 12, false).unwrap();
    assert_eq!(p.target_hz, Some(442.));
}
#[test]
fn decimation_uses_audio_buckets_and_preserves_real_positions() {
    let s = session();
    push(&s, 1, 0.);
    push(&s, 20, 0.);
    push(&s, 99, 0.);
    push(&s, 101, 0.);
    assert_eq!(s.status().count, 2);
    assert_eq!(s.read_page(0, 64)[1].frame.sample_position, 101 * 48);
}
#[test]
fn quiet_and_held_frames_are_not_valid_trace_points() {
    let s = session();
    push(&s, 0, 0.);
    let mut f = frame(100, 0.);
    f.is_held = true;
    f.signal_state = SignalState::Holding;
    assert!(!s.observe(f, 1, 440., 12, false).unwrap().valid);
    assert!(!s.trace_page(0, 6000)[1].valid);
}
#[test]
fn review_selects_one_complete_frame_and_pauses_recording() {
    let s = session();
    let first = push(&s, 0, 3.);
    push(&s, 100, 12.);
    let point = s.select_point(first.id).unwrap();
    assert_eq!(point.frame.sample_position, 0);
    assert_eq!(s.status().state, PracticeRecordingState::Paused);
    push(&s, 200, 25.);
    assert_eq!(s.display_point().unwrap().id, first.id);
    s.return_to_live();
    assert_eq!(s.status().state, PracticeRecordingState::Paused);
    assert!((s.display_point().unwrap().cents.unwrap() - 25.).abs() < 1e-6);
}
#[test]
fn resume_inserts_segment_boundary() {
    let s = session();
    push(&s, 0, 0.);
    s.pause();
    s.start();
    push(&s, 100, 0.);
    let p = s.read_page(0, 64);
    assert_ne!(p[0].segment_id, p[1].segment_id);
}
#[test]
fn capture_restart_pauses_and_does_not_join_time_axes() {
    let s = session();
    push(&s, 1000, 0.);
    s.observe(frame(0, 0.), 2, 440., 12, false).unwrap();
    assert_eq!(s.status().state, PracticeRecordingState::Paused);
    s.start();
    s.observe(frame(100, 0.), 2, 440., 12, false).unwrap();
    let p = s.read_page(0, 64);
    assert_ne!(p[0].segment_id, p[1].segment_id);
    assert!(p[1].time_seconds > p[0].time_seconds);
}
#[test]
fn historical_configuration_survives_current_settings() {
    let s = session();
    let p = push(&s, 0, 0.);
    s.observe(frame(100, 0.), 1, 442., 12, false).unwrap();
    assert_eq!(s.select_point(p.id).unwrap().target_hz, Some(440.));
}
#[test]
fn sampling_rate_change_ends_segment() {
    let s = session();
    push(&s, 0, 0.);
    let mut f = frame(100, 0.);
    f.sample_rate_hz = 44_100.;
    s.observe(f, 1, 440., 12, false).unwrap();
    assert_eq!(s.status().state, PracticeRecordingState::Paused);
}
#[test]
fn frame_validation_rejects_nan_and_wrong_lengths() {
    let s = session();
    let mut f = frame(0, 0.);
    f.spectrum_db[0] = f32::NAN;
    assert!(s.observe(f, 1, 440., 12, false).is_err());
    let mut f = frame(0, 0.);
    f.waveform_min.clear();
    assert!(s.observe(f, 1, 440., 12, false).is_err());
    assert_eq!(s.status().count, 0);
}
#[test]
fn bounded_storage_stops_without_overwriting() {
    let s = session();
    s.core.lock().unwrap().max_points = 2;
    push(&s, 0, 0.);
    push(&s, 100, 0.);
    push(&s, 200, 0.);
    assert_eq!(s.status().count, 2);
    assert_eq!(s.status().state, PracticeRecordingState::Paused);
    assert!(!s.status().reason.is_empty());
}
#[test]
fn payload_limit_is_enforced_before_append() {
    let s = session();
    s.core.lock().unwrap().max_bytes = 100;
    push(&s, 0, 0.);
    assert_eq!(s.status().count, 0);
    assert_eq!(s.status().state, PracticeRecordingState::Paused);
}
#[test]
fn duration_limit_preserves_the_completed_measurement() {
    let s = session();
    s.core.lock().unwrap().max_seconds = 0.15;
    push(&s, 0, 0.);
    push(&s, 100, 0.);
    push(&s, 200, 0.);
    assert_eq!(s.status().count, 2);
    assert_eq!(s.status().state, PracticeRecordingState::Paused);
}
#[test]
fn constant_tone_statistics_use_valid_audio_time() {
    let s = session();
    for i in 0..=10 {
        push(&s, i * 100, 2.);
    }
    let p = s.read_page(0, 64);
    let stats = s.summarize(p[0].id, p[10].id);
    let r = &stats[0];
    assert!(!r.insufficient);
    assert!((r.valid_seconds - 1.).abs() < 1e-6);
    assert!((r.median_cents.unwrap() - 2.).abs() < 1e-6);
    assert_eq!(r.accuracy, Some(1.));
}
#[test]
fn linear_tolerance_crossing_is_time_weighted() {
    let s = session();
    for i in 0..=10 {
        push(&s, i * 100, -10. + i as f64 * 2.);
    }
    let p = s.read_page(0, 64);
    let r = &s.summarize(p[0].id, p[10].id)[0];
    assert!((r.accuracy.unwrap() - 0.5).abs() < 1e-5);
    assert!(r.median_cents.unwrap().abs() < 1e-5);
    assert!((r.low_cents.unwrap() + 8.).abs() < 1e-4);
    assert!((r.high_cents.unwrap() - 8.).abs() < 1e-4);
}
#[test]
fn gaps_reduce_coverage_without_hiding_missing_measurements() {
    let s = session();
    for i in 0..=5 {
        push(&s, i * 100, 0.);
    }
    for i in 15..=20 {
        push(&s, i * 100, 0.);
    }
    let p = s.read_page(0, 64);
    let r = &s.summarize(p[0].id, p.last().unwrap().id)[0];
    assert!((r.coverage - 0.5).abs() < 1e-6);
    assert_eq!(r.accuracy, Some(1.));
}
#[test]
fn reference_tones_are_excluded_from_statistics() {
    let s = session();
    for i in 0..=10 {
        s.observe(frame(i * 100, 0.), 1, 440., 12, true).unwrap();
    }
    let p = s.read_page(0, 64);
    let r = &s.summarize(p[0].id, p[10].id)[0];
    assert!(r.insufficient);
    assert_eq!(r.accuracy, None);
}
#[test]
fn short_selection_has_no_misleading_score() {
    let s = session();
    let a = push(&s, 0, 0.);
    let b = push(&s, 100, 0.);
    assert!(s.summarize(a.id, b.id)[0].insufficient);
}
#[test]
fn snapshots_are_independent_of_session_clearing() {
    let s = session();
    push(&s, 0, 0.);
    let snap = s.add_snapshot("持续段".into()).unwrap();
    s.set_baseline(Some(snap.id)).unwrap();
    s.clear();
    assert_eq!(s.snapshots().len(), 1);
    assert_eq!(s.snapshots()[0].point.frame.spectrum_db, vec![-40.; 64]);
}
#[test]
fn snapshot_count_and_name_are_bounded() {
    let s = session();
    push(&s, 0, 0.);
    for i in 0..8 {
        s.add_snapshot(format!("样本{i}")).unwrap();
    }
    assert!(s.add_snapshot("第九个".into()).is_err());
    assert!(s.add_snapshot(" ".into()).is_err());
}
#[test]
fn incompatible_spectrum_grids_are_rejected() {
    let s = session();
    push(&s, 0, 0.);
    let snap = s.add_snapshot("基准".into()).unwrap();
    s.set_baseline(Some(snap.id)).unwrap();
    let mut f = frame(100, 0.);
    f.wide_spectrum_max_hz = 16_000.;
    s.observe(f, 1, 440., 12, false).unwrap();
    assert!(s.comparison(false, true).is_err());
}
#[test]
fn normalized_comparison_removes_peak_level_explicitly() {
    let s = session();
    push(&s, 0, 0.);
    let snap = s.add_snapshot("基准".into()).unwrap();
    s.set_baseline(Some(snap.id)).unwrap();
    let c = s.comparison(true, false).unwrap();
    assert!(c.normalized);
    assert_eq!(c.current, vec![0.; 64]);
}
#[test]
fn json_round_trip_reopens_complete_historical_frames() {
    let s = session();
    let a = push(&s, 0, 3.);
    push(&s, 100, 5.);
    let snap = s.add_snapshot("样本".into()).unwrap();
    s.set_baseline(Some(snap.id)).unwrap();
    s.select_point(a.id).unwrap();
    let data = s.export_json().unwrap();
    let other = PracticeSession::new();
    other.import_json(data).unwrap();
    assert_eq!(other.status().count, 2);
    assert_eq!(other.display_point().unwrap().id, a.id);
    assert_eq!(other.snapshots()[0].name, "样本");
    assert_eq!(other.status().state, PracticeRecordingState::Paused);
}
#[test]
fn failed_import_preserves_current_session() {
    let s = session();
    push(&s, 0, 0.);
    assert!(s.import_json("{\"schema_version\":999}".into()).is_err());
    assert!(s.import_json("{".into()).is_err());
    assert_eq!(s.status().count, 1);
}
#[test]
fn forged_references_and_pitch_values_are_rejected() {
    let s = session();
    push(&s, 0, 0.);
    let value = s.export_json().unwrap();
    let mut json: serde_json::Value = serde_json::from_str(&value).unwrap();
    json["points"][0]["cents"] = serde_json::json!(999.);
    assert!(s.import_json(json.to_string()).is_err());
    let mut json: serde_json::Value = serde_json::from_str(&value).unwrap();
    json["review_id"] = serde_json::json!(999);
    assert!(s.import_json(json.to_string()).is_err());
}
#[test]
fn csv_nulls_are_empty_and_free_text_is_formula_safe() {
    let s = session();
    let mut c = s.config();
    c.target_name = "=danger".into();
    s.configure(c).unwrap();
    let mut f = frame(0, 0.);
    f.tuner = None;
    f.signal_state = SignalState::Quiet;
    s.observe(f, 1, 440., 12, false).unwrap();
    let csv = s.export_csv(None, None).unwrap();
    assert!(csv.contains("reference_tone_active"));
    assert!(csv.contains("'=danger"));
    assert!(!csv.contains("NaN"));
}

#[test]
fn stale_capture_cannot_mutate_configuration_or_history() {
    let s = session();
    s.observe(frame(0, 0.), 2, 440., 12, false).unwrap();
    let count = s.status().count;
    assert!(s.observe(frame(100, 0.), 1, 442., 19, false).is_err());
    assert_eq!(s.config().a4_hz, 440.);
    assert_eq!(s.config().temperament, 12);
    assert_eq!(s.status().count, count);
}

#[test]
fn unrecorded_display_and_snapshot_keep_their_original_configuration() {
    let s = PracticeSession::new();
    s.lock_reference(0).unwrap();
    push(&s, 0, 3.);
    let mut changed = s.config();
    changed.a4_hz = 442.;
    s.configure(changed).unwrap();
    assert_eq!(s.display_config().a4_hz, 440.);
    let snapshot = s.add_snapshot("修改配置前的实时帧".into()).unwrap();
    assert_eq!(snapshot.config.a4_hz, 440.);
    let saved = s.export_json().unwrap();
    PracticeSession::new().import_json(saved).unwrap();
}

#[test]
fn production_capacity_remains_exportable_without_overwrite() {
    let s = session();
    for i in 0..=MAX_POINTS as u64 {
        push(&s, i * 100, 0.);
    }
    assert_eq!(s.status().state, PracticeRecordingState::Paused);
    assert!(s.status().count > 1000 && s.status().count <= MAX_POINTS as u32);
    let first = s.read_page(0, 1)[0].frame.sample_position;
    let saved = s.export_json().unwrap();
    assert!(saved.len() <= MAX_FILE_BYTES);
    push(&s, 600100, 10.);
    assert_eq!(s.read_page(0, 1)[0].frame.sample_position, first);
    let reopened = PracticeSession::new();
    reopened.import_json(saved).unwrap();
    assert_eq!(reopened.status().count, s.status().count);
}

#[test]
fn measurement_file_restores_selection_and_unrecorded_target() {
    let s = session();
    for i in 0..20 {
        push(&s, i * 100, 3.);
    }
    let points = s.trace_page(0, 6000);
    s.summarize(points[15].id, points[2].id);
    s.lock_reference(12).unwrap();
    let reopened = PracticeSession::new();
    reopened.import_json(s.export_json().unwrap()).unwrap();
    assert_eq!(reopened.config().target_step, Some(12));
    assert_eq!(reopened.status().selection_start_id, Some(points[2].id));
    assert_eq!(reopened.status().selection_end_id, Some(points[15].id));
    assert_eq!(reopened.display_config().target_step, Some(0));
}

#[test]
fn malformed_file_selection_is_rejected_atomically() {
    let s = session();
    push(&s, 0, 0.);
    let mut json: serde_json::Value = serde_json::from_str(&s.export_json().unwrap()).unwrap();
    json["selection"] = serde_json::json!([1, 999]);
    assert!(s.import_json(json.to_string()).is_err());
    assert_eq!(s.status().count, 1);
}

#[test]
fn payload_budget_includes_file_metadata_and_array_separators() {
    let s = session();
    let mut config = s.config();
    config.target_name = "\u{1}".repeat(256);
    config.instrument_id = "\u{1}".repeat(256);
    config.variant_id = "\u{1}".repeat(256);
    s.configure(config).unwrap();
    push(&s, 0, 0.);
    let limit = {
        let mut core = s.core.lock().unwrap();
        let next = core.point(frame(100, 0.), 0.1, false);
        let limit = core.payload_bytes + serde_json::to_vec(&next).unwrap().len() + 4099;
        core.max_bytes = limit;
        limit
    };
    push(&s, 100, 0.);
    assert!(s.export_json().unwrap().len() <= limit);
}

#[test]
fn reopening_cannot_reset_the_existing_duration_budget() {
    let s = session();
    push(&s, 0, 0.);
    push(&s, 599_000, 0.);
    let reopened = PracticeSession::new();
    reopened.import_json(s.export_json().unwrap()).unwrap();
    reopened.start();
    reopened.observe(frame(0, 0.), 2, 440., 12, false).unwrap();
    reopened
        .observe(frame(2000, 0.), 2, 440., 12, false)
        .unwrap();
    assert_eq!(reopened.status().state, PracticeRecordingState::Paused);
    PracticeSession::new()
        .import_json(reopened.export_json().unwrap())
        .unwrap();
}
