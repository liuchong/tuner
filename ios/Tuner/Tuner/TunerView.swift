import AVFoundation
import SwiftUI

/// 通用调音面板（design-system v4 §5/§6）。
struct TunerView: View {
    @Environment(\.lumen) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = TunerViewModel()
    @StateObject private var settings = SettingsStore.shared
    @StateObject private var forkVm = TuningForkViewModel(initialConfig: defaultTunerConfig())
    var onOpenSpectrum: () -> Void = {}

    @State private var animatedCents: Float = 0
    @State private var noteScale: CGFloat = 1.0
    @State private var openPanel: PanelKind?
    @State private var panelAnchor: CGFloat = 0
    @State private var permissionDenied = false

    private enum PanelKind { case key, temperament }

    private var reading: TunerReading? {
        if case .active(let r) = vm.signal { return r }
        return nil
    }

    private var inTune: Bool {
        reading != nil && abs(reading!.centsOff) <= 5
    }

    var body: some View {
        AuroraBackground(tuneCents: reading.map { Float($0.centsOff) }) {
            GeometryReader { geo in
                ZStack {
                    VStack(spacing: 0) {
                        // 音叉与 PRO 严格左右对称，不改变主内容布局
                        HStack {
                            ZStack(alignment: .top) {
                                TuningForkBadge(playing: forkVm.playingStep != nil) {
                                    forkVm.open()
                                }
                                if forkVm.selectedStep != nil {
                                    Button { forkVm.toggleSelected() } label: {
                                        Text(forkVm.playingStep == nil ? "▶ 继续" : "■ 停止")
                                            .font(.system(size: 9, weight: .medium))
                                            .foregroundStyle(
                                                forkVm.playingStep == nil
                                                    ? palette.inkSecondary
                                                    : palette.accent
                                            )
                                            .frame(width: 52, height: 22)
                                            .background(palette.bgSurface.opacity(0.9), in: Capsule())
                                            .overlay(
                                                Capsule().stroke(
                                                    forkVm.playingStep == nil
                                                        ? palette.inkSecondary
                                                        : palette.accent,
                                                    lineWidth: 1
                                                )
                                            )
                                    }
                                    .offset(y: 36)
                                }
                            }
                            .frame(width: 54, height: 32)
                            Spacer()
                            ProBadge(enabled: settings.proMode) {
                                settings.proMode.toggle()
                            }
                        }
                        .padding(.bottom, Lumen.Spacing.sm)

                        // 表盘（顶部固定区域）
                        HaloDial(
                            cents: reading != nil ? animatedCents : nil,
                            clarity: reading?.clarity ?? 1
                        )
                        .opacity(reading == nil ? 1 : 0.35 + Double(vm.displayStrength) * 0.65)
                        .frame(height: geo.size.height * 0.28)

                        // 布局不变量（硬性）：读数块 top ≥ 表盘区域 bottom + 16dp
                        Spacer().frame(height: 16)

                        // 音名读数（表盘正下方，与表盘明确分离）
                        Text(noteNameText(reading?.noteName))
                            .font(Lumen.displayNote)
                            .foregroundStyle(
                                reading != nil
                                    ? Lumen.tuneColor(of: animatedCents, palette)
                                    : palette.inkFaint.opacity(0.6)
                            )
                            .scaleEffect(noteScale)
                            .opacity((reading?.clarity ?? 1) < 0.6 ? 0.4 : 1)

                        Spacer().frame(maxHeight: .infinity)

                        // 唱名 + 调性（+ 律制）胶囊行（浮动面板锚点）
                        HStack(spacing: Lumen.Spacing.sm) {
                            CapsuleContent {
                                Text(reading?.solfege ?? "—")
                                    .font(Lumen.readoutSolfege)
                                    .foregroundStyle(palette.inkPrimary)
                            }
                            SelectorCapsule(
                                text: keyLabel,
                                enabled: settings.solfegeSystem != .fixedDo,
                                selected: openPanel == .key
                            ) {
                                if settings.solfegeSystem != .fixedDo {
                                    openPanel = openPanel == .key ? nil : .key
                                }
                            }
                            if settings.proMode {
                                SelectorCapsule(
                                    text: "\(settings.temperament)-TET ▾",
                                    selected: openPanel == .temperament
                                ) {
                                    openPanel = openPanel == .temperament ? nil : .temperament
                                }
                            }
                        }
                        .background(
                            GeometryReader { g in
                                Color.clear.onAppear {
                                    panelAnchor = g.frame(in: .global).maxY
                                }
                            }
                        )

                        Spacer().frame(maxHeight: .infinity)

                        // 数据胶囊行
                        HStack(spacing: Lumen.Spacing.sm) {
                            DataCapsule(reading.map { String(format: "%.1f Hz", $0.freqHz) } ?? "— Hz",
                                        palette.inkPrimary)
                            DataCapsule(reading.map { String(format: "%+.1fc", $0.centsOff) } ?? "—",
                                        reading.map { Lumen.tuneColor(of: Float($0.centsOff), palette) }
                                            ?? palette.inkSecondary)
                            DataCapsule(reading.map { String(format: "清晰度 %.0f%%", $0.clarity * 100) } ?? "清晰度 —",
                                        palette.inkSecondary)
                            if settings.proMode, let r = reading {
                                DataCapsule(String(format: "%d-TET %+d", r.temperament, Int(r.temperamentCents)),
                                            palette.accent)
                            }
                        }

                        Spacer().frame(maxHeight: .infinity)

                        // 频谱分析带
                        SpectrumBand(
                            spectrumDb: vm.displaySpectrumDb,
                            partials: vm.displayPartials,
                            cents: reading.map { Float($0.centsOff) }
                        )
                        .frame(height: geo.size.height * 0.14)
                        .contentShape(Rectangle())
                        .onTapGesture(perform: onOpenSpectrum)

                        Spacer().frame(maxHeight: .infinity)

                        // 泛音 / 和弦行
                        HStack(spacing: Lumen.Spacing.xs) {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: Lumen.Spacing.xs) {
                                    ForEach(
                                        Array(vm.displayPartials.filter { $0.harmonicIndex > 1 }.prefix(5).enumerated()),
                                        id: \.offset
                                    ) { _, partial in
                                        PartialChip(partial: partial, f0: reading?.freqHz)
                                    }
                                }
                            }
                            CapsuleContent {
                                Text(vm.displayChord ?? "—")
                                    .font(Lumen.label)
                                    .foregroundStyle(palette.accent)
                            }
                        }

                        Spacer().frame(maxHeight: .infinity)

                        // 状态胶囊（底部）
                        StatusChip(visible: reading == nil)
                            .animation(.easeInOut(duration: 0.2), value: reading == nil)
                    }
                    .padding(Lumen.Spacing.page)

                    // 浮动面板遮罩 + 卡片
                    if openPanel != nil {
                        Color.black.opacity(0.38)
                            .ignoresSafeArea()
                            .onTapGesture { openPanel = nil }
                        VStack {
                            Spacer().frame(height: panelAnchor + 32)
                            Group {
                                switch openPanel {
                                case .key:
                                    KeySelectorPanel(currentKey: settings.key) { key in
                                        settings.key = key
                                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                                            openPanel = nil
                                        }
                                    }
                                case .temperament:
                                    TemperamentSelectorPanel(current: settings.temperament) { n in
                                        settings.temperament = n
                                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                                            openPanel = nil
                                        }
                                    }
                                case nil:
                                    EmptyView()
                                }
                            }
                            .frame(width: geo.size.width * 0.88)
                            .background(palette.bgSurfaceRaised, in: RoundedRectangle(cornerRadius: 20))
                            .shadow(radius: 16)
                            .scaleEffect(openPanel != nil ? 1.0 : 0.92)
                            Spacer()
                        }
                        .transition(.opacity.combined(with: .scale(scale: 0.92)))
                    }

