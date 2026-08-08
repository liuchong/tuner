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
    var asked by rememberSaveable { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { g ->
        asked = true
        granted = g
        if (g) onGranted()
    }

    LaunchedEffect(Unit) {
        val g = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (g) {
            granted = true
            onGranted()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (granted) {
        content()
    } else {
        PermissionGuide(asked = asked, onRetry = { launcher.launch(Manifest.permission.RECORD_AUDIO) })
    }
}

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
