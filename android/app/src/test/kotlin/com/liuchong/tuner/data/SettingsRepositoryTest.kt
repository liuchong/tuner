package com.liuchong.tuner.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.tuner_core.ModeKind
import uniffi.tuner_core.SolfegeSystem
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private fun makeRepo(scope: TestScope): DataStoreSettingsRepository {
        val ds = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { File(tmpFolder.root, "test_${System.nanoTime()}.preferences_pb") },
        )
        return DataStoreSettingsRepository(ds)
    }

    @Test
    fun `默认值符合 spec`() = runTest {
        val repo = makeRepo(this)
        val s = repo.settings.first()
        assertEquals(440.0, s.a4Hz, 1e-9)
        assertEquals(SolfegeSystem.NUMBERED, s.solfege)
        assertEquals(0, s.key.tonicPc.toInt())
        assertEquals(ModeKind.MAJOR, s.key.mode)
        assertEquals(-45f, s.noiseGateDbfs, 1e-6f)
        assertEquals(ThemeMode.SYSTEM, s.theme)
        assertEquals(true, s.hapticsEnabled)
    }

    @Test
    fun `读写往返`() = runTest {
        val repo = makeRepo(this)
        repo.setA4(442.0)
        repo.setSolfege(SolfegeSystem.CHINESE)
        repo.setNoiseGate(-45f)
        repo.setTheme(ThemeMode.DARK)
        val s = repo.settings.first()
        assertEquals(442.0, s.a4Hz, 1e-9)
        assertEquals(SolfegeSystem.CHINESE, s.solfege)
        assertEquals(-45f, s.noiseGateDbfs, 1e-6f)
        assertEquals(ThemeMode.DARK, s.theme)
    }

    @Test
    fun `范围钳制`() = runTest {
        val repo = makeRepo(this)
        repo.setA4(400.0)
        assertEquals(415.0, repo.settings.first().a4Hz, 1e-9)
        repo.setA4(500.0)
        assertEquals(466.0, repo.settings.first().a4Hz, 1e-9)
        repo.setNoiseGate(-70f)
        assertEquals(-60f, repo.settings.first().noiseGateDbfs, 1e-6f)
        repo.setNoiseGate(-20f)
        assertEquals(-30f, repo.settings.first().noiseGateDbfs, 1e-6f)
    }

    @Test
    fun `调式读写`() = runTest {
        val repo = makeRepo(this)
        repo.setKey(uniffi.tuner_core.KeyMode(tonicPc = 9u, mode = ModeKind.YU))
        val s = repo.settings.first()
        assertEquals(9, s.key.tonicPc.toInt())
        assertEquals(ModeKind.YU, s.key.mode)
    }
}