                    if forkVm.isOpen {
                        Color.black.opacity(0.38)
                            .ignoresSafeArea()
                            .onTapGesture { forkVm.close() }
                        TuningForkPanel(vm: forkVm)
                            .frame(width: geo.size.width * 0.88, height: geo.size.height * 0.72)
                            .background(palette.bgSurfaceRaised, in: RoundedRectangle(cornerRadius: 20))
                            .shadow(radius: 16)
                            .transition(.opacity.combined(with: .scale(scale: 0.94)))
                    }
                }
            }
        }
        .onAppear {
            forkVm.refresh(settings.toTunerConfig())
            requestPermissionAndStart()
        }
        .onDisappear {
            forkVm.stopForBackground()
            forkVm.close()
            vm.releaseCapture()
        }
        .onChange(of: settings.a4Hz) { _, _ in forkVm.refresh(settings.toTunerConfig()) }
        .onChange(of: settings.temperament) { _, _ in forkVm.refresh(settings.toTunerConfig()) }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { forkVm.stopForBackground() }
        }
        .onChange(of: reading?.centsOff) { _, newValue in
            withAnimation(.spring(response: 0.18, dampingFraction: 0.72)) {
                animatedCents = Float(newValue ?? 0)
            }
        }
        .onChange(of: inTune) { _, newValue in
            if newValue {
                TunerHaptics.shared.tick()
                withAnimation(.easeInOut(duration: 0.15)) { noteScale = 1.06 }
                withAnimation(.easeInOut(duration: 0.15).delay(0.15)) { noteScale = 1.0 }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    if inTune { TunerHaptics.shared.doubleTick() }
                }
            }
        }
        .alert("需要麦克风权限", isPresented: $permissionDenied) {
            Button("去设置", role: .none) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("请在系统设置中开启麦克风权限以使用调音功能")
        }
    }

    private var keyLabel: String {
        "\(Tonic.labels[Int(settings.keyTonicPc)]) \(settings.keyMode.label) ▾"
    }

    private func noteNameText(_ name: String?) -> String {
        guard let name else { return "—" }
        return name.replacingOccurrences(of: "#", with: "♯")
    }

    private func requestPermissionAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            vm.startCapture()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                DispatchQueue.main.async {
                    if granted { vm.startCapture() } else { permissionDenied = true }
                }
            }
        default:
            permissionDenied = true
        }
    }
}

