package com.example.model

sealed interface RootStatus {
    data object Checking : RootStatus
    data class Granted(
        val uid: Int,
        val selinuxContext: String,
        val providerLabel: String,
    ) : RootStatus

    data class Denied(val reason: String) : RootStatus
}

sealed interface SafetyGateState {
    data object Locked : SafetyGateState
    data object Auditing : SafetyGateState
    data object Passed : SafetyGateState
    data class Failed(val reason: String) : SafetyGateState
}

sealed interface EngineRunState {
    data object Idle : EngineRunState
    data class Running(val phase: Int, val phaseLabel: String) : EngineRunState
    data class Completed(val verification: RebootVerification) : EngineRunState
    data class Error(val message: String) : EngineRunState
}

data class DeviceTelemetry(
    val model: String = "Unknown",
    val magicOsVersion: String = "N/A",
    val androidRelease: String = "15",
    val sdkVersion: String = "35",
    val platform: String = "unknown",
    val hardware: String = "unknown",
    val kernelRelease: String = "unknown",
    val selinuxContext: String = "unknown",
    val ceAvailable: Boolean = false,
    val apexdStatus: String = "unknown",
    val ksuVersion: String = "none",
    val ksuModuleCount: Int = 0,
    val uptime: String = "unknown",
    val zygotePid: String = "",
    val systemServerPid: String = "",
    val surfaceFlingerPid: String = "",
)

enum class CheckOutcome {
    PASS,
    WARN,
    FAIL,
}

data class AuditCheckItem(
    val id: String,
    val outcome: CheckOutcome,
    val label: String,
    val detail: String,
)

data class ServiceEntry(
    val bucket: String,
    val name: String,
    val pid: String,
    val selinuxContext: String,
)

data class KsuModuleEntry(
    val active: Boolean,
    val id: String,
    val hooks: String,
)

data class AuditReport(
    val passed: Boolean,
    val summary: String,
    val checks: List<AuditCheckItem>,
    val bucketCounts: Map<String, Int>,
    val services: List<ServiceEntry>,
    val modules: List<KsuModuleEntry>,
    val dScripts: List<Pair<String, String>>,
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    RAW,
}

data class LogLine(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val raw: String,
)

data class PidDiffEntry(
    val processName: String,
    val oldPid: String,
    val newPid: String,
    val selinuxContext: String,
)

data class RebootVerification(
    val completed: Boolean,
    val ceUnlocked: Boolean,
    val uptimeLine: String,
    val pidDiffs: List<PidDiffEntry>,
)

data class MainUiState(
    val rootStatus: RootStatus = RootStatus.Checking,
    val safetyGate: SafetyGateState = SafetyGateState.Locked,
    val engineRunState: EngineRunState = EngineRunState.Idle,
    val telemetry: DeviceTelemetry = DeviceTelemetry(),
    val auditReport: AuditReport? = null,
    val currentLogs: List<LogLine> = emptyList(),
    val previousLogs: List<LogLine> = emptyList(),
    val showingPreviousLogs: Boolean = false,
    val autoScrollLogs: Boolean = true,
    val currentPhase: Int = -1,
    val showConfirmDialog: Boolean = false,
    val showAuditSheet: Boolean = false,
    val statusBannerMessage: String? = null,
)
