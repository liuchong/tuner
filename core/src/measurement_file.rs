//! 版本化测量文件；纯编解码，不访问平台文件系统。
use crate::practice::*;
use crate::signal::SignalState;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

/// 保存会话所需的全部不可变测量和比较基准。
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct MeasurementFile {
    pub(crate) schema_version: u32,
    pub(crate) points: Vec<PracticePoint>,
    pub(crate) segments: Vec<PracticeSegment>,
    pub(crate) snapshots: Vec<PracticeSnapshot>,
    pub(crate) baseline_id: Option<u32>,
    pub(crate) review_id: Option<u64>,
    #[serde(default)]
    pub(crate) current_config: Option<PracticeConfig>,
    #[serde(default)]
    pub(crate) selection: Option<(u64, u64)>,
}
pub(crate) fn encode(c: &SessionCore) -> Result<String, PracticeError> {
    let file = MeasurementFile {
        schema_version: 1,
        points: c.points.clone(),
        segments: c.segments.clone(),
        snapshots: c.snapshots.clone(),
        baseline_id: c.baseline_id,
        review_id: c.review_id,
        current_config: Some(c.config.clone()),
        selection: c.selection,
    };
    let data = serde_json::to_string(&file).map_err(|_| PracticeError::InvalidFile)?;
    if data.len() > MAX_FILE_BYTES {
        return Err(PracticeError::Capacity);
    }
    Ok(data)
}
pub(crate) fn decode(contents: &str) -> Result<MeasurementFile, PracticeError> {
    if contents.len() > MAX_FILE_BYTES {
        return Err(PracticeError::Capacity);
    }
    #[derive(Deserialize)]
    struct Version {
        schema_version: u32,
    }
    let version: Version =
        serde_json::from_str(contents).map_err(|_| PracticeError::InvalidFile)?;
    if version.schema_version != 1 {
        return Err(PracticeError::UnsupportedVersion);
    }
    let file: MeasurementFile =
        serde_json::from_str(contents).map_err(|_| PracticeError::InvalidFile)?;
    if file.points.len() > MAX_POINTS
        || file.segments.len() > MAX_POINTS
        || file.snapshots.len() > 8
    {
        return Err(PracticeError::Capacity);
    }
    if file
        .current_config
        .as_ref()
        .is_some_and(|config| !config.validate())
    {
        return Err(PracticeError::InvalidFile);
    }
    let mut segment_ids = HashSet::new();
    if file.segments.iter().any(|s| {
        s.id == 0 || s.config_id != s.id || !segment_ids.insert(s.id) || !s.config.validate()
    }) {
        return Err(PracticeError::InvalidFile);
    }
    let mut ids = HashSet::new();
    let mut last_time = None;
    let mut last_id = 0;
    for p in &file.points {
        let config = file
            .segments
            .iter()
            .find(|s| s.id == p.segment_id && s.config_id == p.config_id)
            .ok_or(PracticeError::InvalidFile)?;
        if p.id == 0
            || p.id <= last_id
            || !ids.insert(p.id)
            || !valid_point(p, &config.config)
            || last_time.is_some_and(|t| p.time_seconds < t)
        {
            return Err(PracticeError::InvalidFile);
        }
        last_time = Some(p.time_seconds);
        last_id = p.id;
    }
    if let (Some(a), Some(b)) = (file.points.first(), file.points.last())
        && b.time_seconds - a.time_seconds > 600.000001
    {
        return Err(PracticeError::InvalidFile);
    }
    let mut snapshots = HashSet::new();
    for s in &file.snapshots {
        if s.id == 0
            || !snapshots.insert(s.id)
            || s.name.trim().is_empty()
            || s.name.chars().count() > 80
            || !s.config.validate()
            || !valid_point(&s.point, &s.config)
        {
            return Err(PracticeError::InvalidFile);
        }
    }
    if file
        .selection
        .is_some_and(|(a, b)| a > b || !ids.contains(&a) || !ids.contains(&b))
    {
        return Err(PracticeError::InvalidFile);
    }
    if file.review_id.is_some_and(|id| !ids.contains(&id))
        || file.baseline_id.is_some_and(|id| !snapshots.contains(&id))
    {
        return Err(PracticeError::InvalidFile);
    }
    Ok(file)
}
fn valid_point(p: &PracticePoint, config: &PracticeConfig) -> bool {
    if !valid_frame(&p.frame)
        || !p.time_seconds.is_finite()
        || p.time_seconds < 0.
        || p.target_name.len() > 256
    {
        return false;
    }
    let (target, name) = config.target(p.frame.tuner.as_ref());
    let close = |a: Option<f64>, b: Option<f64>| match (a, b) {
        (None, None) => true,
        (Some(x), Some(y)) => x.is_finite() && y.is_finite() && (x - y).abs() < 1e-6,
        _ => false,
    };
    let cents = p
        .frame
        .tuner
        .as_ref()
        .and_then(|e| target.map(|t| 1200. * (e.freq_hz / t).log2()));
    let valid = p.frame.signal_state == SignalState::Tracking
        && !p.frame.is_held
        && !p.reference_active
        && cents.is_some();
    close(target, p.target_hz) && close(cents, p.cents) && name == p.target_name && valid == p.valid
}
fn text_cell(value: &str) -> String {
    let trimmed = value.trim_start();
    let guarded = if trimmed.starts_with(['=', '+', '-', '@', '\t', '\r']) {
        format!("'{value}")
    } else {
        value.to_string()
    };
    format!("\"{}\"", guarded.replace('"', "\"\""))
}
pub(crate) fn csv(
    c: &SessionCore,
    start: Option<u64>,
    end: Option<u64>,
) -> Result<String, PracticeError> {
    let (lo, hi) = (start.unwrap_or(0), end.unwrap_or(u64::MAX));
    if lo > hi {
        return Err(PracticeError::InvalidInput);
    }
    let mut out = String::from(
        "segment_id,time_seconds,state,is_held,freq_hz,target_hz,cents,input_dbfs,clarity,config_id,reference_tone_active,target_name\r\n",
    );
    for p in c.points.iter().filter(|p| p.id >= lo && p.id <= hi) {
        let optional = |v: Option<f64>| v.map(|v| v.to_string()).unwrap_or_default();
        let event = p
            .frame
            .tuner
            .as_ref()
            .filter(|_| p.frame.signal_state == SignalState::Tracking && !p.frame.is_held);
        let row = format!(
            "{},{},{:?},{},{},{},{},{},{},{},{},{}\r\n",
            p.segment_id,
            p.time_seconds,
            p.frame.signal_state,
            p.frame.is_held,
            optional(event.map(|e| e.freq_hz)),
            optional(p.target_hz),
            optional(if p.valid { p.cents } else { None }),
            p.frame.input_level_dbfs,
            optional(event.map(|e| f64::from(e.clarity))),
            p.config_id,
            p.reference_active,
            text_cell(&p.target_name)
        );
        if out.len() + row.len() > MAX_FILE_BYTES {
            return Err(PracticeError::Capacity);
        }
        out.push_str(&row);
    }
    Ok(out)
}
