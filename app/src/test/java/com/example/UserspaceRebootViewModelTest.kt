package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.EngineRepository
import com.example.data.LogTelemetryParser
import com.example.model.AuditCheckItem
import com.example.model.AuditReport
import com.example.model.CheckOutcome
import com.example.model.DeviceTelemetry
import com.example.model.LogLine
import com.example.model.RootStatus
import com.example.model.SafetyGateState
import com.example.ui.UserspaceRebootViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserspaceRebootViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeEngineRepository(
        var auditShouldPass: Boolean = true,
    ) : EngineRepository {
        var rebootLaunched = false

        override suspend fun checkRootAccess(): RootStatus =
            RootStatus.Granted(uid = 0, selinuxContext = "u:r:ksu:s0", providerLabel = "KernelSU")

        override suspend fun fetchTelemetry(): DeviceTelemetry =
            DeviceTelemetry(model = "LGN-LX1", magicOsVersion = "9.0", ceAvailable = true)

        override suspend fun runPreFlightAudit(): Result<AuditReport> = Result.success(
            AuditReport(
                passed = auditShouldPass,
                summary = if (auditShouldPass) "Passed" else "CE locked",
                checks = listOf(
                    AuditCheckItem(
                        id = "root_uid",
                        outcome = if (auditShouldPass) CheckOutcome.PASS else CheckOutcome.FAIL,
                        label = "Root UID 0",
                        detail = "UID 0",
                    ),
                ),
                bucketCounts = mapOf("FRAMEWORK" to 8),
                services = emptyList(),
                modules = emptyList(),
                dScripts = emptyList(),
            ),
        )

        override suspend fun launchUserspaceReboot(): Result<String> {
            rebootLaunched = true
            return Result.success("Detached PID 9999")
        }

        override suspend fun readLogLines(previous: Boolean): List<LogLine> =
            if (rebootLaunched && !previous) {
                LogTelemetryParser.parseLogLines(
                    "[2026-11-24 11:36:20] [INFO] === TRUE USERSPACE REBOOT COMPLETE ===",
                )
            } else {
                emptyList()
            }
    }

    @Test
    fun `safety gate locks reboot until dry run audit passes`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = FakeEngineRepository(auditShouldPass = false)
        val vm = UserspaceRebootViewModel(app, repo)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.safetyGate is SafetyGateState.Locked)
        vm.requestRebootConfirmation()
        assertFalse(vm.uiState.value.showConfirmDialog)

        // Failing audit keeps gate closed
        vm.runDryRunAudit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.safetyGate is SafetyGateState.Failed)
        vm.requestRebootConfirmation()
        assertFalse(vm.uiState.value.showConfirmDialog)

        // Passing audit unlocks confirmation & launch
        repo.auditShouldPass = true
        vm.runDryRunAudit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.safetyGate is SafetyGateState.Passed)

        vm.requestRebootConfirmation()
        assertTrue(vm.uiState.value.showConfirmDialog)

        vm.triggerUserspaceReboot()
        advanceUntilIdle()
        assertTrue(repo.rebootLaunched)
    }
}
