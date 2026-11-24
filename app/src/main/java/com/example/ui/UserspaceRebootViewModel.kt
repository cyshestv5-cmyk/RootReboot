package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.EngineRepository
import com.example.data.LogTelemetryParser
import com.example.data.RootEngineRepository
import com.example.data.RootShellRunner
import com.example.model.EngineRunState
import com.example.model.MainUiState
import com.example.model.RootStatus
import com.example.model.SafetyGateState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UserspaceRebootViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: EngineRepository = RootEngineRepository(RootShellRunner(application)),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var logTailJob: Job? = null

    init {
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            _uiState.update { it.copy(rootStatus = RootStatus.Checking, statusBannerMessage = null) }
            val root = repository.checkRootAccess()
            if (root !is RootStatus.Granted) {
                _uiState.update {
                    it.copy(
                        rootStatus = root,
                        safetyGate = SafetyGateState.Locked,
                    )
                }
                return@launch
            }

            val telemetry = repository.fetchTelemetry()
            val currentLogs = repository.readLogLines(previous = false)
            val prevLogs = repository.readLogLines(previous = true)
            val highestPhase = LogTelemetryParser.extractHighestPhase(currentLogs)
            val verification = LogTelemetryParser.parseVerification(currentLogs)

            val restoredRunState = when {
                verification?.completed == true -> EngineRunState.Completed(verification)
                highestPhase in 0..7 && currentLogs.isNotEmpty() -> {
                    EngineRunState.Running(highestPhase, LogTelemetryParser.phaseLabel(highestPhase))
                }
                else -> EngineRunState.Idle
            }

            _uiState.update {
                it.copy(
                    rootStatus = root,
                    telemetry = telemetry,
                    currentLogs = currentLogs,
                    previousLogs = prevLogs,
                    currentPhase = highestPhase,
                    engineRunState = restoredRunState,
                )
            }
        }
    }

    fun runDryRunAudit() {
        val currentRoot = _uiState.value.rootStatus
        if (currentRoot !is RootStatus.Granted) {
            refreshEnvironment()
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    safetyGate = SafetyGateState.Auditing,
                    statusBannerMessage = null,
                )
            }
            val result = repository.runPreFlightAudit()
            result.fold(
                onSuccess = { report ->
                    _uiState.update {
                        it.copy(
                            auditReport = report,
                            safetyGate = if (report.passed) {
                                SafetyGateState.Passed
                            } else {
                                SafetyGateState.Failed(report.summary)
                            },
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            safetyGate = SafetyGateState.Failed(err.message ?: "Dry-run audit failed"),
                        )
                    }
                },
            )
        }
    }

    fun requestRebootConfirmation() {
        if (_uiState.value.safetyGate !is SafetyGateState.Passed) return
        _uiState.update { it.copy(showConfirmDialog = true) }
    }

    fun dismissRebootConfirmation() {
        _uiState.update { it.copy(showConfirmDialog = false) }
    }

    fun openAuditSheet() {
        if (_uiState.value.auditReport != null) {
            _uiState.update { it.copy(showAuditSheet = true) }
        }
    }

    fun dismissAuditSheet() {
        _uiState.update { it.copy(showAuditSheet = false) }
    }

    fun toggleLogSource(showPrevious: Boolean) {
        _uiState.update { it.copy(showingPreviousLogs = showPrevious) }
        viewModelScope.launch {
            val lines = repository.readLogLines(previous = showPrevious)
            _uiState.update {
                if (showPrevious) it.copy(previousLogs = lines) else it.copy(currentLogs = lines)
            }
        }
    }

    fun toggleAutoScroll(enabled: Boolean) {
        _uiState.update { it.copy(autoScrollLogs = enabled) }
    }

    fun refreshLogsNow() {
        viewModelScope.launch {
            val showingPrev = _uiState.value.showingPreviousLogs
            val lines = repository.readLogLines(previous = showingPrev)
            _uiState.update { state ->
                if (showingPrev) {
                    state.copy(previousLogs = lines)
                } else {
                    val phase = LogTelemetryParser.extractHighestPhase(lines)
                    val verification = LogTelemetryParser.parseVerification(lines)
                    state.copy(
                        currentLogs = lines,
                        currentPhase = phase,
                        engineRunState = if (verification?.completed == true) {
                            EngineRunState.Completed(verification)
                        } else {
                            state.engineRunState
                        },
                    )
                }
            }
        }
    }

    fun triggerUserspaceReboot() {
        if (_uiState.value.safetyGate !is SafetyGateState.Passed) return
        _uiState.update {
            it.copy(
                showConfirmDialog = false,
                showingPreviousLogs = false,
                engineRunState = EngineRunState.Running(0, LogTelemetryParser.phaseLabel(0)),
                currentPhase = 0,
                statusBannerMessage = null,
            )
        }
        viewModelScope.launch {
            val launchResult = repository.launchUserspaceReboot()
            launchResult.fold(
                onSuccess = { msg ->
                    _uiState.update { it.copy(statusBannerMessage = msg) }
                    startLiveLogTailing()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            engineRunState = EngineRunState.Error(
                                err.message ?: "Failed to launch detached reboot engine",
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun startLiveLogTailing() {
        logTailJob?.cancel()
        logTailJob = viewModelScope.launch {
            while (isActive) {
                val lines = repository.readLogLines(previous = false)
                val phase = LogTelemetryParser.extractHighestPhase(lines)
                val verification = LogTelemetryParser.parseVerification(lines)

                if (verification?.completed == true) {
                    _uiState.update {
                        it.copy(
                            currentLogs = lines,
                            currentPhase = 8,
                            engineRunState = EngineRunState.Completed(verification),
                        )
                    }
                    break
                } else if (lines.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            currentLogs = lines,
                            currentPhase = phase,
                            engineRunState = EngineRunState.Running(
                                phase = phase.coerceAtLeast(0),
                                phaseLabel = LogTelemetryParser.phaseLabel(phase.coerceAtLeast(0)),
                            ),
                        )
                    }
                }
                delay(250)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        logTailJob?.cancel()
    }
}
