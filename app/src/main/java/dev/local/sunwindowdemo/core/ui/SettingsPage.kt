package dev.local.sunwindowdemo.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** Scroll viewports reach the navigation bar; their final items consume the bottom inset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)? = null,
    expandedTitle: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (bottomInset: Dp) -> Unit,
) {
    val useLargeTitle = expandedTitle && LocalConfiguration.current.screenHeightDp >= 480
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val barActions: @Composable RowScope.() -> Unit = { actions() }
    val navigation: @Composable () -> Unit = {
        if (onBack != null) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
    }
    Scaffold(
        modifier = if (useLargeTitle) Modifier.nestedScroll(scroll.nestedScrollConnection) else Modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            .union(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
        topBar = {
            if (useLargeTitle) LargeTopAppBar(
                title = { Text(title, Modifier.testTag("page-title-$title")) }, actions = barActions, scrollBehavior = scroll,
                navigationIcon = navigation,
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            ) else TopAppBar(
                title = { Text(title, Modifier.testTag("page-title-$title"), maxLines = 1) },
                navigationIcon = navigation,
                actions = barActions,
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                content(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            }
        }
    }
}
