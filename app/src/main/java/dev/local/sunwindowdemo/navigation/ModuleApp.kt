package dev.local.sunwindowdemo.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.*
import dev.local.sunwindowdemo.core.ui.*
import dev.local.sunwindowdemo.home.HomeScreen
import dev.local.sunwindowdemo.features.floatingwindow.ui.*

enum class Page(val title: String) {
    Home("类原生补全"), Window("小窗"), Apps("快捷应用"), Gestures("手势与菜单"),
    Behavior("窗口与多任务"), Prevention("防误触"), Blocked("禁用应用"), Diagnostics("诊断与恢复"), About("关于")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleApp(controller: WindowController, startInWindow: Boolean, onExit: () -> Unit) {
    var backStack by rememberSaveable { mutableStateOf(listOf(if (startInWindow) Page.Window.name else Page.Home.name)) }
    val page = Page.valueOf(backStack.last())
    var backward by remember { mutableStateOf(false) }
    fun navigate(target: Page) { backward = false; if (page != target) backStack = backStack + target.name }
    fun back() {
        backward = true
        if (backStack.size == 1) onExit() else backStack = backStack.dropLast(1)
    }
    BackHandler(page != Page.Home) { back() }
    var aboutVisible by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, topBar = {
        if (page != Page.Home) TopAppBar(title = {}, navigationIcon = {
            IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
    }) { insets ->
        AnimatedContent(targetState = page, modifier = Modifier.fillMaxSize().padding(insets), label = "settings-page",
            transitionSpec = {
                val direction = if (backward) -1 else 1
                (slideInHorizontally(spring(stiffness = 600f)) { it / 8 * direction } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 12 * direction } + fadeOut())
            }) { current ->
            if (current == Page.Apps || current == Page.Blocked) {
                AppPickerScreen(controller, blocked = current == Page.Blocked)
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                        Text(current.title, Modifier.padding(start = 12.dp, top = if(current == Page.Home) 42.dp else 4.dp, bottom = 28.dp),
                            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Normal)
                        when (current) {
                            Page.Home -> HomeScreen(controller.runtime, { navigate(Page.Window) }, { navigate(Page.Diagnostics) }, { aboutVisible = true })
                            Page.Window -> WindowScreen(controller.preferences, controller.runtime, { navigate(it) }, controller::accessibility)
                            Page.Gestures -> GestureScreen(controller.preferences, controller::boolean, controller::number)
                            Page.Behavior -> BehaviorScreen(controller.preferences, controller::boolean)
                            Page.Prevention -> PreventionScreen(controller.preferences, controller::boolean, { navigate(Page.Blocked) })
                            Page.Diagnostics -> DiagnosticsScreen(controller)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
    if (aboutVisible) AlertDialog(onDismissRequest = { aboutVisible = false }, title = { Text("类原生补全") },
        text = { Text("${BuildConfig.VERSION_NAME}\n\n小窗基于 Demo8.2，保留原有手势、窗口和动画。当前为单活动小窗，每轮最多三分钟。\n\n窗口几何参考 Nameless / SunOS，遵循 Apache 2.0。") },
        confirmButton = { TextButton(onClick = { aboutVisible = false }) { Text("知道了") } })
}
