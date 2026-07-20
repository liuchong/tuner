# Rust core 开发规则

适用于 `core/` 目录。

## 硬性约束

1. **实时安全**：`TunerEngine::feed`、`Metronome::render` 及其调用链上：
   - 禁止堆分配（Vec 增长、Box、String、format! 等）——构造时预分配，运行期复用缓冲；
   - 禁止锁（Mutex/RwLock）；跨线程用无锁结构或所有权转移；
   - 禁止 `unwrap`/`expect`/panic 路径，错误用 Option/Result 返回。
2. 平台无关：不得使用 std::time、std::fs、线程 API；时间一律以采样数表示。
3. 采样率不得假设为定值，一律从配置读取。

## 测试要求（改 core 必须 `cargo test` 全绿）

- 音高检测：合成信号（基频+3 泛音+SNR 20dB 噪声）下，80Hz–1500Hz 扫频误差 ≤ ±0.5 cent。
- 音名/唱名换算：边界值（C4、A4、B#4/Cb5 等等价音）用例齐全。
- 节拍器：连续渲染 1000 个 tick，位置误差 < 1 采样；tempo 变更即时生效。
- 新增算法/换算函数必须带单测。

## 风格

- `cargo fmt`、`cargo clippy -- -D warnings` 零告警。
- 模块划分与 `docs/spec-core.md` §2 保持一致。
