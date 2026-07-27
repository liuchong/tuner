package com.liuchong.tuner.ui.tuner

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuchong.tuner.audio.CaptureHub
import com.liuchong.tuner.audio.AudioTrackReferenceTonePlayer
import com.liuchong.tuner.corebinding.TunerCore
import com.liuchong.tuner.data.DataStoreSettingsRepository
import com.liuchong.tuner.data.SettingsRepository
import com.liuchong.tuner.ui.common.AudioPermissionGate
import com.liuchong.tuner.ui.common.AuroraBackground
import com.liuchong.tuner.ui.common.StatusChip
import com.liuchong.tuner.ui.common.rememberTunerHaptics
import com.liuchong.tuner.ui.settings.SettingsViewModel
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.theme.TunerSpacing
import com.liuchong.tuner.ui.theme.TunerTypography
import com.liuchong.tuner.ui.theme.tuneColorOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import uniffi.tuner_core.SolfegeSystem

/** 准音区（±cents）。 */
private const val IN_TUNE_CENTS = 5.0

/** 浮动面板类型。 */
private enum class PanelKind { KEY, TEMPERAMENT }

/** 音名文本：变音记号 ♯ 以 0.62em 上标显示（design-system §4）。 */
@Composable
fun noteNameText(noteName: String) = buildAnnotatedString {
    val sharp = noteName.contains('#')
    val base = noteName.replace("#", "")
    append(base[0].toString())
    if (sharp) {
        withStyle(SpanStyle(fontSize = 0.62.em, baselineShift = BaselineShift.Superscript)) {
            append("♯")
        }
    }
    append(base.substring(1))
}

