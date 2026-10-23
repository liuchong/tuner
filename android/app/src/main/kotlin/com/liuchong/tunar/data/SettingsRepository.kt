package com.liuchong.tunar.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uniffi.tunar_core.*
import uniffi.tunar_core.KeyMode
import uniffi.tunar_core.ModeKind
import uniffi.tunar_core.SolfegeSystem
import uniffi.tunar_core.TunarConfig

/** 主题模式。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** 应用设置（默认值 = spec-ui §4）。 */
data class AppSettings(
    val a4Hz: Double = 440.0,
    val solfege: SolfegeSystem = SolfegeSystem.NUMBERED,
    val key: KeyMode = KeyMode(tonicPc = 0u, mode = ModeKind.MAJOR),
    val noiseGateDbfs: Float = -45f,
    val inputGateMode:GateMode=GateMode.AUTO,
    val inputProfile:InputProfile=InputProfile.GENERAL,
    val inputResponse:ResponseMode=ResponseMode.BALANCED,
    val inputHold:HoldMode=HoldMode.UNTIL_NEXT,
    val inputHysteresis:Float=3f,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val hapticsEnabled: Boolean = true,
    /** 专业版模式（PRO 角标，与通用面板同源）。 */
    val proMode: Boolean = false,
    /** 律制（12/19/24/31，PRO 开启时可见可调）。 */
    val temperament: Int = 12,
) {
    val inputPolicy:InputPolicy get()=InputPolicy(inputGateMode,inputProfile,noiseGateDbfs,inputHysteresis,inputResponse,inputHold)
    /** 转为 core TunarConfig。 */
    fun toTunarConfig(sampleRate: Double = 44100.0): TunarConfig =
        TunarConfig(
            sampleRate = sampleRate,
            frameHopSamples = 1024u,
            a4Hz = a4Hz,
            noiseGateDbfs = noiseGateDbfs,
            solfege = solfege,
            key = key,
            temperament = temperament.toUByte(),
        )
}

