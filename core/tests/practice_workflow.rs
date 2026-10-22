use std::f64::consts::PI;
use tunar_core::api::*;

fn capture(session: &PracticeSession, cents: f64, count: u64) {
    let engine = TunarEngine::new(TunarConfig {
        sample_rate: 44_100.,
        frame_hop_samples: 1024,
        a4_hz: 440.,
        noise_gate_dbfs: -45.,
        solfege: SolfegeSystem::Numbered,
        key: KeyMode {
            tonic_pc: 0,
            mode: ModeKind::Major,
        },
        temperament: 12,
    });
    let frequency = 440. * 2_f64.powf(cents / 1200.);
    for frame_index in 0..count {
        let pcm = (0..2048)
            .map(|i| {
                (0.5 * (2. * PI * frequency * (frame_index * 1024 + i) as f64 / 44100.).sin())
                    as f32
            })
            .collect();
        let frame = engine.analyze(pcm);
        session.observe(frame, 1, 440., 12, false).unwrap();
    }
}
#[test]
fn real_dsp_measurement_review_and_file_round_trip() {
    let session = PracticeSession::new();
    session.lock_reference(0).unwrap();
    session.start();
    capture(&session, 3., 100);
    session.finish();
    let trace = session.trace_page(0, 6000);
    assert!(trace.len() > 10);
    let valid = trace.iter().find(|p| p.valid).unwrap();
    assert!((valid.cents.unwrap() - 3.).abs() < 0.6);
    let historical = session.select_point(valid.id).unwrap();
    let snapshot = session.add_snapshot("真实DSP".into()).unwrap();
    session.set_baseline(Some(snapshot.id)).unwrap();
    assert!(!session.comparison(false, false).unwrap().current.is_empty());
    let json = session.export_json().unwrap();
    // 可选的原生验收夹具，来源为上面的真实DSP结果，不进入产品或Git。
    if let Ok(directory) = std::env::var("TUNAR_ACCEPTANCE_DIR") {
        let directory = std::path::Path::new(&directory);
        std::fs::create_dir_all(directory).unwrap();
        std::fs::write(directory.join("TUNAR-acceptance.json"), &json).unwrap();
    }

    let reopened = PracticeSession::new();
    reopened.import_json(json).unwrap();
    assert_eq!(
        reopened.display_point().unwrap().frame.sample_position,
        historical.frame.sample_position
    );
    assert!(reopened.export_csv(None, None).unwrap().lines().count() > 10);
}
#[test]
fn two_sessions_do_not_share_targets_or_history() {
    let a = PracticeSession::new();
    let b = PracticeSession::new();
    a.lock_reference(0).unwrap();
    b.lock_reference(12).unwrap();
    a.start();
    capture(&a, 0., 20);
    assert!(a.status().count > 0);
    assert_eq!(b.status().count, 0);
    assert_eq!(b.config().target_step, Some(12));
}

#[test]
fn locked_target_tracks_real_dsp_across_semitone_octave_and_a4_change() {
    let engine = TunarEngine::new(TunarConfig {
        sample_rate: 44_100.,
        frame_hop_samples: 1024,
        a4_hz: 440.,
        noise_gate_dbfs: -45.,
        solfege: SolfegeSystem::Numbered,
        key: KeyMode {
            tonic_pc: 0,
            mode: ModeKind::Major,
        },
        temperament: 12,
    });
    let session = PracticeSession::new();
    session.lock_reference(0).unwrap();
    session.start();
    let mut position = 0u64;
    for (block, cents) in [3., -7., 100., 1200., 0.].into_iter().enumerate() {
        let a4 = if block == 4 { 442. } else { 440. };
        engine.set_a4(a4);
        let frequency = a4 * 2_f64.powf(cents / 1200.);
        let mut measured = None;
        for _ in 0..100 {
            let pcm = (0..2048)
                .map(|i| {
                    (0.5 * (2. * PI * frequency * (position + i) as f64 / 44100.).sin()) as f32
                })
                .collect();
            let point = session
                .observe(engine.analyze(pcm), 1, a4, 12, false)
                .unwrap();
            if point.valid {
                measured = point.cents;
            }
            position += 1024;
        }
        assert!((measured.unwrap() - cents).abs() < 0.6, "block {block}");
    }
    let first = session
        .trace_page(0, 6000)
        .into_iter()
        .find(|p| p.valid)
        .unwrap();
    let trace = session.trace_page(0, 6000);
    session.summarize(trace[2].id, trace[18].id);
    session.select_point(first.id).unwrap();
    assert_eq!(session.display_config().a4_hz, 440.);
    let snapshot = session.add_snapshot("3c DSP基准".into()).unwrap();
    session.set_baseline(Some(snapshot.id)).unwrap();
    if let Ok(directory) = std::env::var("TUNAR_ACCEPTANCE_DIR") {
        let directory = std::path::Path::new(&directory);
        std::fs::create_dir_all(directory).unwrap();
        std::fs::write(
            directory.join("TUNAR-acceptance-range.json"),
            session.export_json().unwrap(),
        )
        .unwrap();
    }
}
