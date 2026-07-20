package com.liuchong.tuner.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.theme.TunerTypography

/**
 * 状态胶囊（design-system v2.0 §6.2）：无信号时淡入显示
 * 「mic 图标 + 请发声」，替代旧版巨字。
 */
@Composable
fun StatusChip(visible: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalLumenColors.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.bgSurface)
                .padding(horizontal = 16.dp),
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = colors.inkFaint,
                modifier = Modifier.height(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("请发声", style = TunerTypography.caption, color = colors.inkSecondary)
        }
    }
}