/**
 * 通用调音面板（design-system v4 §5/§6）：表盘 → 音名读数 → 唱名+调性胶囊行 →
 * 数据胶囊行 → 频谱分析带 → 泛音/和弦行 → 状态胶囊。PRO 角标 + 律制浮动面板。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TunerScreen(
    onOpenSpectrum: () -> Unit = {},
    viewModel: TunerViewModel = viewModel(initializer = {
        TunerViewModel(stream = CaptureHub)
    }),
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settingsVm: SettingsViewModel = viewModel(
        viewModelStoreOwner = context as ComponentActivity,
        initializer = {
            SettingsViewModel(
                repo = DataStoreSettingsRepository(appContext),
                configSink = CaptureHub,
            )
        },
    )
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val forkVm: TuningForkViewModel = viewModel(
        key = "tuning-fork",
        initializer = {
            TuningForkViewModel(
                stream = CaptureHub,
                toneProvider = TunerCore::referenceTones,
                player = AudioTrackReferenceTonePlayer(appContext),
            )
        },
    )
    val forkState by forkVm.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        forkVm.stopForBackground()
    }
    DisposableEffect(forkVm) {
        onDispose(forkVm::stopForBackground)
    }

    AudioPermissionGate(onGranted = viewModel::startCapture) {
        val colors = LocalLumenColors.current
        val haptics = rememberTunerHaptics()
        val scope = rememberCoroutineScope()
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        val reading = (state.signal as? TunerSignal.Active)?.reading
        val inTune = reading != null && abs(reading.centsOff) <= IN_TUNE_CENTS

        // 触觉（§8）：进入准音区边沿单 tick；保持 500ms 双 tick
        LaunchedEffect(inTune) {
            if (inTune) {
                haptics.tick()
                delay(500)
                haptics.doubleTick()
            }
        }

        // 指针弹簧（§7：800 / 0.72）
        val animatedCents = remember { Animatable(0f) }
        LaunchedEffect(reading?.centsOff) {
            animatedCents.animateTo(
                targetValue = (reading?.centsOff ?: 0.0).toFloat().coerceIn(-50f, 50f),
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 800f),
            )
        }

        // 音名脉冲（准音瞬间 1.0→1.06→1.0，300ms）
        val noteScale = remember { Animatable(1f) }
        LaunchedEffect(inTune) {
            if (inTune) {
                noteScale.animateTo(1.06f, tween(150))
                noteScale.animateTo(1f, tween(150))
            }
        }

        // 浮动面板状态 + 展开动画（spring scale 0.92→1 + fade，220ms）
        var openPanel by remember { mutableStateOf<PanelKind?>(null) }
        var anchorBottomPx by remember { mutableIntStateOf(0) }
        val panelAnim = remember { Animatable(0f) }
        LaunchedEffect(openPanel != null) {
            panelAnim.animateTo(
                if (openPanel != null) 1f else 0f,
                tween(220),
            )
        }
        val panelSink = panelAnim.value

        AuroraBackground(tuneCents = reading?.let { animatedCents.value }) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 主内容（面板展开时下沉 8dp + scale 0.98）
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = panelSink * 8.dp.toPx()
                            val s = 1f - 0.02f * panelSink
                            scaleX = s
                            scaleY = s
                        }
                        .padding(TunerSpacing.page),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 左音叉与右 PRO 严格镜像，不改变主体布局。
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.size(width = 54.dp, height = 32.dp)) {
                            TuningForkBadge(
                                playing = forkState.playingStep != null,
                                onClick = forkVm::open,
                            )
                            if (forkState.selectedStep != null) {
                                QuickPlaybackBadge(
                                    playing = forkState.playingStep != null,
                                    onClick = forkVm::toggleSelected,
                                    modifier = Modifier.offset(y = 36.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        ProBadge(
                            enabled = settings.proMode,
                            onClick = { settingsVm.setProMode(!settings.proMode) },
                        )
                    }

                    // 表盘（顶部固定区域，圆心不放文字；指针扫掠区域收敛在区域内）
                    // 表盘区 ~28% 屏高（小屏按比例压缩；各区间距用加权 Spacer 分散）
                    val dialHeight = (LocalConfiguration.current.screenHeightDp * 0.28f).dp
                    TunerDial(
                        cents = reading?.let { animatedCents.value },
                        clarity = reading?.clarity ?: 1f,
                        accessibilityText = reading?.let { accessibilityText(it) }
                            ?: "无信号，请发声",
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(0.35f + 0.65f * state.displayStrength)
                            .height(dialHeight),
                    )
                    // 布局不变量（硬性）：读数块 top ≥ 表盘区域 bottom + 16dp。
                    // 表盘 pivot/针尾均在表盘区域内部，任意偏转角（含 ±50c 满偏）
                    // 指针扫掠区域都不会与下方读数块相交。
                    Spacer(modifier = Modifier.height(16.dp))

                    // 音名读数（表盘正下方，与表盘明确分离）
                    val lowConfidence = (reading?.clarity ?: 1f) < 0.6f
                    Text(
                        text = reading?.let { noteNameText(it.noteName) }
                            ?: buildAnnotatedString { append("—") },
                        style = if (reading != null) {
                            TunerTypography.displayNote
                        } else {
                            TunerTypography.displayNote.copy(fontSize = 44.sp)
                        },
                        color = reading?.let { tuneColorOf(animatedCents.value, colors) }
                            ?: colors.inkFaint.copy(alpha = 0.6f),
                        modifier = Modifier
                            .alpha(
                                (if (lowConfidence) 0.4f else 1f) *
                                    state.displayStrength,
                            )
                            .graphicsLayer {
                                scaleX = noteScale.value
                                scaleY = noteScale.value
                            },
                    )

                    Spacer(modifier = Modifier.weight(0.4f))

                    // 唱名 + 调性（+ 律制）胶囊行（浮动面板锚点）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.onGloballyPositioned { coords ->
                            anchorBottomPx = coords.size.height + coords.positionInParent().y.toInt()
                        },
                    ) {
                        Capsule {
                            Text(
                                text = reading?.solfege ?: "—",
                                style = TunerTypography.readoutSolfege,
                                color = colors.inkPrimary,
                            )
                        }
                        Spacer(modifier = Modifier.width(TunerSpacing.sm))
                        // 调性按钮胶囊
                        val keyEnabled = settings.solfege != SolfegeSystem.FIXED_DO
                        val keyLabel = SettingsRepository.TONIC_LABELS[
                            settings.key.tonicPc.toInt(),
                        ] + " " + SettingsRepository.modeLabel(settings.key.mode) + " ▾"
                        SelectorCapsule(
                            text = keyLabel,
                            enabled = keyEnabled,
                            selected = openPanel == PanelKind.KEY,
                            onClick = {
                                if (keyEnabled) {
                                    openPanel =
                                        if (openPanel == PanelKind.KEY) null else PanelKind.KEY
                                } else {
                                    Toast.makeText(
                                        context, "固定 Do 无需调性", Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            description = "调性按钮，当前 " + SettingsRepository.TONIC_LABELS[
                                settings.key.tonicPc.toInt(),
                            ] + " " + SettingsRepository.modeLabel(settings.key.mode),
                        )
                        // PRO：律制胶囊（开启时出现）
                        if (settings.proMode) {
                            Spacer(modifier = Modifier.width(TunerSpacing.sm))
                            SelectorCapsule(
                                text = "${settings.temperament}-TET ▾",
                                enabled = true,
                                selected = openPanel == PanelKind.TEMPERAMENT,
                                onClick = {
                                    openPanel = if (openPanel == PanelKind.TEMPERAMENT) {
                                        null
                                    } else {
                                        PanelKind.TEMPERAMENT
                                    }
                                },
                                description = "律制按钮，当前 ${settings.temperament}-TET",
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(0.4f))

                    // 数据胶囊行：Hz / ±c / 清晰度%（+ PRO 律制偏差）
                    Row {
                        DataCapsule(
                            reading?.let { String.format(Locale.US, "%.1f Hz", it.freqHz) }
                                ?: "— Hz",
                            colors.inkPrimary,
                        )
                        Spacer(modifier = Modifier.width(TunerSpacing.sm))
                        DataCapsule(
                            reading?.let {
                                val c = if (abs(it.centsOff) < 0.05) 0.0 else it.centsOff
                                String.format(Locale.US, "%+.1fc", c)
                            } ?: "—",
                            reading?.let { tuneColorOf(it.centsOff.toFloat(), colors) }
                                ?: colors.inkSecondary,
                        )
                        Spacer(modifier = Modifier.width(TunerSpacing.sm))
                        DataCapsule(
                            reading?.let {
                                String.format(Locale.US, "清晰度 %.0f%%", it.clarity * 100f)
                            } ?: "清晰度 —",
                            colors.inkSecondary,
                        )
                        if (settings.proMode && reading != null) {
                            Spacer(modifier = Modifier.width(TunerSpacing.sm))
                            DataCapsule(
                                String.format(
                                    Locale.US,
                                    "%d-TET %+d",
                                    reading.temperament,
                                    reading.temperamentCents.toInt(),
                                ),
                                colors.accent,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(TunerSpacing.md))

                    // 区间距加权分散（design-system §5 填充率纪律，避免底部堆空）
                    Spacer(modifier = Modifier.weight(1f))

                    // 频谱分析带（真实 FFT 数据，加高到 ~18-20% 屏高）
                    SpectrumBand(
                        spectrumDb = state.displaySpectrumDb,
                        partials = state.displayPartials,
                        cents = reading?.let { animatedCents.value },
                        onClick = onOpenSpectrum,
                        modifier = Modifier.height(
                            (LocalConfiguration.current.screenHeightDp * 0.14f).dp,
                        ),
                    )
                    Spacer(modifier = Modifier.weight(0.6f))

                    // 泛音 / 和弦行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(TunerSpacing.xs),
                        ) {
                            val f0 = reading?.freqHz
                            state.displayPartials
                                .filter { it.harmonicIndex > 1u }
                                .take(5)
                                .forEach { p ->
                                    val idx = p.harmonicIndex.toInt()
                                    val dev = if (f0 != null) {
                                        TunerCore.centsBetween(p.freqHz, f0 * idx) ?: 0.0
                                    } else {
                                        0.0
                                    }
                                    PartialChip(index = idx, cents = dev)
                                }
                        }
                        Spacer(modifier = Modifier.width(TunerSpacing.sm))
                        // 和弦胶囊
                        Capsule {
                            Text(
                                text = state.displayChord ?: "—",
                                style = TunerTypography.label,
                                color = colors.accent,
                            )
                        }
                    }

                    // 状态胶囊（底部，无信号时出现）
                    Spacer(modifier = Modifier.weight(1f))
                    StatusChip(visible = reading == null)
                    Spacer(modifier = Modifier.height(TunerSpacing.sm))
                }

                // 浮动面板遮罩（点击收起）
                if (panelSink > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.38f * panelSink)
                            .background(Color.Black)
                            .clickable { openPanel = null },
                    )
                    // 浮动卡片（锚定胶囊行下方，spring 展开）
                    val density = LocalDensity.current
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset {
                                IntOffset(0, anchorBottomPx + with(density) { 32.dp.roundToPx() })
                            }
                            .fillMaxWidth(0.88f)
                            .graphicsLayer {
                                val s = 0.92f + 0.08f * panelSink
                                scaleX = s
                                scaleY = s
                                alpha = panelSink
                            },
                        shape = RoundedCornerShape(20.dp),
                        color = colors.bgSurfaceRaised,
                        shadowElevation = 16.dp,
                    ) {
                        when (openPanel) {
                            PanelKind.KEY -> KeySelectorPanel(
                                currentKey = settings.key,
                                onSelect = { key ->
                                    settingsVm.setKey(key)
                                    scope.launch {
                                        delay(200)
                                        openPanel = null
                                    }
                                },
                            )
                            PanelKind.TEMPERAMENT -> TemperamentSelectorPanel(
                                current = settings.temperament,
                                onSelect = { n ->
                                    settingsVm.setTemperament(n)
                                    scope.launch {
                                        delay(200)
                                        openPanel = null
                                    }
                                },
                            )
                            null -> {}
                        }
                    }
                }
            }
        }
        if (forkState.isOpen) {
            ModalBottomSheet(onDismissRequest = forkVm::close) {
                val colors = LocalLumenColors.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    Text(
                        "固定音高 · ${forkState.tones.firstOrNull()?.temperament ?: settings.temperament}-TET",
                        style = TunerTypography.readoutSolfege,
                        color = colors.inkPrimary,
                    )
                    Text(
                        "点击试听；再次点击停止。麦克风与指针保持工作。",
                        style = TunerTypography.caption,
                        color = colors.inkSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier.height(420.dp)) {
                        items(forkState.tones, key = { it.stepFromA4 }) { tone ->
                            val selected = forkState.selectedStep == tone.stepFromA4
                            val playing = forkState.playingStep == tone.stepFromA4
                            Surface(
                                onClick = { forkVm.toggle(tone) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) {
                                    colors.accent.copy(alpha = 0.16f)
                                } else {
                                    Color.Transparent
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        tone.noteName.replace("#", "♯"),
                                        modifier = Modifier.width(72.dp),
                                        style = TunerTypography.label,
                                        color = if (selected) colors.accent else colors.inkPrimary,
                                    )
                                    Text(
                                        String.format(
                                            Locale.US,
                                            "%.1f Hz",
                                            tone.frequencyHz,
                                        ),
                                        modifier = Modifier.weight(1f),
                                        style = TunerTypography.readoutValue,
                                        color = colors.inkSecondary,
                                    )
                                    Text(
                                        if (playing) "停止" else if (selected) "继续" else "播放",
                                        style = TunerTypography.caption,
                                        color = if (selected) colors.accent else colors.inkSecondary,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

/** PRO 角标（design-system §6.9：开启 accent 点亮+微光，关闭 ink/secondary 描边）。 */
@Composable
private fun ProBadge(enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalLumenColors.current
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 54.dp, height = 32.dp),
        shape = RoundedCornerShape(50),
        color = if (enabled) colors.accent.copy(alpha = 0.15f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (enabled) colors.accent else colors.inkSecondary,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "PRO",
                style = TunerTypography.caption,
                color = if (enabled) colors.accent else colors.inkSecondary,
            )
        }
    }
}

