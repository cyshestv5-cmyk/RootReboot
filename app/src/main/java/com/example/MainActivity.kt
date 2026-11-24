package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.UserspaceRebootViewModel
import com.example.ui.screen.UserspaceRebootScreen
import com.example.ui.theme.MagicOsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        setContent {
            MagicOsTheme {
                val viewModel: UserspaceRebootViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                UserspaceRebootScreen(
                    uiState = uiState,
                    onRefreshEnvironment = viewModel::refreshEnvironment,
                    onRunAudit = viewModel::runDryRunAudit,
                    onOpenAuditSheet = viewModel::openAuditSheet,
                    onDismissAuditSheet = viewModel::dismissAuditSheet,
                    onRequestRebootConfirm = viewModel::requestRebootConfirmation,
                    onDismissRebootConfirm = viewModel::dismissRebootConfirmation,
                    onConfirmReboot = viewModel::triggerUserspaceReboot,
                    onToggleLogSource = viewModel::toggleLogSource,
                    onToggleAutoScroll = viewModel::toggleAutoScroll,
                    onRefreshLogs = viewModel::refreshLogsNow,
                )
            }
        }
    }
}
