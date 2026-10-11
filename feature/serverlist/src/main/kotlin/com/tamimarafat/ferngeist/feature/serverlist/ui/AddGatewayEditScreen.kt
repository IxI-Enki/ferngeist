package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.formContentMaxWidth
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayUiState
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayViewModel
import com.tamimarafat.ferngeist.feature.serverlist.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditGatewayScreen(
    onNavigateBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    viewModel: AddGatewayViewModel,
    name: String,
    scheme: String,
    host: String,
    uiState: AddGatewayUiState,
) {
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.serverlist_add_gateway_edit_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateBack, modifier = Modifier.handCursor()) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.serverlist_back_desc),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .formContentMaxWidth()
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EditGatewayDetailsSection(
                    name = name,
                    scheme = scheme,
                    host = host,
                    uiState = uiState,
                    onUpdateName = viewModel::updateName,
                    onSelectScheme = viewModel::updateScheme,
                    onUpdateHost = viewModel::updateHost,
                    onCheckStatus = viewModel::checkStatus,
                    onSave = viewModel::saveGateway,
                )
            }
        }
    }
}

@Composable
private fun EditGatewayDetailsSection(
    name: String,
    scheme: String,
    host: String,
    uiState: AddGatewayUiState,
    onUpdateName: (String) -> Unit,
    onSelectScheme: (String) -> Unit,
    onUpdateHost: (String) -> Unit,
    onCheckStatus: () -> Unit,
    onSave: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.serverlist_add_gateway_details_title),
        subtitle = stringResource(R.string.serverlist_add_gateway_details_subtitle),
        icon = Icons.Default.Computer,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onUpdateName,
            label = { Text(stringResource(R.string.serverlist_add_gateway_name_label)) },
            placeholder = { Text(stringResource(R.string.serverlist_add_gateway_name_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        GatewayProtocolSelector(selected = scheme, onSelect = onSelectScheme)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = host,
            onValueChange = onUpdateHost,
            label = { Text(stringResource(R.string.serverlist_add_gateway_host_label)) },
            placeholder = { Text(stringResource(R.string.serverlist_add_gateway_host_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Default.Link,
                    contentDescription = stringResource(R.string.serverlist_add_server_host_label),
                )
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onCheckStatus,
            enabled = !uiState.isCheckingStatus,
            modifier = Modifier.handCursor(enabled = !uiState.isCheckingStatus),
        ) {
            if (uiState.isCheckingStatus) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.serverlist_add_gateway_check))
            }
        }
        uiState.status?.let { status ->
            Spacer(modifier = Modifier.height(12.dp))
            GatewayStatusCard(status = status)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onSave,
            enabled = !uiState.isSaving,
            modifier = Modifier.handCursor(enabled = !uiState.isSaving),
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.serverlist_add_gateway_save))
            }
        }
    }
}
