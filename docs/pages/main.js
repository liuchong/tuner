/* TUNAR · 吐呐 — 中英切换 */
(function () {
  "use strict";

  var en = {
    "nav.features": "Features",
    "nav.platforms": "Platforms",
    "nav.arch": "Architecture",
    "nav.docs": "Docs",
    "hero.tagline": "Breathe with sound. Tune with confidence.",
    "hero.subtitle": "A cross-platform tuner & metronome — shared Rust core × native UI",
    "hero.star": "View on GitHub",
    "hero.docs": "Read the specs",
    "hero.badge.harmonic": "harmonic model",
    "features.title": "Features",
    "features.tuner.title": "Universal tuner",
    "features.tuner.body": "Pitch accuracy within ±0.5 cent via YIN plus harmonic-model refinement. Adjustable noise gate, two-frame confirmation and hysteresis. Aurora dial with a single responsive needle. Fixed Do, movable Do, numbered and gongche-style notation.",
    "features.pro.title": "Pro analysis",
    "features.pro.body": "Musical/full-range FFT, 12-second pitch trace, live waveform envelope, measured peak labels, waterfall history and chord detection. Reference tones for 12/19/24/31 equal divisions with A4 calibration.",
    "features.instrument.title": "Instrument tuning",
    "features.instrument.body": "Guitar, ukulele and guqin tunings. Zhudi, dongxiao and shakuhachi fingering charts. Automatic target matching or manually locked strings/notes.",
    "features.metro.title": "Metronome",
    "features.metro.body": "Sample-accurate timing from the Rust engine with multiple tick sounds. Pendulum visuals and haptics, sharing one audio path with the tuner.",
    "platforms.title": "Platforms",
    "platforms.android": "Kotlin + Jetpack Compose with UniFFI Kotlin bindings and native libraries for all ABIs.",
    "platforms.ios": "Native SwiftUI app, statically linking the Rust core via XCFramework.",
    "platforms.macos": "macOS 14+ SwiftUI desktop app with a five-destination sidebar.",
    "arch.title": "Architecture",
    "arch.uniffi": "UniFFI bindings (interface as contract)",
    "arch.core": "Rust shared core: DSP / solfege / presets / metronome engine",
    "arch.note": "All business logic lives in the Rust core. Native layers only handle UI, microphone capture, metronome playback and lifecycle. The audio callback path is allocation-free and lock-free.",
    "docs.title": "Documentation",
    "docs.core": "Core spec (UniFFI contract)",
    "docs.ui": "UI spec",
    "docs.audio": "Audio spec",
    "docs.inst": "Instruments spec",
    "docs.design": "Aurora design system",
    "docs.roadmap": "Roadmap",
    "docs.en": "English specs",
    "footer.made": "Rust core × native UI"
  };

  var toggle = document.getElementById("lang-toggle");
  var zh = {};

  function collectZh() {
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      zh[el.getAttribute("data-i18n")] = el.textContent;
    });
  }

  function apply(lang) {
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      var key = el.getAttribute("data-i18n");
      var text = lang === "en" ? en[key] : zh[key];
      if (text) el.textContent = text;
    });
    document.documentElement.lang = lang === "en" ? "en" : "zh-CN";
    document.title = lang === "en"
      ? "TUNAR — Cross-platform tuner & metronome"
      : "TUNAR · 吐呐 — 跨平台调音器与节拍器";
    toggle.textContent = lang === "en" ? "中文" : "EN";
    try { localStorage.setItem("tunar-lang", lang); } catch (e) { /* ignore */ }
  }

  collectZh();
  var saved = "zh";
  try { saved = localStorage.getItem("tunar-lang") || "zh"; } catch (e) { /* ignore */ }
  apply(saved);

  toggle.addEventListener("click", function () {
    apply(document.documentElement.lang === "en" ? "zh" : "en");
  });
})();