/** 与 PRO 同尺寸的音叉入口。 */
@Composable
private fun TuningForkBadge(playing: Boolean, onClick: () -> Unit) {
    val colors = LocalLumenColors.current
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 54.dp, height = 32.dp),
        shape = RoundedCornerShape(50),
        color = if (playing) colors.accent.copy(alpha = 0.15f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (playing) colors.accent else colors.inkSecondary,
        ),
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .padding(horizontal = 17.dp, vertical = 6.dp)
                .fillMaxSize(),
        ) {
            val color = if (playing) colors.accent else colors.inkSecondary
            val stroke = 1.8.dp.toPx()
            drawLine(color, Offset(size.width * 0.22f, 0f), Offset(size.width * 0.22f, size.height * 0.48f), stroke)
            drawLine(color, Offset(size.width * 0.78f, 0f), Offset(size.width * 0.78f, size.height * 0.48f), stroke)
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * 0.22f, size.height * 0.25f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.46f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
            drawLine(color, Offset(size.width * 0.5f, size.height * 0.68f), Offset(size.width * 0.5f, size.height), stroke)
        }
    }
}

/** 音叉入口下方的同宽快捷播放/停止胶囊；用偏移覆盖空白区，不推动主体布局。 */
@Composable
private fun QuickPlaybackBadge(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 54.dp, height = 24.dp),
        shape = RoundedCornerShape(50),
        color = colors.bgSurface.copy(alpha = 0.86f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (playing) colors.accent else colors.inkSecondary,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (playing) "■ 停止" else "▶ 继续",
                style = TunerTypography.caption.copy(fontSize = 9.sp),
                color = if (playing) colors.accent else colors.inkSecondary,
                maxLines = 1,
            )
        }
    }
}

