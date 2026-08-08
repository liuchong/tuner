# TUNAR documentation / 吐呐文档

The English and Simplified Chinese documents describe the same product contract. Code
changes that alter behavior must update both versions in the same commit.

英文与简体中文文档描述同一份产品合同。任何改变行为的代码提交，都必须同步更新两种语言。

| Topic | English | 简体中文 |
|---|---|---|
| Shared core and UniFFI contract | [spec-core](en/spec-core.md) | [spec-core](spec-core.md) |
| Instruments and tunings | [spec-instruments](en/spec-instruments.md) | [spec-instruments](spec-instruments.md) |
| UI behavior | [spec-ui](en/spec-ui.md) | [spec-ui](spec-ui.md) |
| Audio pipeline | [spec-audio](en/spec-audio.md) | [spec-audio](spec-audio.md) |
| Aurora design system | [design-system](en/design-system.md) | [design-system](design-system.md) |
| Native macOS app | [macos-native](en/macos-native.md) | [macos-native](macos-native.md) |
| Roadmap | [roadmap](en/roadmap.md) | [roadmap](roadmap.md) |

Brand rule: the product is **TUNAR** in English and **吐呐** in Simplified Chinese.
Technical identifiers remain `tuner` / `Tuner` where changing them would break upgrades,
generated bindings, or persisted data.
