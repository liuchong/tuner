import SwiftUI
import AVFoundation

/// 仅是枚举与本地偏好编码的对应表，不计算判定阈值。
enum InputPolicyChoices {
    static let gates:[GateMode]=[.auto,.manual]
    static let profiles:[InputProfile]=[.soft,.general,.noisy]
    static let responses:[ResponseMode]=[.fast,.balanced,.steady]
    static let holds:[HoldMode]=[.off,.seconds5,.seconds15,.untilNext]
    static func profileLabel(_ value:InputProfile)->String{switch value{case .soft:return "轻声";case .general:return "通用";case .noisy:return "较吵"}}
    static func responseLabel(_ value:ResponseMode)->String{switch value{case .fast:return "迅速";case .balanced:return "均衡";case .steady:return "平稳"}}
    static func holdLabel(_ value:HoldMode)->String{switch value{case .off:return "关闭";case .seconds5:return "5秒";case .seconds15:return "15秒";case .untilNext:return "直到下次发声"}}
    static func reasonLabel(_ reason:InputReason)->String{switch reason{case .none:return "实时测量";case .belowGate:return "当前低于开启/维持门限";case .noPeriodicPitch:return "音高不明确，周期证据不足";case .ambiguousPitch:return "音高跳变确认中";case .acquiring:return "正在识别起音";case .calibrating:return "正在测量环境，不计成绩";case .dropout:return "信号不稳";case .clipped:return "输入过强，请退远或降低输入增益";case .outputContaminated:return "参考音影响中，不计成绩";case .inputUnavailable:return "输入已中断"}}
}

/// 共享iOS/macOS控件；设置和工作区使用同一状态与偏好。
struct InputControlsView:View {
    @ObservedObject var settings:SettingsStore = .shared
    @ObservedObject private var hub=CaptureHub.shared
    @State private var ownsCalibration=false
    @State private var localError:String?
    @State private var advanced=false
    var onCalibrationStart:(()->Void)?
    var body:some View {
        VStack(alignment:.leading,spacing:12){
            Text("输入与响应").font(.headline)
            Picker("环境适应",selection:$settings.gateMode){Text("按环境自动设置").tag(GateMode.auto);Text("手动门限").tag(GateMode.manual)}
            if settings.gateMode == .auto {Picker("环境档位",selection:$settings.inputProfile){ForEach(InputPolicyChoices.profiles,id:\.self){Text(InputPolicyChoices.profileLabel($0)).tag($0)}}}
            Picker("响应速度",selection:$settings.responseMode){ForEach(InputPolicyChoices.responses,id:\.self){Text(InputPolicyChoices.responseLabel($0)).tag($0)}}
            Picker("停吹后保留",selection:$settings.holdMode){ForEach(InputPolicyChoices.holds,id:\.self){Text(InputPolicyChoices.holdLabel($0)).tag($0)}}
            Text("响应只改变指针平滑；保持读数不增加演奏成绩。").font(.caption).foregroundStyle(.secondary)
            LiveInputStatusView(status:hub.inputStatus)
            if hub.calibrationRequested {Text("正在停止播放，等待输入及输出保护结束…").font(.caption)}
            if hub.inputStatus.calibration.state == .measuring {
                Text("先不要演奏，保持2秒安静").font(.callout)
                ProgressView(value:min(1,hub.inputStatus.calibration.elapsedSeconds/2))
            }
            if hub.inputStatus.calibration.state != .measuring {Text(hub.inputStatus.calibration.reason).font(.caption).foregroundStyle(.secondary)}
            if hub.inputStatus.calibration.state == .needsQuietConfirmation {
                Button("确认是环境声，采用测量"){hub.confirmCalibration()}
                Button("重新测量"){requestCalibration()}
            } else {
                Button("测一下环境噪声"){requestCalibration()}.disabled(hub.calibrationRequested || hub.inputStatus.calibration.state == .measuring)
            }
            if hub.calibrationRequested || hub.inputStatus.calibration.state == .measuring || hub.inputStatus.calibration.state == .needsQuietConfirmation {Button("取消测量"){hub.cancelCalibration()}}
            if let localError{Text(localError).font(.caption).foregroundStyle(.red)}
            DisclosureGroup("高级门限",isExpanded:$advanced){
                VStack(alignment:.leading){
                    Text("手动开启 \(settings.noiseGateDbfs,specifier:"%.0f") dBFS")
                    Slider(value:$settings.noiseGateDbfs,in: -80 ... -12).accessibilityLabel("手动开启门限")
                    Text("门限差 \(settings.gateHysteresisDb,specifier:"%.0f") dB")
                    Slider(value:$settings.gateHysteresisDb,in:3 ... 12).accessibilityLabel("门限差")
                    Text("修改开启值会切到手动；这些数值不是声压级。").font(.caption)
                    Button("恢复输入默认设置"){settings.restoreInputDefaults()}
                }
            }
        }.pickerStyle(.menu)
        .onDisappear{if ownsCalibration{DispatchQueue.main.async{hub.endCalibrationCapture()}}}
    }
    private func requestCalibration(){
        localError=nil
        let begin: @MainActor @Sendable () -> Void = {settings.gateMode = .auto;onCalibrationStart?();ownsCalibration=true;hub.beginCalibration()}
        switch AVCaptureDevice.authorizationStatus(for:.audio){
        case .authorized:begin()
        case .notDetermined:AVCaptureDevice.requestAccess(for:.audio){granted in Task{@MainActor in if granted{begin()}else{localError="请允许麦克风访问，已有记录仍可查看"}}}
        default:localError="需要麦克风权限，请在系统设置允许后重试"
        }
    }
}

