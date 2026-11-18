package dev.local.sunwindowdemo.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

internal val LocalAppQuery = compositionLocalOf { "" }

@Composable
internal fun AppQueryField(query: String, change: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(query, change, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
        placeholder = { Text("搜索应用名或包名") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
}
