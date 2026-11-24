package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AuditReport
import com.example.model.CheckOutcome
import com.example.ui.theme.MagicBadgeGreen
import com.example.ui.theme.MagicBadgeOrange
import com.example.ui.theme.MagicBadgeRed
import com.example.ui.theme.MagicBlueBright
import com.example.ui.theme.MagicCardElevated
import com.example.ui.theme.MagicCardSurface
import com.example.ui.theme.MagicNestedPill
import com.example.ui.theme.MagicTextPrimary
import com.example.ui.theme.MagicTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditBreakdownSheet(
    report: AuditReport,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expandedBucket by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MagicCardElevated,
        contentColor = MagicTextPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pre-Flight Audit Breakdown",
                            style = MaterialTheme.typography.titleLarge,
                            color = MagicTextPrimary,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = report.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (report.passed) MagicBadgeGreen else MagicBadgeRed,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_audit_sheet_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close audit breakdown",
                            tint = MagicTextSecondary,
                        )
                    }
                }
            }

            item {
                MagicSectionHeader(
                    title = "SAFETY INVARIANTS (${report.checks.size})",
                    modifier = Modifier.padding(horizontal = 0.dp),
                )
                MagicGroupedCard {
                    report.checks.forEachIndexed { index, check ->
                        val (icon, tint) = when (check.outcome) {
                            CheckOutcome.PASS -> Icons.Rounded.CheckCircle to MagicBadgeGreen
                            CheckOutcome.WARN -> Icons.Rounded.Warning to MagicBadgeOrange
                            CheckOutcome.FAIL -> Icons.Rounded.Error to MagicBadgeRed
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = check.outcome.name,
                                tint = tint,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = check.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MagicTextPrimary,
                                )
                                Text(
                                    text = check.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MagicTextSecondary,
                                )
                            }
                        }
                        if (index < report.checks.lastIndex) {
                            MagicInsetDivider(insetStart = 50.dp)
                        }
                    }
                }
            }

            item {
                MagicSectionHeader(
                    title = "INIT SERVICE CLASSIFICATION",
                    modifier = Modifier.padding(horizontal = 0.dp),
                )
                MagicGroupedCard {
                    val bucketEntries = report.bucketCounts.entries.toList()
                    bucketEntries.forEachIndexed { index, (bucket, count) ->
                        val isExpanded = expandedBucket == bucket
                        val bucketServices = report.services.filter { it.bucket == bucket }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("audit_bucket_$bucket")
                                    .clickable {
                                        expandedBucket = if (isExpanded) null else bucket
                                    }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = bucketLabel(bucket),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MagicTextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    color = MagicNestedPill,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text(
                                        text = "$count active",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (bucket == "PROTECTED") MagicBadgeGreen else MagicBlueBright,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                                    tint = MagicTextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            AnimatedVisibility(visible = isExpanded && bucketServices.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF141416))
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    bucketServices.forEach { svc ->
                                        Text(
                                            text = "${svc.name}  [pid:${svc.pid}]  ${svc.selinuxContext}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MagicTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        if (index < bucketEntries.lastIndex) {
                            MagicInsetDivider(insetStart = 16.dp)
                        }
                    }
                }
            }

            if (report.modules.isNotEmpty() || report.dScripts.isNotEmpty()) {
                item {
                    MagicSectionHeader(
                        title = "KERNELSU NEXT MODULES & HOOKS",
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )
                    MagicGroupedCard {
                        report.modules.forEachIndexed { idx, mod ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (mod.active) MagicBadgeGreen else MagicTextSecondary),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mod.id,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MagicTextPrimary,
                                    )
                                    Text(
                                        text = mod.hooks,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MagicTextSecondary,
                                    )
                                }
                            }
                            if (idx < report.modules.lastIndex || report.dScripts.isNotEmpty()) {
                                MagicInsetDivider(insetStart = 36.dp)
                            }
                        }
                        report.dScripts.forEachIndexed { idx, (stage, name) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MagicTextPrimary,
                                )
                                Text(
                                    text = stage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MagicBlueBright,
                                )
                            }
                            if (idx < report.dScripts.lastIndex) {
                                MagicInsetDivider(insetStart = 16.dp)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

private fun bucketLabel(key: String): String = when (key) {
    "FRAMEWORK" -> "Phase 2/5 • Framework & MagicOS"
    "CORE_INFRA" -> "Phase 2/4 • Core Infrastructure"
    "GRAPHICS_HAL" -> "Phase 3A • Display & Composer HALs"
    "AUDIO_HAL" -> "Phase 3B • QCOM AudioReach/PAL HALs"
    "VENDOR_HAL" -> "Phase 3C • Stateless Vendor HALs"
    "PROTECTED" -> "Immutable • Binder, TEE, DSP & Modem"
    "OBSERVE" -> "Auxiliary • Unclassified OEM Services"
    else -> key
}
