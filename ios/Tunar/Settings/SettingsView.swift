import SwiftUI

/// 设置页（spec-ui §4）：按用途分组成卡片，每行左侧说明、右侧控件或当前值。
struct SettingsView: View {
    @Environment(\.lumen) private var palette
    @StateObject private var settings = SettingsStore.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Lumen.Spacing.lg) {
                Text("设置")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(palette.inkPrimary)
                    .padding(.top, Lumen.Spacing.sm)

                SectionView(title: "校准") {
                    SettingRow("A4 标准音") {
                        Text(String(format: "%.1f Hz", settings.a4Hz))
                            .font(Lumen.readoutValue.monospacedDigit())
                            .foregroundStyle(palette.accent)
                    }
                    HStack(spacing: Lumen.Spacing.sm) {
                        StepButton("-1") { settings.a4Hz = max(415, settings.a4Hz - 1) }
                        Slider(value: $settings.a4Hz, in: 415...466)
                            .tint(palette.accent)
                            .accessibilityLabel("A4 标准音")
                        StepButton("+1") { settings.a4Hz = min(466, settings.a4Hz + 1) }
                    }
                }

                SectionView(title: "唱名与调式") {
                    SettingRow("唱名体系") {
                        Picker("唱名体系", selection: $settings.solfegeSystem) {
                            ForEach([SolfegeSystem.fixedDo, .movableDo, .numbered, .chinese], id: \.self) {
                                Text($0.label).tag($0)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(palette.accent)
                    }
                    SettingDivider()
                    SettingRow("调式", footnote: settings.solfegeSystem == .fixedDo ? "固定 Do 时无需设置" : nil) {
                        HStack(spacing: 0) {
                            Picker("主音", selection: $settings.keyTonicPc) {
                                ForEach(0..<12, id: \.self) { pc in
                                    Text(Tonic.labels[pc]).tag(pc)
                                }
                            }
                            Picker("调式类别", selection: $settings.keyMode) {
                                ForEach([ModeKind.major, .minor, .gong, .shang, .jue, .zhi, .yu], id: \.self) {
                                    Text($0.label).tag($0)
                                }
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(palette.accent)
                        .disabled(settings.solfegeSystem == .fixedDo)
                    }
                }

                SectionView(title: "识别") {
                    SettingRow("灵敏度", footnote: "噪声门限，越高越不易误触发") {
                        Text(String(format: "%.0f dBFS", settings.noiseGateDbfs))
                            .font(Lumen.readoutValue.monospacedDigit())
                            .foregroundStyle(palette.accent)
                    }
                    Slider(value: $settings.noiseGateDbfs, in: -60 ... -30)
                        .tint(palette.accent)
                        .accessibilityLabel("灵敏度")
                }

                SectionView(title: "专业版") {
                    Toggle(isOn: $settings.proMode) {
                        SettingLabel("PRO 模式", footnote: "律制选择与频谱分析增强")
                    }
                    .tint(palette.accent)
                    if settings.proMode {
                        SettingDivider()
                        SettingRow("律制") {
                            Picker("平均律", selection: $settings.temperament) {
                                ForEach([12, 19, 24, 31], id: \.self) { n in
                                    Text("\(n)-TET").tag(n)
                                }
                            }
                            .pickerStyle(.menu)
                            .tint(palette.accent)
                        }
                    }
                }

                SectionView(title: "反馈与外观") {
                    Toggle(isOn: $settings.hapticsEnabled) {
                        SettingLabel("准音震动", footnote: "进入准音区与保持准音时轻震提示")
                    }
                    .tint(palette.accent)
                    SettingDivider()
                    SettingLabel("主题")
                    Picker("主题", selection: $settings.themeMode) {
                        ForEach(ThemeMode.allCases, id: \.self) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .padding(Lumen.Spacing.page)
            .animation(.easeInOut(duration: 0.2), value: settings.proMode)
        }
        .background(palette.bgCanvas.ignoresSafeArea())
    }
}

/// 设置分组：小标题 + 圆角卡片。
struct SectionView<Content: View>: View {
    @Environment(\.lumen) private var palette
    var title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: Lumen.Spacing.sm) {
            Text(title)
                .font(Lumen.caption.weight(.semibold))
                .foregroundStyle(palette.inkSecondary)
                .padding(.leading, 4)
            VStack(alignment: .leading, spacing: 12) {
                content()
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(palette.lineSubtle, lineWidth: 1))
        }
    }
}

private struct SettingLabel: View {
    @Environment(\.lumen) private var palette
    let title: String
    let footnote: String?

    init(_ title: String, footnote: String? = nil) {
        self.title = title
        self.footnote = footnote
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(Lumen.label)
                .foregroundStyle(palette.inkPrimary)
            if let footnote {
                Text(footnote)
                    .font(Lumen.caption)
                    .foregroundStyle(palette.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct SettingRow<Trailing: View>: View {
    let title: String
    let footnote: String?
    @ViewBuilder var trailing: () -> Trailing

    init(_ title: String, footnote: String? = nil, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.title = title
        self.footnote = footnote
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .center, spacing: Lumen.Spacing.md) {
            SettingLabel(title, footnote: footnote)
            Spacer(minLength: 0)
            trailing()
        }
        .frame(minHeight: 36)
    }
}

private struct SettingDivider: View {
    @Environment(\.lumen) private var palette

    var body: some View {
        Rectangle()
            .fill(palette.lineSubtle)
            .frame(height: 1)
    }
}
