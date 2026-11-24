package com.example.data

import com.example.model.AuditCheckItem
import com.example.model.AuditReport
import com.example.model.CheckOutcome
import com.example.model.DeviceTelemetry
import com.example.model.KsuModuleEntry
import com.example.model.LogLevel
import com.example.model.LogLine
import com.example.model.PidDiffEntry
import com.example.model.RebootVerification
import com.example.model.ServiceEntry

object LogTelemetryParser {

    private val logLineRegex = Regex("""^\[([^\]]+)]\s+\[(INFO|WARN|ERROR)]\s+(.*)$""")
    private val phaseRegex = Regex("""PHASE\s+([0-8])\b""")
    private val pidDiffRegex = Regex("""([a-zA-Z0-9_.-]+)=\[([^\]]*)]\s+\(was\s+([^)]*)\)""")
    private val ctxRegex = Regex("""CTX=([^\s|]+)""")

    fun parseProbe(stdout: String): DeviceTelemetry {
        val map = mutableMapOf<String, String>()
        stdout.lineSequence().forEach { line ->
            if (line.startsWith("PROBE|")) {
                val kv = line.removePrefix("PROBE|")
                val idx = kv.indexOf('=')
                if (idx > 0) {
                    map[kv.substring(0, idx).trim()] = kv.substring(idx + 1).trim()
                }
            }
        }
        return DeviceTelemetry(
            model = map["model"].orEmpty().ifBlank { "Unknown" },
            magicOsVersion = map["magicos"].orEmpty().ifBlank { "N/A" },
            androidRelease = map["android"].orEmpty().ifBlank { "15" },
            sdkVersion = map["sdk"].orEmpty().ifBlank { "35" },
            platform = map["platform"].orEmpty().ifBlank { "unknown" },
            hardware = map["hardware"].orEmpty().ifBlank { "unknown" },
            kernelRelease = map["kernel"].orEmpty().ifBlank { "unknown" },
            selinuxContext = map["selinux"].orEmpty().ifBlank { "unknown" },
            ceAvailable = map["ce_available"].equals("true", ignoreCase = true),
            apexdStatus = map["apexd"].orEmpty().ifBlank { "unknown" },
            ksuVersion = map["ksu_ver"].orEmpty().ifBlank { "none" },
            ksuModuleCount = map["ksu_modules"]?.toIntOrNull() ?: 0,
            uptime = map["uptime"].orEmpty().ifBlank { "unknown" },
            zygotePid = map["zygote64"].orEmpty(),
            systemServerPid = map["system_server"].orEmpty(),
            surfaceFlingerPid = map["surfaceflinger"].orEmpty(),
        )
    }