/** 胶囊容器（bg/surface 全圆角）。 */
@Composable
private fun Capsule(content: @Composable () -> Unit) {
    val colors = LocalLumenColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.bgSurface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        content()
    }
}

/** 选择器胶囊（accent 10% 底 + accent 文字）。 */
@Composable
private fun SelectorCapsule(
    text: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    description: String,
) {
    val colors = LocalLumenColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = colors.accent.copy(alpha = if (enabled) 0.10f else 0.04f),
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = description },
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.5.dp, colors.accent)
        } else {
            null
        },
    ) {
        Text(
            text = text,
            style = TunerTypography.readoutSolfege,
            color = colors.accent,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 数据小胶囊（caption）。 */
@Composable
private fun DataCapsule(text: String, color: Color) {
    Capsule {
        Text(text = text, style = TunerTypography.caption, color = color)
    }
}

/** 泛音 chip（H2/H3…+cents，|c|≤5 绿色描边）。 */
@Composable
private fun PartialChip(index: Int, cents: Double) {
    val colors = LocalLumenColors.current
    val inTune = abs(cents) <= 5.0
    Surface(
        shape = RoundedCornerShape(50),
        color = colors.bgSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (inTune) colors.tuneIn else colors.lineSubtle,
        ),
    ) {
        Text(
            text = String.format(Locale.US, "H%d %+.0fc", index, cents),
            style = TunerTypography.caption,
            color = if (inTune) colors.tuneIn else colors.inkSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 表盘无障碍文案（design-system §9：「当前 A4，偏高 3.2 音分」）。 */
private fun accessibilityText(reading: TunerReading): String {
    val c = reading.centsOff
    val name = reading.noteName.replace("#", "♯")
    return when {
        abs(c) <= IN_TUNE_CENTS -> "当前 $name，音准"
        c > 0 -> String.format(Locale.US, "当前 %s，偏高 %.1f 音分", name, c)
        else -> String.format(Locale.US, "当前 %s，偏低 %.1f 音分", name, -c)
    }
}
