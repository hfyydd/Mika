package com.wangzan101.mika.ui

import java.io.File
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.core.superagent.SuperagentSettings
import com.wangzan101.mika.core.superagent.FileSuperagentSettingsStore
import com.wangzan101.mika.superagent.SuperagentRuntimeService
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
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
    var applying by remember { mutableStateOf(false) }
    val loaded by remember { derivedStateOf { settings != SuperagentSettings() || applying } }
    LaunchedEffect(Unit) {
        settings = withContext(Dispatchers.IO) { store.load() }
    }
    PageScaffold(stringResource(R.string.superagent_title), onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Spark,
                stringResource(R.string.superagent_enabled),
                stringResource(R.string.superagent_enabled_sub),
                first = true,
            ) { MikaSwitch(settings.enabled, { on ->
                if (applying) return@MikaSwitch
                applying = true
                val next = settings.copy(enabled = on)
                settings = next
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.save(next) }
                        app.superHost.setEnabled(on)
                        if (on) SuperagentRuntimeService.start(context) else SuperagentRuntimeService.stop(context)
                    } finally {
                        applying = false
                    }
                }
            }, enabled = loaded) }
        }
        FooterNote(stringResource(R.string.superagent_note))
    }
}