    fun parseAudit(stdout: String, exitCode: Int): AuditReport {
        val checks = mutableListOf<AuditCheckItem>()
        val bucketCounts = linkedMapOf<String, Int>()
        val services = mutableListOf<ServiceEntry>()
        val modules = mutableListOf<KsuModuleEntry>()
        val dScripts = mutableListOf<Pair<String, String>>()
        var summaryPass: Boolean? = null
        var summaryMessage = ""

        stdout.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "CHECK" -> if (parts.size >= 5) {
                    val outcome = when (parts[2]) {
                        "PASS" -> CheckOutcome.PASS
                        "WARN" -> CheckOutcome.WARN
                        else -> CheckOutcome.FAIL
                    }
                    checks += AuditCheckItem(
                        id = parts[1],
                        outcome = outcome,
                        label = parts[3],
                        detail = parts.drop(4).joinToString("|"),
                    )
                }

                "BUCKET" -> if (parts.size >= 3) {
                    bucketCounts[parts[1]] = parts[2].toIntOrNull() ?: 0
                }

                "SVC" -> if (parts.size >= 5) {
                    services += ServiceEntry(
                        bucket = parts[1],
                        name = parts[2],
                        pid = parts[3],
                        selinuxContext = parts[4],
                    )
                }

                "MOD" -> if (parts.size >= 4) {
                    modules += KsuModuleEntry(
                        active = parts[1] == "ACTIVE",
                        id = parts[2],
                        hooks = parts[3],
                    )
                }

                "DSCRIPT" -> if (parts.size >= 3) {
                    dScripts += parts[1] to parts[2]
                }

                "AUDIT_SUMMARY" -> if (parts.size >= 3) {
                    summaryPass = parts[1] == "PASS"
                    summaryMessage = parts.drop(2).joinToString("|").trim()
                }
            }
        }

        val hasFailCheck = checks.any { it.outcome == CheckOutcome.FAIL }
        val passed = (summaryPass ?: (exitCode == 0)) && !hasFailCheck && checks.isNotEmpty()
        val finalSummary = summaryMessage.ifBlank {
            if (passed) "All pre-flight invariants verified." else "Pre-flight checks did not pass."
        }

        return AuditReport(
            passed = passed,
            summary = finalSummary,
            checks = checks,
            bucketCounts = bucketCounts,
            services = services,
            modules = modules,
            dScripts = dScripts,
        )
    }

    fun parseLogLines(rawText: String): List<LogLine> {
        if (rawText.isBlank()) return emptyList()
        return rawText.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val match = logLineRegex.matchEntire(line)
                if (match != null) {
                    val level = when (match.groupValues[2]) {
                        "WARN" -> LogLevel.WARN
                        "ERROR" -> LogLevel.ERROR
                        else -> LogLevel.INFO
                    }
                    LogLine(
                        timestamp = match.groupValues[1],
                        level = level,
                        message = match.groupValues[3],
                        raw = line,
                    )
                } else {
                    LogLine(
                        timestamp = "",
                        level = LogLevel.RAW,
                        message = line,
                        raw = line,
                    )
                }
            }
            .toList()
    }

    fun extractHighestPhase(lines: List<LogLine>): Int {
        var maxPhase = -1
        for (line in lines) {
            val match = phaseRegex.find(line.message)
            if (match != null) {
                val phase = match.groupValues[1].toIntOrNull() ?: -1
                if (phase > maxPhase) maxPhase = phase
            }
        }
        return maxPhase
    }

    fun parseVerification(lines: List<LogLine>): RebootVerification? {
        if (lines.isEmpty()) return null
        val pidDiffs = mutableListOf<PidDiffEntry>()
        var ceUnlocked = false
        var uptimeLine = ""
        var completed = false

        for (line in lines) {
            val msg = line.message
            if (msg.contains("=== TRUE USERSPACE REBOOT COMPLETE ===")) {
                completed = true
            }
            if (msg.startsWith("Post Core") || msg.startsWith("Post Infra") || msg.startsWith("Post Media")) {
                val ctxMatch = ctxRegex.find(msg)
                val defaultCtx = ctxMatch?.groupValues?.get(1).orEmpty().ifBlank { "—" }
                val segments = msg.split('|')
                for (segment in segments) {
                    val m = pidDiffRegex.find(segment)
                    if (m != null) {
                        val proc = m.groupValues[1]
                        val newPid = m.groupValues[2].ifBlank { "none" }
                        val oldPid = m.groupValues[3].ifBlank { "none" }
                        val segCtx = if (msg.startsWith("Post Core")) defaultCtx else "init-managed"
                        pidDiffs += PidDiffEntry(
                            processName = proc,
                            oldPid = oldPid,
                            newPid = newPid,
                            selinuxContext = segCtx,
                        )
                    }
                }
            }
            if (msg.startsWith("Storage & Kernel")) {
                ceUnlocked = msg.contains("CE_Unlocked=true")
                val parts = msg.split('|')
                if (parts.size >= 3) {
                    uptimeLine = parts.last().trim()
                }
            }
        }

        if (!completed && pidDiffs.isEmpty()) return null
        return RebootVerification(
            completed = completed,
            ceUnlocked = ceUnlocked,
            uptimeLine = uptimeLine,
            pidDiffs = pidDiffs,
        )
    }

    fun phaseLabel(phase: Int): String = when (phase) {
        0 -> "Phase 0: Cgroup Escape & Wakelock"
        1 -> "Phase 1: KSU Teardown & Prop Scrub"
        2 -> "Phase 2: Zygote, App Cgroups & FUSE Reset"
        3 -> "Phase 3: Bottom-Up HAL Reset"
        4 -> "Phase 4: APEX, SurfaceFlinger & post-fs-data"
        5 -> "Phase 5: MagicOS, Zygote & KSU service.sh"
        6 -> "Phase 6: 11-Service Binder Watchdog"
        7 -> "Phase 7: Class Reset Escalation"
        8 -> "Phase 8: Boot Complete & PID Audit"
        else -> "Standby"
    }
}
