# 文档同步规则（硬性）

代码与文档不一致是缺陷。以下变更必须同步更新对应文档：

| 代码变更 | 必须同步更新 |
|---|---|
| UniFFI 接口（core/src/api.rs 任何签名/类型） | `docs/spec-core.md` 附录 A |
| 音高检测参数、精度指标 | `docs/spec-core.md` §3、§8 |
| 唱名体系/调式规则 | `docs/spec-core.md` §5 |
| 乐器预设、定弦表、指法表 | `docs/spec-instruments.md` |
| 面板交互行为 | `docs/spec-ui.md` |
| 视觉/动效/触觉设计（色彩、字体、组件外观） | `docs/design-system.md` |
| 音频管线（采样率/缓冲/混音/保活） | `docs/spec-audio.md` |
| 构建命令、目录结构 | `AGENTS.md` |
| 里程碑状态 | `docs/roadmap.md` |

若实现时发现 spec 不合理：**先改 spec 说明理由，再改代码**，禁止代码先行。
