package dev.local.sunwindowdemo.features.barrage

import android.content.SharedPreferences
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.*
import java.text.DateFormat
import java.util.Date

@Composable
fun BarrageHistoryScreen(apps: List<LaunchableApp>, bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    var history by remember { mutableStateOf<List<Bundle>>(BarrageStore.readHistory(context)) }
    DisposableEffect(context) {
        val prefs = BarrageStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            history = BarrageStore.readHistory(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 680.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (history.isEmpty()) {
                item { Hint("暂无弹幕记录") }
            }
            items(history) { entry ->
                val pkg = entry.getString("package").orEmpty()
                val app = apps.firstOrNull { it.packageName == pkg }
                val appLabel = entry.getString("app").orEmpty().ifBlank { app?.label ?: pkg }
                val source = entry.getString("source").orEmpty()
                val text = entry.getString("text").orEmpty()
                val time = entry.getLong("time", 0)
                val title = source.ifBlank { appLabel }
                val subtitle = buildString {
                    append(appLabel)
                    if (time > 0) append(" · ").append(DateFormat.getDateTimeInstance().format(Date(time)))
                    if (text.isNotBlank()) append(" · ").append(text)
                }
                SettingRow(title, subtitle, leading = { BarrageAppIcon(app) })
            }
            if (history.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { BarrageStore.clearHistory(context); history = emptyList() }) {
                            Text("清空记录")
                        }
                    }
                }
            }
        }
    }
}
