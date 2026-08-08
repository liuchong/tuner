import SwiftUI

/// 设置页（spec-ui §4）。
struct SettingsView: View {
    @Environment(\.lumen) private var palette
    @StateObject private var settings = SettingsStore.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Lumen.Spacing.lg) {
                // A4 校准
                SectionView(title: "A4 校准") {
                    HStack {
                        StepButton("-1") { settings.a4Hz = max(415, settings.a4Hz - 1) }
                        Slider(value: $settings.a4Hz, in: 415...466)
                        StepButton("+1") { settings.a4Hz = min(466, settings.a4Hz + 1) }
                    }
                    Text(String(format: "%.1f Hz", settings.a4Hz))
                        .font(Lumen.readoutValue)
                        .foregroundStyle(palette.accent)
                }

                // 唱名体系
                SectionView(title: "唱名体系") {
                    Picker("唱名体系", selection: $settings.solfegeSystem) {
                        ForEach([SolfegeSystem.fixedDo, .movableDo, .numbered, .chinese], id: \.self) {
                            Text($0.label).tag($0)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(palette.inkPrimary)
                }

                // 调式（固定 Do 时禁用）
                SectionView(title: settings.solfegeSystem == .fixedDo ? "调式（固定 Do 时无需设置）" : "调式") {
                    HStack(spacing: Lumen.Spacing.sm) {
                        Picker("主音", selection: $settings.keyTonicPc) {
                            ForEach(0..<12, id: \.self) { pc in
                                Text(Tonic.labels[pc]).tag(pc)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(palette.inkPrimary)
                        .disabled(settings.solfegeSystem == .fixedDo)
                        Picker("调式类别", selection: $settings.keyMode) {
                            ForEach([ModeKind.major, .minor, .gong, .shang, .jue, .zhi, .yu], id: \.self) {
                                Text($0.label).tag($0)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(palette.inkPrimary)
                        .disabled(settings.solfegeSystem == .fixedDo)
                    }
                }

                // 灵敏度（噪声门限）
                SectionView(title: "灵敏度（噪声门限）") {
                    Slider(value: $settings.noiseGateDbfs, in: -60 ... -30)
                    Text(String(format: "%.0f dBFS（越高越不敏感）", settings.noiseGateDbfs))
                        .font(Lumen.readoutValue)
                        .foregroundStyle(palette.accent)
                }

                // 专业版模式
                SectionView(title: "专业版模式") {
                    Toggle("PRO（律制选择 / 频谱分析增强）", isOn: $settings.proMode)
                        .font(Lumen.label)
                        .tint(palette.accent)
                }

                // 律制（PRO 开启时可见）
                if settings.proMode {
                    SectionView(title: "律制") {
                        Picker("平均律", selection: $settings.temperament) {
                            ForEach([12, 19, 24, 31], id: \.self) { n in
                                Text("\(n)-TET").tag(n)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(palette.inkPrimary)
                    }
                }

                // 触觉反馈
                SectionView(title: "触觉反馈") {
                    Toggle("准音震动提示（进入准音区 / 准音保持）", isOn: $settings.hapticsEnabled)
                        .font(Lumen.label)
                        .tint(palette.accent)
                }

                // 主题
                SectionView(title: "主题") {
                    Picker("主题", selection: $settings.themeMode) {
                        ForEach(ThemeMode.allCases, id: \.self) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .padding(Lumen.Spacing.page)
        }
        .background(palette.bgCanvas.ignoresSafeArea())
    }
}

/// 设置分组容器。
struct SectionView<Content: View>: View {
    @Environment(\.lumen) private var palette
    var title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: Lumen.Spacing.sm) {
            Text(title)
                .font(Lumen.caption)
                .foregroundStyle(palette.inkSecondary)
            content()
        }
    }
}
