package com.example.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.LogTelemetryParser
import com.example.model.EngineRunState
import com.example.model.LogLevel
import com.example.model.LogLine
import com.example.model.MainUiState
import com.example.model.RebootVerification
import com.example.model.RootStatus
import com.example.model.SafetyGateState
import com.example.ui.components.AuditBreakdownSheet
import com.example.ui.components.MagicActionRow
import com.example.ui.components.MagicBentoTile
import com.example.ui.components.MagicConfirmDialog
import com.example.ui.components.MagicGroupedCard
import com.example.ui.components.MagicHeroOrb
import com.example.ui.components.MagicInfoKeyValueRow
import com.example.ui.components.MagicInsetDivider
import com.example.ui.components.MagicOsSwitch
import com.example.ui.components.MagicSectionHeader
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MagicBadgeBlue
import com.example.ui.theme.MagicBadgeGreen
import com.example.ui.theme.MagicBadgeMuted
import com.example.ui.theme.MagicBadgeOrange
import com.example.ui.theme.MagicBadgeRed
import com.example.ui.theme.MagicBadgeTeal
import com.example.ui.theme.MagicBlack
import com.example.ui.theme.MagicBlue
import com.example.ui.theme.MagicBlueBright
import com.example.ui.theme.MagicBlueGlow
import com.example.ui.theme.MagicCardSurface
import com.example.ui.theme.MagicNestedPill
import com.example.ui.theme.MagicTextPrimary
import com.example.ui.theme.MagicTextSecondary

