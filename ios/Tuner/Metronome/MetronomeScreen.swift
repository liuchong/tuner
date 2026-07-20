import SwiftUI

/// 节拍器面板（design-system §6.7）。
struct MetronomeView: View {
    @Environment(\.lumen) private var palette
    @StateObject private var vm = MetronomeViewModel()

    var body: some View {
        AuroraBackground(tuneCents: nil) {
            VStack(spacing: Lumen.Spacing.md) {
                // BPM 环 + 垂直拨轮
                BpmRing(bpm: vm.bpm) { delta in
                    vm.adjustBpm(delta.rounded())
                }
                .frame(height: 220)
                .overlay(alignment: .center) {
                    VStack {
                        Text("\(Int(vm.bpm))")
                            .font(Lumen.displayBpm)
                            .foregroundStyle(palette.accent)
                        Text("BPM（上下拖动调节）")
                            .font(Lumen.caption)
                            .foregroundStyle(palette.inkSecondary)
                    }
                }

                // 摆锤
                Pendulum(playing: vm.playing, bpm: vm.bpm, beatUnit: vm.beatUnit)
                    .frame(height: 96)

                // 滑杆 + 步进 + tap
                HStack {
                    StepButton("-5") { vm.adjustBpm(-5) }
                    StepButton("-1") { vm.adjustBpm(-1) }
                    Slider(value: Binding(
                        get: { vm.bpm },
                        set: { vm.setBpm($0) }
                    ), in: 30...250)
                    StepButton("+1") { vm.adjustBpm(1) }
                    StepButton("+5") { vm.adjustBpm(5) }
                }
                Button("TAP 测速") { vm.tap() }
                    .font(Lumen.label)
                    .foregroundStyle(palette.accent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(palette.bgSurface, in: Capsule())

                // 拍号选择
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(MetronomeViewModel.commonTimeSignatures, id: \.0) { sig in
                            let selected = vm.beatsPerBar == sig.0 && vm.beatUnit == sig.1
                            Button { vm.setTimeSignature(beats: sig.0, unit: sig.1) } label: {
                                Text("\(sig.0)/\(sig.1)")
                                    .font(Lumen.label)
                                    .foregroundStyle(selected ? palette.bgCanvas : palette.inkPrimary)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 8)
                                    .background(selected ? palette.accent : palette.bgSurface, in: Capsule())
                            }
                        }
                    }
                }

                // 重音圆点行（重拍实心/普通半透/静音空心）
                HStack(spacing: 12) {
                    ForEach(vm.accents.indices, id: \.self) { i in
                        let isCurrent = vm.playing && vm.currentBeat == i
                        BeatDot(
                            accent: vm.accents[i],
                            current: isCurrent
                        ) { vm.cycleAccent(i) }
                    }
                }
                // 小节进度
                ProgressView(value: vm.playing && vm.currentBeat >= 0
                    ? Double(vm.currentBeat + 1) / Double(vm.beatsPerBar)
                    : 0)
                    .tint(palette.accent)

                // 音色选择（重拍/弱拍）
                HStack(spacing: Lumen.Spacing.sm) {
                    SoundPicker(label: "重拍音色", selection: $vm.accentSound)
                    SoundPicker(label: "弱拍音色", selection: $vm.normalSound)
                }

                Spacer()

                // 播放/停止大按钮（全宽 72dp，播放中 tune/off 红）
                Button {
                    vm.togglePlay()
                } label: {
                    Text(vm.playing ? "停止" : "播放")
                        .font(.title3.bold())
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 72)
                        .background(
                            vm.playing ? palette.tuneOff : palette.accent,
                            in: RoundedRectangle(cornerRadius: 28)
                        )
                }
            }
            .padding(Lumen.Spacing.page)
        }
    }
}

/// 拍点（重拍实心/普通半透/静音空心）。
struct BeatDot: View {
    @Environment(\.lumen) private var palette
    var accent: TickAccent
    var current: Bool
    var onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            Group {
                switch accent {
                case .accent:
                    Circle().fill(current ? palette.accent : palette.inkPrimary)
                case .normal:
                    Circle().fill((current ? palette.accent : palette.inkPrimary).opacity(0.4))
                case .muted:
                    Circle().stroke(current ? palette.accent : palette.inkSecondary, lineWidth: 1.5)
                }
            }
            .frame(width: current ? 18 : 12, height: current ? 18 : 12)
        }
    }
}

/// 步进按钮。
struct StepButton: View {
    @Environment(\.lumen) private var palette
    var text: String
    var action: () -> Void

    init(_ text: String, action: @escaping () -> Void) {
        self.text = text
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(Lumen.label)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(palette.bgSurface, in: Capsule())
                .overlay(Capsule().stroke(palette.lineSubtle, lineWidth: 1))
        }
    }
}

/// 音色选择器。
struct SoundPicker: View {
    @Environment(\.lumen) private var palette
    var label: String
    @Binding var selection: TickSoundKind

    var body: some View {
        Menu {
            ForEach(TickSoundKind.allCases, id: \.self) { kind in
                Button(kind.label) { selection = kind }
            }
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(label)
                    .font(Lumen.caption)
                    .foregroundStyle(palette.inkSecondary)
                HStack {
                    Text(selection.label)
                        .font(Lumen.label)
                        .foregroundStyle(palette.inkPrimary)
                    Image(systemName: "chevron.down")
                        .font(.caption2)
                        .foregroundStyle(palette.inkSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}
