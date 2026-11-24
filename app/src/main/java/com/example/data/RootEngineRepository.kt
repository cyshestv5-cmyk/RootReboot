package com.example.data

import com.example.model.AuditReport
import com.example.model.DeviceTelemetry
import com.example.model.LogLine
import com.example.model.RootStatus
import java.io.File

interface EngineRepository {
    suspend fun checkRootAccess(): RootStatus
    suspend fun fetchTelemetry(): DeviceTelemetry
    suspend fun runPreFlightAudit(): Result<AuditReport>
    suspend fun launchUserspaceReboot(): Result<String>
    suspend fun readLogLines(previous: Boolean): List<LogLine>
}

class RootEngineRepository(
    private val shellRunner: RootShellRunner,
) : EngineRepository {

    companion object {
        const val LOG_PATH = "/data/adb/ScriptLogs.txt"
        const val PREV_LOG_PATH = "/data/adb/ScriptLogs.prev.txt"
    }

    override suspend fun checkRootAccess(): RootStatus {
        val result = shellRunner.runRootCommand(
            "echo \"UID=$(id -u)|CTX=$(cat /proc/self/attr/current 2>/dev/null | tr -d '\\0')\"",
            timeoutSec = 8,
        )
        if (result.exitCode != 0) {
            val errMsg = result.stderr.trim().ifBlank { "su exited with code ${result.exitCode}" }
            return RootStatus.Denied(errMsg)
        }
        val line = result.stdout.lineSequence().firstOrNull { it.contains("UID=") }.orEmpty()
        val uid = line.substringAfter("UID=", "").substringBefore("|").trim().toIntOrNull()
        val ctx = line.substringAfter("CTX=", "unknown").trim().ifBlank { "unknown" }
        return if (uid == 0) {
            val provider = when {
                ctx.contains("ksu", ignoreCase = true) -> "KernelSU"
                ctx.contains("magisk", ignoreCase = true) -> "Magisk"
                ctx.contains("apatch", ignoreCase = true) || ctx.contains("apd", ignoreCase = true) -> "APatch"
                else -> "Root UID 0"
            }
            RootStatus.Granted(uid = 0, selinuxContext = ctx, providerLabel = provider)
        } else {
            RootStatus.Denied("Root access denied (uid=${uid ?: "unknown"})")
        }
    }

    override suspend fun fetchTelemetry(): DeviceTelemetry {
        val scriptPath = shellRunner.ensureEngineScriptDeployed()
        val result = shellRunner.runRootCommand("/system/bin/sh \"$scriptPath\" --probe", timeoutSec = 10)
        return if (result.exitCode == 0 && result.stdout.contains("PROBE|")) {
            LogTelemetryParser.parseProbe(result.stdout)
        } else {
            DeviceTelemetry()
        }
    }

    override suspend fun runPreFlightAudit(): Result<AuditReport> {
        val scriptPath = shellRunner.ensureEngineScriptDeployed()
        val result = shellRunner.runRootCommand("/system/bin/sh \"$scriptPath\" --audit", timeoutSec = 20)
        if (result.stdout.isBlank() && result.exitCode != 0 && result.exitCode != 2) {
            return Result.failure(IllegalStateException(result.stderr.ifBlank { "Audit failed (${result.exitCode})" }))
        }
        val report = LogTelemetryParser.parseAudit(result.stdout, result.exitCode)
        return Result.success(report)
    }

    override suspend fun launchUserspaceReboot(): Result<String> {
        val scriptPath = shellRunner.ensureEngineScriptDeployed()
        // Run via /system/bin/sh; script enters PID 1 mnt ns via nsenter, detaches with setsid, and escapes cgroup.
        val result = shellRunner.runRootCommand("/system/bin/sh \"$scriptPath\"", timeoutSec = 10)
        return if (result.exitCode == 0) {
            Result.success(result.stdout.trim().ifBlank { "Detached userspace reboot engine started." })
        } else {
            Result.failure(
                IllegalStateException(
                    result.stderr.ifBlank { result.stdout.ifBlank { "Failed to launch engine (exit ${result.exitCode})" } },
                ),
            )
        }
    }

    override suspend fun readLogLines(previous: Boolean): List<LogLine> {
        val path = if (previous) PREV_LOG_PATH else LOG_PATH
        val directFile = File(path)
        if (directFile.canRead()) {
            val text = runCatching { directFile.readText() }.getOrNull()
            if (text != null) {
                return LogTelemetryParser.parseLogLines(text)
            }
        }
        val result = shellRunner.runRootCommand("tail -n 350 \"$path\" 2>/dev/null", timeoutSec = 6)
        return if (result.exitCode == 0 && result.stdout.isNotBlank()) {
            LogTelemetryParser.parseLogLines(result.stdout)
        } else {
            emptyList()
        }
    }
}
