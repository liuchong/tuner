package com.liuchong.tunar.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuchong.tunar.audio.CaptureHub
import com.liuchong.tunar.corebinding.initialInputPolicy
import uniffi.tunar_core.*
import java.util.Locale

fun inputReasonLabel(reason:InputReason):String=when(reason){
    InputReason.NONE->"实时测量";InputReason.BELOW_GATE->"当前低于开启/维持门限"
    InputReason.NO_PERIODIC_PITCH->"音高不明确，周期证据不足";InputReason.AMBIGUOUS_PITCH->"音高跳变确认中"
    InputReason.ACQUIRING->"正在识别起音";InputReason.CALIBRATING->"正在测环境，不计成绩"
    InputReason.DROPOUT->"信号不稳";InputReason.CLIPPED->"输入过强，请退远或降低输入增益"
    InputReason.OUTPUT_CONTAMINATED->"参考音影响中，不计成绩";InputReason.INPUT_UNAVAILABLE->"输入已中断"
}

/** 只处理权限与捕获租约，门限/时间/保持均由core决定。 */
@Composable
fun CaptureInputControls(policy:InputPolicy,onPolicy:(InputPolicy)->Unit,onCalibrationStart:()->Unit={}){
    val context=LocalContext.current
    val status by CaptureHub.inputStatus.collectAsStateWithLifecycle()
    var owns by remember{mutableStateOf(false)}
    var permissionError by remember{mutableStateOf(false)}
    fun begin(){permissionError=false;onPolicy(policy.copy(gateMode=GateMode.AUTO));onCalibrationStart();owns=true;CaptureHub.beginCalibration()}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)begin()else permissionError=true}
    DisposableEffect(Unit){onDispose{if(owns)CaptureHub.endCalibrationCapture()}}
    InputControls(policy,status,onPolicy,{
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)begin()else permission.launch(Manifest.permission.RECORD_AUDIO)
    },CaptureHub::confirmCalibration,CaptureHub::cancelCalibration)
    if(permissionError)Text("请允许麦克风访问，已有记录仍可查看",color=MaterialTheme.colorScheme.error)
}

@Composable
fun InputControls(policy:InputPolicy,status:InputStatus,onPolicy:(InputPolicy)->Unit,onCalibrate:()->Unit,onConfirm:()->Unit,onCancel:()->Unit){
    var advanced by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("输入与响应",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=policy.gateMode==GateMode.AUTO,onClick={onPolicy(policy.copy(gateMode=GateMode.AUTO))},label={Text("按环境自动")})
            FilterChip(selected=policy.gateMode==GateMode.MANUAL,onClick={onPolicy(policy.copy(gateMode=GateMode.MANUAL))},label={Text("手动门限")})
        }
        if(policy.gateMode==GateMode.AUTO)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(InputProfile.SOFT to "轻声",InputProfile.GENERAL to "通用",InputProfile.NOISY to "较吵").forEach{(value,label)->FilterChip(selected=policy.profile==value,onClick={onPolicy(policy.copy(profile=value))},label={Text(label)})}
        }
        Text("响应速度",style=MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(ResponseMode.FAST to "迅速",ResponseMode.BALANCED to "均衡",ResponseMode.STEADY to "平稳").forEach{(value,label)->FilterChip(selected=policy.response==value,onClick={onPolicy(policy.copy(response=value))},label={Text(label)})}
        }
        Text("停吹后保留",style=MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(HoldMode.OFF to "关闭",HoldMode.SECONDS5 to "5秒",HoldMode.SECONDS15 to "15秒",HoldMode.UNTIL_NEXT to "直到下次发声").forEach{(value,label)->FilterChip(selected=policy.hold==value,onClick={onPolicy(policy.copy(hold=value))},label={Text(label)})}
        }
        Text("响应只改变指针平滑；保持读数不增加演奏成绩。",style=MaterialTheme.typography.bodySmall)
        LiveInputStatus(status)
        if(status.calibration.state!=CalibrationState.MEASURING)Text(status.calibration.reason,style=MaterialTheme.typography.bodySmall)
        if(status.calibration.state==CalibrationState.MEASURING){
            Text("先不要演奏，保持2秒安静")
            LinearProgressIndicator(progress={ (status.calibration.elapsedSeconds/2).toFloat().coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
            TextButton(onClick=onCancel){Text("取消测量")}
        }else if(status.calibration.state==CalibrationState.NEEDS_QUIET_CONFIRMATION){
            TextButton(onClick=onConfirm){Text("确认是环境声，采用测量")}
            TextButton(onClick=onCancel){Text("取消测量")}
        }else{OutlinedButton(onClick=onCalibrate){Text("测一下环境噪声")}}
        TextButton(onClick={advanced=!advanced}){Text(if(advanced)"收起高级门限"else"高级门限")}
        if(advanced){
            Text(String.format(Locale.ROOT,"手动开启 %.0f dBFS",policy.manualOpenDbfs))
            Slider(value=policy.manualOpenDbfs,onValueChange={onPolicy(policy.copy(gateMode=GateMode.MANUAL,manualOpenDbfs=it))},valueRange=-80f..-12f)
            Text(String.format(Locale.ROOT,"门限差 %.0f dB",policy.manualHysteresisDb))
            Slider(value=policy.manualHysteresisDb,onValueChange={onPolicy(policy.copy(manualHysteresisDb=it))},valueRange=3f..12f)
            TextButton(onClick={onPolicy(initialInputPolicy())}){Text("恢复输入默认设置")}
        }
    }
}

@Composable
fun LiveInputStatus(status:InputStatus,compact:Boolean=false){
    if(!status.available){Text("实时输入不可用；可查看上次结果",style=MaterialTheme.typography.bodySmall);return}
    val d=status.diagnostics
    Text(String.format(Locale.ROOT,"实时输入 %.1f dBFS · %s",status.inputLevelDbfs,d?.let{inputReasonLabel(it.reason)}?:"等待识别"),style=MaterialTheme.typography.bodySmall)
    if(d!=null&&!compact){
        Text(String.format(Locale.ROOT,"开启 %.0f / 关闭 %.0f dBFS",d.policy.openDbfs,d.policy.closeDbfs),style=MaterialTheme.typography.bodySmall)
        Text(d.policy.noiseFloorDbfs?.let{String.format(Locale.ROOT,"背景 %.0f dBFS · 高出背景 %.1f dB",it,status.inputLevelDbfs-it)}?:"尚未按环境测量",style=MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(18.dp)){
            fun x(v:Float)=((v+100)/100).coerceIn(0f,1f)*size.width
            drawLine(Color.Gray.copy(alpha=.2f),Offset(0f,size.height/2),Offset(size.width,size.height/2),6.dp.toPx())
            drawLine(Color.Blue,Offset(0f,size.height/2),Offset(x(status.inputLevelDbfs),size.height/2),6.dp.toPx())
            for((value,color)in listOf(d.policy.openDbfs to Color(0xffff9800),d.policy.closeDbfs to Color.Gray)){drawLine(color,Offset(x(value),0f),Offset(x(value),size.height),2.dp.toPx())}
            d.policy.noiseFloorDbfs?.let{drawLine(Color.DarkGray,Offset(x(it),3.dp.toPx()),Offset(x(it),size.height-3.dp.toPx()),1.dp.toPx())}
        }
    }
    if(d?.metronomeRisk==true)Text("扬声器节拍可能影响成绩，建议使用耳机",style=MaterialTheme.typography.bodySmall)
}