@Composable
fun UserspaceRebootScreen(
    uiState: MainUiState,
    onRefreshEnvironment: () -> Unit,
    onRunAudit: () -> Unit,
    onOpenAuditSheet: () -> Unit,
    onDismissAuditSheet: () -> Unit,
    onRequestRebootConfirm: () -> Unit,
    onDismissRebootConfirm: () -> Unit,
    onConfirmReboot: () -> Unit,
    onToggleLogSource: (Boolean) -> Unit,
    onToggleAutoScroll: (Boolean) -> Unit,
    onRefreshLogs: () -> Unit,
) {
    val context = LocalContext.current

    if (uiState.showConfirmDialog) {
        MagicConfirmDialog(
            title = stringResource(R.string.dialog_confirm_title),
            body = stringResource(R.string.dialog_confirm_body),
            onDismiss = onDismissRebootConfirm,
            onConfirm = onConfirmReboot,
        )
    }

    if (uiState.showAuditSheet && uiState.auditReport != null) {
        AuditBreakdownSheet(
            report = uiState.auditReport,
            onDismiss = onDismissAuditSheet,
        )
    }

    Scaffold(
        containerColor = MagicBlack,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            ) {
                // 1. MagicOS Top Header
                item {
                    MagicTopHeader(
                        subtitle = if (uiState.telemetry.magicOsVersion != "N/A") {
                            "MagicOS ${uiState.telemetry.magicOsVersion} • Android ${uiState.telemetry.androidRelease}"
                        } else {
                            stringResource(R.string.header_subtitle)
                        },
                        onRefresh = onRefreshEnvironment,
                    )
                }

                // 2. Hero Orb Status Banner
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    HeroStatusCard(uiState = uiState)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 3. MagicOS Bento Telemetry Grid (inspired by About Phone)
                item {
                    BentoTelemetrySection(uiState = uiState)
                }

                // 4. Execution & Safety Gate Controls
                item {
                    MagicSectionHeader(title = stringResource(R.string.section_controls))
                    SafetyGateControlCard(
                        uiState = uiState,
                        onRunAudit = onRunAudit,
                        onOpenAuditSheet = onOpenAuditSheet,
                        onRequestRebootConfirm = onRequestRebootConfirm,
                    )
                }

                // 5. Post-Reboot Verification Diff (when ScriptLogs contains PID transitions)
                val verification = LogTelemetryParser.parseVerification(uiState.currentLogs)
                if (verification != null) {
                    item {
                        MagicSectionHeader(title = stringResource(R.string.section_verification))
                        PostRebootVerificationCard(verification = verification)
                    }
                }

                // 6. Live Engine Log Viewer
                item {
                    MagicSectionHeader(title = stringResource(R.string.section_logs))
                    LiveLogViewerCard(
                        uiState = uiState,
                        onToggleLogSource = onToggleLogSource,
                        onToggleAutoScroll = onToggleAutoScroll,
                        onRefreshLogs = onRefreshLogs,
                        onCopyLogs = {
                            val lines = if (uiState.showingPreviousLogs) uiState.previousLogs else uiState.currentLogs
                            val text = lines.joinToString("\n") { it.raw }
                            if (text.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("UserspaceRebootLog", text))
                                Toast.makeText(context, "Copied ${lines.size} log lines", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MagicTopHeader(
    subtitle: String,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MagicTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MagicTextSecondary,
            )
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("refresh_probe_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "Refresh root and system telemetry",
                tint = MagicTextSecondary,
            )
        }
    }
}

@Composable
private fun HeroStatusCard(uiState: MainUiState) {
    val (orbIcon, orbPrimary, orbSecondary, animating, title, desc) = when {
        uiState.rootStatus is RootStatus.Checking -> HeroSpec(
            icon = Icons.Rounded.Sync,
            primary = MagicBlue,
            secondary = MagicBlueGlow,
            animating = true,
            title = stringResource(R.string.status_checking_root),
            desc = "Requesting root probe & reading KernelSU Next status…",
        )
        uiState.rootStatus is RootStatus.Denied -> HeroSpec(
            icon = Icons.Rounded.GppBad,
            primary = MagicBadgeRed,
            secondary = Color(0xFFFF7875),
            animating = false,
            title = stringResource(R.string.status_no_root),
            desc = uiState.rootStatus.reason,
        )
        uiState.engineRunState is EngineRunState.Running -> HeroSpec(
            icon = Icons.Rounded.RestartAlt,
            primary = MagicBlue,
            secondary = MagicBlueBright,
            animating = true,
            title = stringResource(R.string.status_rebooting),
            desc = uiState.engineRunState.phaseLabel,
        )
        uiState.engineRunState is EngineRunState.Error -> HeroSpec(
            icon = Icons.Rounded.GppBad,
            primary = MagicBadgeRed,
            secondary = Color(0xFFFF7875),
            animating = false,
            title = stringResource(R.string.status_failed),
            desc = uiState.engineRunState.message,
        )
        uiState.safetyGate is SafetyGateState.Auditing -> HeroSpec(
            icon = Icons.AutoMirrored.Rounded.FactCheck,
            primary = MagicBadgeTeal,
            secondary = MagicBlueBright,
            animating = true,
            title = "Running Pre-Flight Audit…",
            desc = "Inspecting init services, CE storage, APEX & KSU hooks.",
        )
        uiState.safetyGate is SafetyGateState.Passed -> HeroSpec(
            icon = Icons.Rounded.Shield,
            primary = MagicBadgeGreen,
            secondary = MagicBadgeTeal,
            animating = false,
            title = stringResource(R.string.status_ready),
            desc = "Dry-run audit passed. Full AOSP + KSU Next trigger unlocked.",
        )
        uiState.engineRunState is EngineRunState.Completed -> HeroSpec(
            icon = Icons.Rounded.Verified,
            primary = MagicBadgeGreen,
            secondary = MagicBlueGlow,
            animating = false,
            title = stringResource(R.string.status_completed),
            desc = "Last userspace reboot succeeded. Run dry-run audit to unlock next run.",
        )
        uiState.safetyGate is SafetyGateState.Failed -> HeroSpec(
            icon = Icons.Rounded.Lock,
            primary = MagicBadgeOrange,
            secondary = MagicBadgeRed,
            animating = false,
            title = "Safety Gate Blocked",
            desc = uiState.safetyGate.reason,
        )
        else -> HeroSpec(
            icon = Icons.Rounded.Lock,
            primary = MagicBlue,
            secondary = MagicBlueBright,
            animating = false,
            title = stringResource(R.string.status_audit_required),
            desc = "Run Pre-Flight Dry-Run Audit before unlocking Userspace Reboot.",
        )
    }

    MagicGroupedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MagicHeroOrb(
                icon = orbIcon,
                primaryColor = orbPrimary,
                secondaryColor = orbSecondary,
                isAnimating = animating,
                contentDescription = title,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MagicTextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MagicTextSecondary,
                )
            }
        }
    }
}

private data class HeroSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val primary: Color,
    val secondary: Color,
    val animating: Boolean,
    val title: String,
    val desc: String,
)

