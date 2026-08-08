import SwiftUI

struct DesktopSettingsView: View {
    @StateObject private var settings = SettingsStore.shared

    var body: some View {
        MacPageBackground {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("设置")
                        .font(.largeTitle.bold())
                    Text("桌面端独立保存；分析参数即时下发共享引擎")
                        .foregroundStyle(.secondary)
                    MacCard {
                        VStack(alignment: .leading, spacing: 18) {
                            settingsRow(title: "A4 校准") {
                                HStack {
                                    Slider(value: $settings.a4Hz, in: 415...466, step: 0.1)
                                    Text(String(format: "%.1f Hz", settings.a4Hz))
                                        .monospacedDigit()
                                        .frame(width: 88, alignment: .trailing)
                                }
                            }
                            Divider()
                            settingsRow(title: "唱名体系") {
                                Picker("唱名体系", selection: $settings.solfegeSystem) {
                                    ForEach(
                                        [
                                            SolfegeSystem.fixedDo,
                                            .movableDo,
                                            .numbered,
                                            .chinese,
                                        ],
                                        id: \.self
                                    ) {
                                        Text($0.label).tag($0)
                                    }
                                }
                                .labelsHidden()
                            }
                            Divider()
                            settingsRow(title: "调式") {
                                HStack {
                                    Picker("主音", selection: $settings.keyTonicPc) {
                                        ForEach(0..<12, id: \.self) {
                                            Text(Tonic.labels[$0]).tag($0)
                                        }
                                    }
                                    Picker("类别", selection: $settings.keyMode) {
                                        ForEach(
                                            [
                                                ModeKind.major,
                                                .minor,
                                                .gong,
                                                .shang,
                                                .jue,
                                                .zhi,
                                                .yu,
                                            ],
                                            id: \.self
                                        ) {
                                            Text($0.label).tag($0)
                                        }
                                    }
                                }
                                .disabled(settings.solfegeSystem == .fixedDo)
                            }
                            Divider()
                            settingsRow(title: "噪声门限") {
                                HStack {
                                    Slider(
                                        value: $settings.noiseGateDbfs,
                                        in: -60 ... -30,
                                        step: 1
                                    )
                                    Text(String(format: "%.0f dBFS", settings.noiseGateDbfs))
                                        .monospacedDigit()
                                        .frame(width: 88, alignment: .trailing)
                                }
                            }
                            Divider()
                            settingsRow(title: "专业模式") {
                                Toggle("启用律制选择和增强分析", isOn: $settings.proMode)
                            }
                            if settings.proMode {
                                Divider()
                                settingsRow(title: "律制") {
                                    Picker("律制", selection: $settings.temperament) {
                                        ForEach([12, 19, 24, 31], id: \.self) {
                                            Text("\($0)-TET").tag($0)
                                        }
                                    }
                                    .labelsHidden()
                                }
                            }
                            Divider()
                            settingsRow(title: "主题") {
                                Picker("主题", selection: $settings.themeMode) {
                                    ForEach(ThemeMode.allCases, id: \.self) {
                                        Text($0.label).tag($0)
                                    }
                                }
                                .pickerStyle(.segmented)
                                .labelsHidden()
                            }
                            Divider()
                            settingsRow(title: "触觉反馈") {
                                VStack(alignment: .leading) {
                                    Toggle("准音提示", isOn: $settings.hapticsEnabled)
                                    Text("macOS 无对应触觉硬件时静默不执行。")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
                .padding(24)
            }
        }
    }

    private func settingsRow<Content: View>(
        title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        HStack(alignment: .top, spacing: 24) {
            Text(title)
                .font(.headline)
                .frame(width: 130, alignment: .leading)
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
