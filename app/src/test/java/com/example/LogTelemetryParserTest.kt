package com.example

import com.example.data.LogTelemetryParser
import com.example.model.CheckOutcome
import com.example.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTelemetryParserTest {

    @Test
    fun `parseProbe extracts Honor MagicOS 9 and kernel telemetry`() {
        val sample = """
            PROBE|uid=0
            PROBE|selinux=u:r:ksu:s0
            PROBE|sdk=35
            PROBE|android=15
            PROBE|magicos=9.0
            PROBE|model=LGN-LX1
            PROBE|platform=bengal
            PROBE|hardware=qcom
            PROBE|kernel=5.15.180-android13-8-00018
            PROBE|ce_available=true
            PROBE|apexd=activated
            PROBE|uptime=up 24 minutes
            PROBE|ksu_ver=v1.0.5
            PROBE|ksu_modules=3
        """.trimIndent()

        val telemetry = LogTelemetryParser.parseProbe(sample)
        assertEquals("LGN-LX1", telemetry.model)
        assertEquals("9.0", telemetry.magicOsVersion)
        assertEquals("15", telemetry.androidRelease)
        assertEquals("bengal", telemetry.platform)
        assertTrue(telemetry.ceAvailable)
        assertEquals(3, telemetry.ksuModuleCount)
    }

    @Test
    fun `parseAudit validates passing and failing safety gates`() {
        val passStdout = """
            CHECK|root_uid|PASS|Root UID 0 Access|UID 0 (u:r:ksu:s0)
            CHECK|ce_storage|PASS|CE Storage Keyring|sys.user.0.ce_available=true
            BUCKET|FRAMEWORK|12
            BUCKET|PROTECTED|28
            SVC|FRAMEWORK|zygote|812|u:r:zygote:s0
            MOD|ACTIVE|zygisk_next|teardown,post-fs-data,service
            DSCRIPT|service.d|99_tune.sh
            AUDIT_SUMMARY|PASS|All pre-flight invariants verified.
        """.trimIndent()

        val passReport = LogTelemetryParser.parseAudit(passStdout, exitCode = 0)
        assertTrue(passReport.passed)
        assertEquals(2, passReport.checks.size)
        assertEquals(12, passReport.bucketCounts["FRAMEWORK"])
        assertEquals("zygisk_next", passReport.modules.first().id)

        val failStdout = """
            CHECK|root_uid|PASS|Root UID 0 Access|UID 0
            CHECK|ce_storage|FAIL|CE Storage Keyring|CE storage not unlocked
            AUDIT_SUMMARY|FAIL|CE storage locked;
        """.trimIndent()

        val failReport = LogTelemetryParser.parseAudit(failStdout, exitCode = 2)
        assertFalse(failReport.passed)
        assertEquals(CheckOutcome.FAIL, failReport.checks[1].outcome)
    }

    @Test
    fun `parseLogLines and parseVerification extract phases and PID diffs`() {
        val logText = """
            [2026-11-24 11:36:01] [INFO] PHASE 0: AOSP Userspace Reboot + KernelSU Module Lifecycle Engine
            [2026-11-24 11:36:05] [WARN] SIGKILL lingering system_server PID=1420
            [2026-11-24 11:36:18] [INFO] PHASE 8: Finalizing Boot Completion, KSU boot-completed & Full Audit
            [2026-11-24 11:36:20] [INFO] Post Core  | zygote64=[18400] (was 912) | CTX=u:r:zygote:s0
            [2026-11-24 11:36:20] [INFO] Post Core  | system_server=[18512] (was 1420) | CTX=u:r:system_server:s0
            [2026-11-24 11:36:20] [INFO] Post Infra | installd=[18300] (was 710) | CTX=u:r:installd:s0 | statsd=[18305] (was 720)
            [2026-11-24 11:36:20] [INFO] Storage & Kernel | CE_Unlocked=true | 11:36:20 up 2:14,  0 users
            [2026-11-24 11:36:20] [INFO] === TRUE USERSPACE REBOOT COMPLETE ===
        """.trimIndent()

        val lines = LogTelemetryParser.parseLogLines(logText)
        assertEquals(8, lines.size)
        assertEquals(LogLevel.WARN, lines[1].level)
        assertEquals(8, LogTelemetryParser.extractHighestPhase(lines))

        val verification = LogTelemetryParser.parseVerification(lines)
        assertNotNull(verification)
        assertTrue(verification!!.completed)
        assertTrue(verification.ceUnlocked)
        assertEquals(4, verification.pidDiffs.size)
        assertEquals("zygote64", verification.pidDiffs[0].processName)
        assertEquals("912", verification.pidDiffs[0].oldPid)
        assertEquals("18400", verification.pidDiffs[0].newPid)
    }
}
