import SwiftUI

struct DesktopMetronomeView: View {
    @StateObject private var vm = MetronomeViewModel()

    var body: some View {
        MacPageBackground {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("节拍器")
                        .font(.largeTitle.bold())
                    Text("节奏和采样位置由共享 Rust 核心调度")
                        .foregroundStyle(.secondary)
                    HStack(alignment: .top, spacing: 16) {
                        tempoCard
                        soundCard
                    }
                }
                .padding(24)
            }
        }
        .onDisappear { vm.pause() }
    }

    private var tempoCard: some View {
        MacCard {
            VStack(spacing: 18) {
                HStack {
                    Button { vm.adjustBpm(-1) } label: {
                        Image(systemName: "minus")
                    }
                    Text(String(format: "%.0f", vm.bpm))
                        .font(.system(size: 74, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .frame(minWidth: 160)
                    Button { vm.adjustBpm(1) } label: {
                        Image(systemName: "plus")
                    }
                }
                Text("BPM").foregroundStyle(.secondary)
                Slider(
                    value: Binding(
                        get: { vm.bpm },
                        set: { value in vm.setBpm(value) }
                    ),
                    in: MetronomeViewModel.bpmMin...MetronomeViewModel.bpmMax,
                    step: 1
                )
                Picker(
                    "拍号",
                    selection: Binding(
                        get: { "\(vm.beatsPerBar)/\(vm.beatUnit)" },
                        set: { value in
                            let parts = value.split(separator: "/").compactMap { Int($0) }
                            if parts.count == 2 {
                                vm.setTimeSignature(beats: parts[0], unit: parts[1])
                            }
                        }
                    )
                ) {
                    ForEach(
                        Array(MetronomeViewModel.commonTimeSignatures.enumerated()),
                        id: \.offset
                    ) { _, signature in
                        let (beats, unit) = signature
                        Text("\(beats)/\(unit)").tag("\(beats)/\(unit)")
                    }
                }
                HStack(spacing: 8) {
                    ForEach(vm.accents.indices, id: \.self) { index in
                        Button {
                            vm.cycleAccent(index)
                        } label: {
                            VStack(spacing: 5) {
                                Circle()
                                    .fill(
                                        index == vm.currentBeat
                                            ? MacTheme.accent
                                            : vm.accents[index].color
                                    )
                                    .frame(width: 22, height: 22)
                                Text("\(index + 1)")
                                    .font(.caption.monospacedDigit())
                            }
                            .frame(minWidth: 42, minHeight: 48)
                        }
                        .buttonStyle(.plain)
                    }
                }
                HStack {
                    Button("Tap", action: vm.tap)
                    Button {
                        vm.togglePlay()
                    } label: {
                        Label(
                            vm.playing ? "停止" : "开始",
                            systemImage: vm.playing ? "stop.fill" : "play.fill"
                        )
                        .frame(minWidth: 110)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var soundCard: some View {
        MacCard {
            VStack(alignment: .leading, spacing: 16) {
                Text("音色")
                    .font(.title2.bold())
                Picker("强拍", selection: $vm.accentSound) {
                    ForEach(TickSoundKind.allCases, id: \.self) {
                        Text($0.label).tag($0)
                    }
                }
                Picker("普通拍", selection: $vm.normalSound) {
                    ForEach(TickSoundKind.allCases, id: \.self) {
                        Text($0.label).tag($0)
                    }
                }
                Divider()
                Label("点击圆点循环设置强拍、普通拍和静音。", systemImage: "info.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Label("离开节拍器页面后自动停止播放。", systemImage: "stop.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

private extension TickAccent {
    var color: Color {
        switch self {
        case .accent: MacTheme.tuneOff
        case .normal: Color.secondary.opacity(0.55)
        case .muted: Color.secondary.opacity(0.15)
        }
    }
}