/// PRO 角标（design-system §6.9）。
struct ProBadge: View {
    @Environment(\.lumen) private var palette
    var enabled: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("PRO")
                .font(Lumen.caption)
                .foregroundStyle(enabled ? palette.accent : palette.inkSecondary)
                .frame(width: 50, height: 28)
                .background(enabled ? palette.accent.opacity(0.15) : .clear, in: Capsule())
                .overlay(
                    Capsule().stroke(enabled ? palette.accent : palette.inkSecondary, lineWidth: 1.5)
                )
        }
        .frame(width: 54, height: 32)
    }
}

/// 与 PRO 占位完全一致的音叉入口。
struct TuningForkBadge: View {
    @Environment(\.lumen) private var palette
    var playing: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "tuningfork")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(playing ? palette.accent : palette.inkSecondary)
                .frame(width: 50, height: 28)
                .background(playing ? palette.accent.opacity(0.12) : .clear, in: Capsule())
                .overlay(
                    Capsule().stroke(
                        playing ? palette.accent : palette.inkSecondary,
                        lineWidth: 1.5
                    )
                )
        }
        .frame(width: 54, height: 32)
        .accessibilityLabel("打开音叉")
    }
}

private struct TuningForkPanel: View {
    @Environment(\.lumen) private var palette
    @ObservedObject var vm: TuningForkViewModel

    var body: some View {
        VStack(spacing: Lumen.Spacing.md) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("固定音高音叉")
                        .font(.headline)
                        .foregroundStyle(palette.inkPrimary)
                    Text("当前平均律 · 点击播放，再点击停止")
                        .font(Lumen.caption)
                        .foregroundStyle(palette.inkSecondary)
                }
                Spacer()
                Button { vm.close() } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundStyle(palette.inkSecondary)
                }
            }

            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 10)], spacing: 10) {
                    ForEach(vm.tones, id: \.stepFromA4) { tone in
                        let playing = vm.playingStep == tone.stepFromA4
                        let selected = vm.selectedStep == tone.stepFromA4
                        Button { vm.toggle(tone) } label: {
                            VStack(spacing: 4) {
                                Text(tone.noteName.replacingOccurrences(of: "#", with: "♯"))
                                    .font(Lumen.label.weight(.semibold))
                                Text(String(format: "%.1f Hz", tone.frequencyHz))
                                    .font(.system(size: 11, design: .monospaced))
                                if abs(tone.centsFromNote) >= 0.05 {
                                    Text(String(format: "%+.1fc", tone.centsFromNote))
                                        .font(.system(size: 10))
                                }
                            }
                            .foregroundStyle(playing ? palette.bgCanvas : palette.inkPrimary)
                            .frame(maxWidth: .infinity, minHeight: 64)
                            .background(
                                playing
                                    ? palette.accent
                                    : selected ? palette.accent.opacity(0.16) : palette.bgSurface,
                                in: RoundedRectangle(cornerRadius: 14)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 14)
                                    .stroke(selected ? palette.accent : palette.lineSubtle)
                            )
                        }
                    }
                }
            }
        }
        .padding(Lumen.Spacing.md)
    }
}

/// 数据小胶囊。
struct DataCapsule: View {
    @Environment(\.lumen) private var palette
    var text: String
    var color: Color

    init(_ text: String, _ color: Color) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(Lumen.caption)
            .foregroundStyle(color)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(palette.bgSurface, in: Capsule())
    }
}

/// 泛音 chip（H2/H3…+cents，|c|≤5 绿色描边）。
struct PartialChip: View {
    @Environment(\.lumen) private var palette
    var partial: Partial
    var f0: Double?

    private var cents: Double {
        guard let f0, partial.harmonicIndex > 1 else { return 0 }
        return CorePresets.centsBetween(
            freq: partial.freqHz,
            target: f0 * Double(partial.harmonicIndex)
        ) ?? 0
    }

    var body: some View {
        let inTune = abs(cents) <= 5
        Text(String(format: "H%d %+.0fc", partial.harmonicIndex, cents))
            .font(Lumen.caption)
            .foregroundStyle(inTune ? palette.tuneIn : palette.inkSecondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(palette.bgSurface, in: Capsule())
            .overlay(
                Capsule().stroke(inTune ? palette.tuneIn : palette.lineSubtle, lineWidth: 1)
            )
    }
}