struct LiveInputStatusView:View{
    let status:InputStatus
    var compact=false
    var body:some View{
        VStack(alignment:.leading,spacing:5){
            if compact {
                Text(status.available ? "实时输入 \(String(format:"%.1f",status.inputLevelDbfs)) dBFS · \(status.diagnostics.map{InputPolicyChoices.reasonLabel($0.reason)} ?? "等待识别")" : "输入已中断，可查看上次结果")
            } else if status.available {
                Text("实时输入 \(status.inputLevelDbfs,specifier:"%.1f") dBFS").monospacedDigit()
                if let d=status.diagnostics {
                    Text(InputPolicyChoices.reasonLabel(d.reason))
                    Text("开启 \(d.policy.openDbfs,specifier:"%.0f") / 关闭 \(d.policy.closeDbfs,specifier:"%.0f") dBFS")
                    if let noise=d.policy.noiseFloorDbfs {Text("背景 \(noise,specifier:"%.0f") dBFS · 高出背景 \(status.inputLevelDbfs-noise,specifier:"%.1f") dB")}
                    else{Text("尚未按环境测量")}
                    InputLevelMeter(level:status.inputLevelDbfs,policy:d.policy).frame(height:18)
                    if d.metronomeRisk{Text("扬声器节拍可能影响成绩，建议使用耳机")}
                }
            }else{Text("实时输入不可用；可继续查看上次结果")}
        }.font(.caption).foregroundStyle(.secondary).accessibilityElement(children:.combine)
    }
}
private struct InputLevelMeter:View{
    let level:Float
    let policy:ResolvedInputPolicy
    var body:some View{Canvas{context,size in
        func x(_ v:Float)->CGFloat{CGFloat((v+100).clamped(to:0...100)/100)*size.width}
        context.fill(Path(CGRect(x:0,y:6,width:size.width,height:6)),with:.color(.gray.opacity(0.2)))
        context.fill(Path(CGRect(x:0,y:6,width:x(level),height:6)),with:.color(.blue))
        for (v,color) in [(policy.openDbfs,Color.orange),(policy.closeDbfs,Color.gray)] {var p=Path();p.move(to:CGPoint(x:x(v),y:0));p.addLine(to:CGPoint(x:x(v),y:size.height));context.stroke(p,with:.color(color),lineWidth:2)}
        if let noise=policy.noiseFloorDbfs {var p=Path();p.move(to:CGPoint(x:x(noise),y:3));p.addLine(to:CGPoint(x:x(noise),y:15));context.stroke(p,with:.color(.secondary),style:StrokeStyle(lineWidth:1,dash:[2,2]))}
    }.accessibilityLabel("实时输入电平、环境背景、开启与关闭门限")}
}
private extension Float{func clamped(to range:ClosedRange<Float>)->Float{min(range.upperBound,max(range.lowerBound,self))}}
