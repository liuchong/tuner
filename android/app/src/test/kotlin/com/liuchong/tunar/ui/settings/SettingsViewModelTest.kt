package com.liuchong.tunar.ui.settings

import com.liuchong.tunar.audio.TunarConfigSink
import com.liuchong.tunar.data.AppSettings
import com.liuchong.tunar.data.SettingsRepository
import com.liuchong.tunar.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import uniffi.tunar_core.KeyMode
import uniffi.tunar_core.ModeKind
import uniffi.tunar_core.SolfegeSystem
import uniffi.tunar_core.TunarConfig

/** 内存版设置仓库（接口 fake）。 */
private class FakeRepo(private var current: AppSettings = AppSettings()) : SettingsRepository {
    private val flow = MutableStateFlow(current)
    override val settings: Flow<AppSettings> = flow

    private suspend fun update(s: AppSettings) {
        current = s
        flow.emit(s)
    }

    override suspend fun setA4(hz: Double) = update(
        current.copy(a4Hz = hz.coerceIn(415.0, 466.0)),
    )

    override suspend fun setSolfege(system: SolfegeSystem) =
        update(current.copy(solfege = system))

    override suspend fun setKey(key: KeyMode) = update(current.copy(key = key))

    override suspend fun setNoiseGate(dbfs: Float) = update(
        current.copy(noiseGateDbfs = dbfs.coerceIn(-60f, -30f)),
    )

    override suspend fun setTheme(theme: ThemeMode) = update(current.copy(theme = theme))

    override suspend fun setHapticsEnabled(enabled: Boolean) =
        update(current.copy(hapticsEnabled = enabled))

    override suspend fun setProMode(enabled: Boolean) =
        update(current.copy(proMode = enabled))

    override suspend fun setTemperament(divisions: Int) =
        update(current.copy(temperament = divisions))
}

/** 记录下发配置的 sink。 */
private class FakeSink : TunarConfigSink {
    val configs = mutableListOf<TunarConfig>()
    override fun applyConfig(config: TunarConfig) {
        configs.add(config)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `初始设置即下发引擎配置`() {
        val sink = FakeSink()
        SettingsViewModel(repo = FakeRepo(), configSink = sink)
        assertEquals(1, sink.configs.size)
        val c = sink.configs[0]
        assertEquals(440.0, c.a4Hz, 1e-9)
        assertEquals(SolfegeSystem.NUMBERED, c.solfege)
        assertEquals(-45f, c.noiseGateDbfs, 1e-6f)
    }

    @Test
    fun `设置变更即时下发引擎`() {
        val repo = FakeRepo()
        val sink = FakeSink()
        val vm = SettingsViewModel(repo = repo, configSink = sink)

        vm.setA4(442.0)
        assertEquals(442.0, sink.configs.last().a4Hz, 1e-9)
        assertEquals(442.0, vm.settings.value.a4Hz, 1e-9)

        vm.setSolfege(SolfegeSystem.CHINESE)
        assertEquals(SolfegeSystem.CHINESE, sink.configs.last().solfege)

        vm.setKey(KeyMode(tonicPc = 5u, mode = ModeKind.GONG))
        assertEquals(5, sink.configs.last().key.tonicPc.toInt())
        assertEquals(ModeKind.GONG, sink.configs.last().key.mode)

        vm.setNoiseGate(-45f)
        assertEquals(-45f, sink.configs.last().noiseGateDbfs, 1e-6f)

        vm.setTheme(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, vm.settings.value.theme)
    }

    @Test
    fun `PRO 与律制设置下发引擎`() {
        val repo = FakeRepo()
        val sink = FakeSink()
        val vm = SettingsViewModel(repo = repo, configSink = sink)
        assertEquals(false, vm.settings.value.proMode)
        vm.setProMode(true)
        assertEquals(true, vm.settings.value.proMode)
        vm.setTemperament(19)
        assertEquals(19, vm.settings.value.temperament)
        assertEquals(19, sink.configs.last().temperament.toInt())
        vm.setTemperament(5) // FakeRepo 不校验，真实 repo 会钳到 12
        assertEquals(5, vm.settings.value.temperament)
    }

    @Test
    fun `A4 越界钳制后下发`() {
        val repo = FakeRepo()
        val sink = FakeSink()
        val vm = SettingsViewModel(repo = repo, configSink = sink)
        vm.setA4(500.0)
        assertEquals(466.0, sink.configs.last().a4Hz, 1e-9)
        vm.setA4(400.0)
        assertEquals(415.0, sink.configs.last().a4Hz, 1e-9)
    }
}
