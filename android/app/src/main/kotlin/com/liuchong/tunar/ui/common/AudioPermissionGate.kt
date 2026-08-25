package com.liuchong.tunar.ui.common

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 录音权限门（spec-audio §1）：未授权时先申请，拒绝则显示引导页；授权后展示内容。
 *
 * @param onGranted 授权确认回调（含冷启动已有权限的情况）
 */
@Composable
fun AudioPermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var asked by rememberSaveable { mutableStateOf(false) }
    var requestInFlight by rememberSaveable { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(hasAudioPermission(context))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { g ->
        asked = true
        requestInFlight = false
        // 部分厂商 ROM 返回值晚于系统权限落盘，再读一次真实状态。
        granted = g || hasAudioPermission(context)
    }

    fun requestPermission() {
        requestInFlight = true
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(granted) {
        if (granted) {
            onGranted()
        } else if (!asked && !requestInFlight) {
            requestPermission()
        }
    }

    // 某些 ROM 不回调 RequestPermission；权限弹窗或系统设置返回时以真实权限为准。
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val nowGranted = hasAudioPermission(context)
            granted = nowGranted
            if (requestInFlight) {
                requestInFlight = false
                asked = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        content()
    } else {
        PermissionGuide(
            asked = asked,
            onRetry = {
                asked = false
                if (!requestInFlight) {
                    requestPermission()
                }
            },
        )
    }
}

private fun hasAudioPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

/** 权限引导页（拒绝时优雅降级）。 */
@Composable
private fun PermissionGuide(asked: Boolean, onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (asked) "需要麦克风权限才能调音" else "正在请求麦克风权限…",
            style = MaterialTheme.typography.titleLarge,
        )
        if (asked) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
                context.startActivity(intent)
            }) {
                Text("去系统设置开启")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}
