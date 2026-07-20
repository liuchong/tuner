import AVFoundation
import SwiftUI

/// 通用调音面板（design-system v4 §5/§6）。
struct TunerView: View {
    @Environment(\.lumen) private var palette
    @StateObject private var vm = TunerViewModel()
    @StateObject private var settings = SettingsStore.shared

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
                        // PRO 角标
                        HStack {
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
                            spectrumDb: vm.spectrumDb,
                            partials: vm.partials,
                            fundamentalHz: reading?.freqHz,
                            cents: reading.map { Float($0.centsOff) }
                        )
                        .frame(height: geo.size.height * 0.14)

                        Spacer().frame(maxHeight: .infinity)

                        // 泛音 / 和弦行
                        HStack(spacing: Lumen.Spacing.xs) {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: Lumen.Spacing.xs) {
                                    ForEach(vm.partials.filter { $0.harmonicIndex > 1 }.prefix(5), id: \.freqHz) { p in
                                        PartialChip(partial: p, f0: reading?.freqHz)
                                    }
                                }
                            }
                            CapsuleContent {
                                Text(vm.chord ?? "—")
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
                }
            }
        }
        .onAppear { requestPermissionAndStart() }
        .onDisappear { vm.releaseCapture() }
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
        .task {
            // 无信号超时巡检（~10Hz）
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 100_000_000)
                vm.onTick()
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
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(enabled ? palette.accent.opacity(0.15) : .clear, in: Capsule())
                .overlay(
                    Capsule().stroke(enabled ? palette.accent : palette.inkSecondary, lineWidth: 1.5)
                )
        }
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
