package com.liuchong.tunar.data
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.tunar_core.*
import java.io.File
class InputPreferencesTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun legacyGateMigratesAndPresentationChoicesRoundTrip() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(folder.root, "legacy.preferences_pb") })
        ds.edit { it[floatPreferencesKey("noise_gate_dbfs")] = -52f }
        val repo = DataStoreSettingsRepository(ds)
        assertEquals(GateMode.MANUAL, repo.settings.first().inputPolicy.gateMode)
        assertEquals(-52f, repo.settings.first().inputPolicy.manualOpenDbfs, 0f)
        val next = repo.settings.first().inputPolicy.copy(response = ResponseMode.STEADY, hold = HoldMode.SECONDS5)
        repo.setInputPolicy(next)
        assertEquals(next, repo.settings.first().inputPolicy)
    }
    @Test fun newInstallHasAutomaticGeneralPreferences() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(folder.root, "fresh.preferences_pb") })
        val p = DataStoreSettingsRepository(ds).settings.first().inputPolicy
        assertEquals(GateMode.AUTO, p.gateMode)
        assertEquals(InputProfile.GENERAL, p.profile)
        assertEquals(HoldMode.UNTIL_NEXT, p.hold)
    }
}
