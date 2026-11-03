package com.wangzan101.mika.ui

import android.content.Context
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.superagent.SuperagentSettings
import com.wangzan101.mika.core.superagent.TelegramGroupPolicy
import com.wangzan101.mika.core.superagent.FileSuperagentSettingsStore
import com.wangzan101.mika.core.superagent.TelegramConfig
import com.wangzan101.mika.superagent.SuperagentRuntimeService
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SuperagentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as com.wangzan101.mika.MikaApp
    val scope = rememberCoroutineScope()
    val store = remember { FileSuperagentSettingsStore(File(context.filesDir, "superagent/settings.json").toPath()) }
    var settings by remember { mutableStateOf(SuperagentSettings()) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        settings = withContext(Dispatchers.IO) { store.load() }
    }
    PageScaffold(stringResource(R.string.superagent_title), onBack) {
        GroupLabel(stringResource(R.string.superagent_runtime))
        GroupCard {
            ListRow(
                MikaIcons.Spark,
                stringResource(R.string.superagent_enabled),
                stringResource(R.string.superagent_enabled_sub),
                first = true,
            ) { MikaSwitch(settings.enabled, { on ->
                val next = settings.copy(enabled = on, telegram = settings.telegram)
                settings = next
                scope.launch {
                    withContext(Dispatchers.IO) { store.save(next) }
                    app.superHost.setEnabled(on)
                    if (on) SuperagentRuntimeService.start(context) else SuperagentRuntimeService.stop(context)
                }
            }) }
            ListRow(
                MikaIcons.Message,
                stringResource(R.string.superagent_telegram),
                stringResource(R.string.superagent_telegram_sub),
                onClick = { editing = true },
            ) {
                val source = settings.telegram
                RowValue(if (settings.telegramReady) source!!.instance else stringResource(R.string.settings_not_set))
                RowChevron()
            }
        }
        FooterNote(stringResource(R.string.superagent_note))
    }
    if (editing) {
        TelegramDialog(
            settings,
            onDismiss = { editing = false },
        ) { next ->
            editing = false
            settings = next
            scope.launch {
                withContext(Dispatchers.IO) { store.save(next) }
                app.superHost.setEnabled(next.enabled)
                if (next.enabled) SuperagentRuntimeService.start(context) else SuperagentRuntimeService.stop(context)
            }
        }
    }
}

@Composable
private fun TelegramDialog(
    settings: SuperagentSettings,
    onDismiss: () -> Unit,
    onSave: (SuperagentSettings) -> Unit,
) {
    val initial = settings.telegram
    var instance by remember { mutableStateOf(initial?.instance.orEmpty()) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    var allowList by remember { mutableStateOf(initial?.allowFrom?.joinToString(", ").orEmpty()) }
    var allowAll by remember { mutableStateOf(initial?.groupPolicy == TelegramGroupPolicy.AllowAll) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.superagent_telegram_edit)) },
        text = {
            Column {
                OutlinedTextField(instance, { instance = it }, label = { Text(stringResource(R.string.superagent_instance)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    token, { token = it },
                    label = { Text(stringResource(R.string.superagent_token)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    allowList, { allowList = it },
                    label = { Text(stringResource(R.string.superagent_allow_from)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                ListRow(null, stringResource(R.string.superagent_allow_all), stringResource(R.string.superagent_allow_all_sub)) { MikaSwitch(allowAll, { allowAll = it }) }
            }
        },
        confirmButton = {
            TextButton({
                val from = allowList.split(",").mapNotNull { item -> item.trim().takeIf { it.isNotEmpty() } }
                onSave(settings.copy(telegram = TelegramConfig(
                    instance = instance.trim(),
                    token = token.trim(),
                    allowFrom = from,
                    groupPolicy = if (allowAll) TelegramGroupPolicy.AllowAll else TelegramGroupPolicy.MentionOnly,
                )))
            }, enabled = instance.isNotBlank() && token.isNotBlank()) { Text(stringResource(R.string.mcp_servers_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
