package dev.local.sunwindowdemo.features.gamemode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.AppCatalog
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.Hint
import dev.local.sunwindowdemo.core.ui.LocalAppQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val alphabet = ('A'..'Z').map(Char::toString) + "#"

/** The index rail appears only while the user scrolls with the first letter group at or above the top. */
internal fun indexRailVisible(firstVisibleItem: Int, firstSectionItem: Int?, scrolling: Boolean, railDragging: Boolean) =
    railDragging || scrolling && firstSectionItem != null && firstVisibleItem >= firstSectionItem

@Composable
fun AppListPicker(
    apps: List<LaunchableApp>, title: String, bottomInset: Dp,
    emptyText: String = "暂无可显示的应用", modifier: Modifier = Modifier,
    loaded: Boolean = true, header: @Composable () -> Unit = {}, footer: @Composable () -> Unit = {},
    row: @Composable (LaunchableApp) -> Unit,
) {
    val query = LocalAppQuery.current.trim()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    val sections by produceState<Map<String, String>?>(null, apps) {
        value = withContext(Dispatchers.Default) { apps.associate { it.packageName to AppCatalog.sectionForLabel(it.label) } }
    }
    val indexed = remember(apps, query, sections) {
        if (sections == null) emptyMap() else apps.indices.filter { query.isBlank() || apps[it].label.contains(query, true) || apps[it].packageName.contains(query, true) }
            .groupBy { sections!![apps[it].packageName] ?: "#" }.toSortedMap(compareBy { if (it == "#") "{" else it })
    }
    val targets = remember(indexed) {
        var offset = 1
        indexed.mapValues { (_, indices) -> offset.also { offset += 1 + indices.size } }
    }
    val firstSection = targets.values.minOrNull()
    var railDragging by remember { mutableStateOf(false) }
    val wanted by remember(firstSection) {
        derivedStateOf { indexRailVisible(state.firstVisibleItemIndex, firstSection, state.isScrollInProgress, railDragging) }
    }
    var showRail by remember { mutableStateOf(false) }
    LaunchedEffect(wanted) {
        if (wanted) showRail = true
        else if (showRail) { delay(1000); showRail = false }
    }
    fun jump(letter: String) {
        val target = targets[letter] ?: return
        scrollJob?.cancel()
        scrollJob = scope.launch { state.scrollToItem(target) }
    }
    LaunchedEffect(query) { state.scrollToItem(0) }
    Box(modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "header") {
                Column {
                    header()
                    GroupTitle(title.ifBlank { "应用列表" })
                    if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (loaded && indexed.isEmpty()) Hint(if (query.isEmpty()) emptyText else "没有匹配的应用")
                }
            }
            indexed.forEach { (section, indices) ->
                item(key = "section:$section") {
                    Text(section, Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(indices, key = { "app:${apps[it].packageName}" }) { index -> row(apps[index]) }
            }
            item(key = "footer") { footer() }
        }
        AnimatedVisibility(showRail, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 8.dp, bottom = 8.dp + bottomInset),
            enter = fadeIn(), exit = fadeOut()) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 2.dp) {
                Column(Modifier.width(28.dp).fillMaxHeight().padding(vertical = 6.dp)
                    .pointerInput(targets) {
                        fun jumpAt(y: Float) {
                            if (size.height > 0) jump(alphabet[(y / size.height * alphabet.size).toInt().coerceIn(alphabet.indices)])
                        }
                        detectVerticalDragGestures(
                            onDragStart = { railDragging = true; jumpAt(it.y) },
                            onDragEnd = { railDragging = false },
                            onDragCancel = { railDragging = false },
                        ) { change, _ ->
                            change.consume()
                            jumpAt(change.position.y)
                        }
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                    alphabet.forEach { letter ->
                        Box(Modifier.weight(1f).fillMaxWidth().clickable(enabled = letter in targets) { jump(letter) }
                            .semantics { contentDescription = "跳转到$letter" }, contentAlignment = Alignment.Center) {
                            Text(letter, style = MaterialTheme.typography.labelSmall,
                                color = if (letter !in targets) MaterialTheme.colorScheme.onSurface.copy(alpha = .35f) else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