@Composable
private fun BentoTelemetrySection(uiState: MainUiState) {
    val t = uiState.telemetry
    val rootGranted = uiState.rootStatus as? RootStatus.Granted

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MagicBentoTile(
                label = "Device & SoC",
                value = t.model,
                pillBadge = "${t.platform} (${t.hardware})",
                modifier = Modifier.weight(1f),
            )
            MagicBentoTile(
                label = "Kernel & Userspace",
                value = "Android ${t.androidRelease} (SDK ${t.sdkVersion})",
                pillBadge = t.kernelRelease.substringBefore("-g"),
                modifier = Modifier.weight(1f),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MagicCardSurface,
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Root & SELinux",
                        style = MaterialTheme.typography.bodySmall,
                        color = MagicTextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = rootGranted?.let { "${it.providerLabel} • UID 0" }
                            ?: if (uiState.rootStatus is RootStatus.Checking) "Probing…" else "Denied",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (rootGranted != null) MagicBadgeGreen else MagicBadgeOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = rootGranted?.selinuxContext ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MagicTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "KernelSU Next & CE",
                        style = MaterialTheme.typography.bodySmall,
                        color = MagicTextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${t.ksuModuleCount} Active Modules",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MagicTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "CE=${if (t.ceAvailable) "Unlocked" else "Locked"} • APEX=${t.apexdStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (t.ceAvailable) MagicBadgeGreen else MagicTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SafetyGateControlCard(
    uiState: MainUiState,
    onRunAudit: () -> Unit,
    onOpenAuditSheet: () -> Unit,
    onRequestRebootConfirm: () -> Unit,
) {
    val rootOk = uiState.rootStatus is RootStatus.Granted
    val auditRunning = uiState.safetyGate is SafetyGateState.Auditing
    val rebootRunning = uiState.engineRunState is EngineRunState.Running
    val rebootUnlocked = uiState.safetyGate is SafetyGateState.Passed && !rebootRunning

    val auditTrailing = when (uiState.safetyGate) {
        SafetyGateState.Locked -> "Required"
        SafetyGateState.Auditing -> "Auditing…"
        SafetyGateState.Passed -> "Passed"
        is SafetyGateState.Failed -> "Failed"
    }
    val auditTrailingColor = when (uiState.safetyGate) {
        SafetyGateState.Passed -> MagicBadgeGreen
        is SafetyGateState.Failed -> MagicBadgeRed
        SafetyGateState.Auditing -> MagicBlueBright
        SafetyGateState.Locked -> MagicBadgeOrange
    }

    MagicGroupedCard {
        MagicActionRow(
            title = stringResource(R.string.action_run_audit),
            subtitle = "Verify UID 0, PID 1 mount ns, CE keyring, HALs & KSU hooks",
            icon = Icons.AutoMirrored.Rounded.FactCheck,
            badgeColor = MagicBadgeGreen,
            trailingValue = auditTrailing,
            trailingColor = auditTrailingColor,
            enabled = rootOk && !auditRunning && !rebootRunning,
            testTag = "run_audit_button",
            onClick = onRunAudit,
        )

        AnimatedVisibility(visible = uiState.auditReport != null) {
            Column {
                MagicInsetDivider(insetStart = 68.dp)
                val report = uiState.auditReport
                MagicActionRow(
                    title = stringResource(R.string.action_view_audit_details),
                    subtitle = "Service buckets, protected anchors & KSU module hooks",
                    icon = Icons.Rounded.Checklist,
                    badgeColor = MagicBadgeTeal,
                    trailingValue = report?.let { "${it.services.size} svcs • ${it.modules.size} mods" },
                    testTag = "inspect_audit_button",
                    onClick = onOpenAuditSheet,
                )
            }
        }

        MagicInsetDivider(insetStart = 68.dp)

        MagicActionRow(
            title = stringResource(R.string.action_userspace_reboot),
            subtitle = if (rebootUnlocked) {
                "Full AOSP + KernelSU Next lifecycle (kernel stays alive)"
            } else {
                "Locked — pass Pre-Flight Dry-Run Audit to unlock"
            },
            icon = if (rebootUnlocked) Icons.Rounded.RestartAlt else Icons.Rounded.Lock,
            badgeColor = if (rebootUnlocked) MagicBlue else MagicBadgeMuted,
            trailingValue = when {
                rebootRunning -> "Running"
                rebootUnlocked -> "Unlocked"
                else -> "Locked"
            },
            trailingColor = if (rebootUnlocked) MagicBlueBright else MagicTextSecondary,
            enabled = rebootUnlocked,
            testTag = "userspace_reboot_button",
            onClick = onRequestRebootConfirm,
        )
    }
}

@Composable
private fun PostRebootVerificationCard(verification: RebootVerification) {
    MagicGroupedCard {
        MagicInfoKeyValueRow(
            label = "Engine State",
            value = if (verification.completed) "Userspace Reboot Verified" else "Execution In Progress",
            valueColor = if (verification.completed) MagicBadgeGreen else MagicBlueBright,
        )
        MagicInsetDivider(insetStart = 16.dp)
        MagicInfoKeyValueRow(
            label = "User 0 CE Storage",
            value = if (verification.ceUnlocked) "Unlocked (true)" else "Unknown",
            valueColor = if (verification.ceUnlocked) MagicBadgeGreen else MagicBadgeOrange,
        )
        if (verification.uptimeLine.isNotBlank()) {
            MagicInsetDivider(insetStart = 16.dp)
            MagicInfoKeyValueRow(
                label = "Preserved Uptime",
                value = verification.uptimeLine,
            )
        }
        verification.pidDiffs.forEach { diff ->
            MagicInsetDivider(insetStart = 16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = diff.processName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MagicTextPrimary,
                    )
                    if (diff.selinuxContext != "init-managed" && diff.selinuxContext != "—") {
                        Text(
                            text = diff.selinuxContext,
                            style = MaterialTheme.typography.labelSmall,
                            color = MagicTextSecondary,
                        )
                    }
                }
                Surface(
                    color = MagicNestedPill,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = "${diff.oldPid} → ${diff.newPid}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (diff.newPid != diff.oldPid && diff.newPid != "none") {
                            MagicBadgeGreen
                        } else {
                            MagicBadgeOrange
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveLogViewerCard(
    uiState: MainUiState,
    onToggleLogSource: (Boolean) -> Unit,
    onToggleAutoScroll: (Boolean) -> Unit,
    onRefreshLogs: () -> Unit,
    onCopyLogs: () -> Unit,
) {
    val lines = if (uiState.showingPreviousLogs) uiState.previousLogs else uiState.currentLogs
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size, uiState.autoScrollLogs) {
        if (uiState.autoScrollLogs && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    MagicGroupedCard {
        // Phase progress bar when running or recently run
        if (uiState.currentPhase >= 0 && !uiState.showingPreviousLogs) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = LogTelemetryParser.phaseLabel(uiState.currentPhase),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MagicBlueBright,
                    )
                    Text(
                        text = "${uiState.currentPhase}/8",
                        style = MaterialTheme.typography.labelSmall,
                        color = MagicTextSecondary,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { ((uiState.currentPhase + 1) / 9f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (uiState.currentPhase >= 8) MagicBadgeGreen else MagicBlue,
                    trackColor = MagicNestedPill,
                )
            }
        }

        // Toolbar: Current/Previous pill + Auto-scroll switch + Refresh/Copy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MagicNestedPill)
                    .padding(3.dp),
            ) {
                LogTabPill(
                    label = "Current",
                    selected = !uiState.showingPreviousLogs,
                    testTag = "log_tab_current",
                    onClick = { onToggleLogSource(false) },
                )
                LogTabPill(
                    label = "Previous",
                    selected = uiState.showingPreviousLogs,
                    testTag = "log_tab_previous",
                    onClick = { onToggleLogSource(true) },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Auto",
                    style = MaterialTheme.typography.bodySmall,
                    color = MagicTextSecondary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                MagicOsSwitch(
                    checked = uiState.autoScrollLogs,
                    onCheckedChange = onToggleAutoScroll,
                    testTag = "log_autoscroll_switch",
                )
                IconButton(
                    onClick = onRefreshLogs,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("refresh_logs_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reload log file",
                        tint = MagicTextSecondary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(
                    onClick = onCopyLogs,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("copy_logs_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy logs to clipboard",
                        tint = MagicTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        MagicInsetDivider(insetStart = 12.dp)

        if (lines.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = "No log output yet",
                    tint = MagicTextSecondary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (uiState.showingPreviousLogs) {
                        "No previous log at /data/adb/ScriptLogs.prev.txt"
                    } else {
                        "No log yet at /data/adb/ScriptLogs.txt.\nPass Pre-Flight Audit and run Userspace Reboot to stream live telemetry."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MagicTextSecondary,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp)
                    .background(Color(0xFF101012))
                    .testTag("live_log_list"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(lines) { line ->
                    LogLineRow(line = line)
                }
            }
        }
    }
}

@Composable
private fun LogTabPill(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .testTag(testTag)
            .clickable(onClick = onClick),
        color = if (selected) MagicBlue else Color.Transparent,
        shape = RoundedCornerShape(11.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) Color.White else MagicTextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LogLineRow(line: LogLine) {
    val msgColor = when (line.level) {
        LogLevel.WARN -> MagicBadgeOrange
        LogLevel.ERROR -> MagicBadgeRed
        LogLevel.INFO -> Color(0xFFE5E5EA)
        LogLevel.RAW -> MagicTextSecondary
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        if (line.timestamp.isNotBlank()) {
            val shortTs = line.timestamp.substringAfter(' ', line.timestamp)
            Text(
                text = "$shortTs ",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MagicBlueBright,
            )
        }
        Text(
            text = line.message,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = msgColor,
        )
    }
}