/** 设置仓库。 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setA4(hz: Double)
    suspend fun setSolfege(system: SolfegeSystem)
    suspend fun setKey(key: KeyMode)
    suspend fun setNoiseGate(dbfs: Float)
    suspend fun setInputPolicy(policy:InputPolicy) { error("此设置仓库不支持输入策略") }
    suspend fun setTheme(theme: ThemeMode)
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setProMode(enabled: Boolean)
    suspend fun setTemperament(divisions: Int)

    companion object {
        const val A4_MIN = 415.0
        const val A4_MAX = 466.0
        const val GATE_MIN = -80f
        const val GATE_MAX = -12f

        /** 支持的律制（N 平均律）。 */
        val TEMPERAMENT_DIVISIONS = listOf(12, 19, 24, 31)

        /** 唱名体系中文名。 */
        fun solfegeLabel(system: SolfegeSystem): String = when (system) {
            SolfegeSystem.FIXED_DO -> "固定 Do"
            SolfegeSystem.MOVABLE_DO -> "首调 Do"
            SolfegeSystem.NUMBERED -> "简谱数字"
            SolfegeSystem.CHINESE -> "宫商角徵羽"
        }

        /** 调式类别中文名。 */
        fun modeLabel(mode: ModeKind): String = when (mode) {
            ModeKind.GONG -> "宫"
            ModeKind.SHANG -> "商"
            ModeKind.JUE -> "角"
            ModeKind.ZHI -> "徵"
            ModeKind.YU -> "羽"
            ModeKind.MAJOR -> "大调"
            ModeKind.MINOR -> "小调"
        }

        /** 12 主音显示名（升号命名，♯ 显示）。 */
        val TONIC_LABELS: List<String> = listOf(
            "C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B",
        )
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** DataStore Preferences 实现（写入时范围钳制）。 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    private inline fun <reified T:Enum<T>> enumPreference(value:String?,fallback:T):T = enumValues<T>().firstOrNull{it.name==value}?:fallback
    private object Keys {
        val INPUT_GATE=stringPreferencesKey("input_gate_mode")
        val INPUT_PROFILE=stringPreferencesKey("input_profile")
        val INPUT_RESPONSE=stringPreferencesKey("input_response")
        val INPUT_HOLD=stringPreferencesKey("input_hold")
        val INPUT_HYSTERESIS=floatPreferencesKey("input_hysteresis")
        val A4 = doublePreferencesKey("a4_hz")
        val SOLFEGE = stringPreferencesKey("solfege_system")
        val TONIC = intPreferencesKey("key_tonic_pc")
        val MODE = stringPreferencesKey("key_mode")
        val GATE = floatPreferencesKey("noise_gate_dbfs")
        val THEME = stringPreferencesKey("theme_mode")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val PRO_MODE = booleanPreferencesKey("pro_mode")
        val TEMPERAMENT = intPreferencesKey("temperament")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            inputGateMode=enumPreference(p[Keys.INPUT_GATE],if(p[Keys.GATE]!=null)GateMode.MANUAL else GateMode.AUTO),
            inputProfile=enumPreference(p[Keys.INPUT_PROFILE],InputProfile.GENERAL),
            inputResponse=enumPreference(p[Keys.INPUT_RESPONSE],ResponseMode.BALANCED),
            inputHold=enumPreference(p[Keys.INPUT_HOLD],HoldMode.UNTIL_NEXT),
            inputHysteresis=(p[Keys.INPUT_HYSTERESIS]?:3f).let{if(it.isFinite())it.coerceIn(3f,12f)else 3f},
            a4Hz = (p[Keys.A4] ?: 440.0).coerceIn(
                SettingsRepository.A4_MIN, SettingsRepository.A4_MAX,
            ),
            solfege = p[Keys.SOLFEGE]
                ?.let { runCatching { SolfegeSystem.valueOf(it) }.getOrNull() }
                ?: SolfegeSystem.NUMBERED,
            key = KeyMode(
                tonicPc = (p[Keys.TONIC] ?: 0).coerceIn(0, 11).toUByte(),
                mode = p[Keys.MODE]
                    ?.let { runCatching { ModeKind.valueOf(it) }.getOrNull() }
                    ?: ModeKind.MAJOR,
            ),
            noiseGateDbfs = (p[Keys.GATE]?.takeIf{it.isFinite()} ?: -45f).coerceIn(
                SettingsRepository.GATE_MIN, SettingsRepository.GATE_MAX,
            ),
            theme = p[Keys.THEME]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            hapticsEnabled = p[Keys.HAPTICS] ?: true,
            proMode = p[Keys.PRO_MODE] ?: false,
            temperament = (p[Keys.TEMPERAMENT] ?: 12).let {
                if (it in SettingsRepository.TEMPERAMENT_DIVISIONS) it else 12
            },
        )
    }

    override suspend fun setA4(hz: Double) {
        dataStore.edit {
            it[Keys.A4] = hz.coerceIn(SettingsRepository.A4_MIN, SettingsRepository.A4_MAX)
        }
    }

    override suspend fun setSolfege(system: SolfegeSystem) {
        dataStore.edit { it[Keys.SOLFEGE] = system.name }
    }

    override suspend fun setKey(key: KeyMode) {
        dataStore.edit {
            it[Keys.TONIC] = key.tonicPc.toInt()
            it[Keys.MODE] = key.mode.name
        }
    }

    override suspend fun setNoiseGate(dbfs: Float) {
        dataStore.edit {
            it[Keys.GATE] = if(dbfs.isFinite())dbfs.coerceIn(SettingsRepository.GATE_MIN, SettingsRepository.GATE_MAX)else -45f
            it[Keys.INPUT_GATE]=GateMode.MANUAL.name
        }
    }

    override suspend fun setInputPolicy(policy:InputPolicy){
        require(policy.manualOpenDbfs.isFinite()&&policy.manualHysteresisDb.isFinite())
        dataStore.edit {
            it[Keys.GATE]=policy.manualOpenDbfs.coerceIn(SettingsRepository.GATE_MIN,SettingsRepository.GATE_MAX)
            it[Keys.INPUT_GATE]=policy.gateMode.name;it[Keys.INPUT_PROFILE]=policy.profile.name
            it[Keys.INPUT_RESPONSE]=policy.response.name;it[Keys.INPUT_HOLD]=policy.hold.name
            it[Keys.INPUT_HYSTERESIS]=policy.manualHysteresisDb.coerceIn(3f,12f)
        }
    }

    override suspend fun setTheme(theme: ThemeMode) {
        dataStore.edit { it[Keys.THEME] = theme.name }
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.HAPTICS] = enabled }
    }

    override suspend fun setProMode(enabled: Boolean) {
        dataStore.edit { it[Keys.PRO_MODE] = enabled }
    }

    override suspend fun setTemperament(divisions: Int) {
        dataStore.edit {
            it[Keys.TEMPERAMENT] =
                if (divisions in SettingsRepository.TEMPERAMENT_DIVISIONS) divisions else 12
        }
    }
}
