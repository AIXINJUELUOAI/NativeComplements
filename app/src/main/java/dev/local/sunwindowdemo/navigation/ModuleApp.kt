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
    IconLayers("图标补全"),
    Motion("防摇一摇"), Suggestions("建议"), EdgeTouch("边缘防误触"), Calls("电话拦截"), Home("类原生补全计划"), Window("小窗"), Apps("快捷应用"), Gestures("手势与菜单"),
    Behavior("窗口与多任务"), Prevention("小窗触发限制"), Blocked("禁用应用"), Diagnostics("诊断与恢复"), About("关于"), Licenses("开源许可")
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
        if (page != Page.Home && page != Page.Calls && page != Page.EdgeTouch) TopAppBar(title = {}, navigationIcon = {
            IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
    }) { insets ->
        AnimatedContent(targetState = page, modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), label = "settings-page",
            transitionSpec = {
                val direction = if (backward) -1 else 1
                (slideInHorizontally(spring(stiffness = 600f)) { it / 8 * direction } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 12 * direction } + fadeOut())
            }) { current ->
            if (current == Page.EdgeTouch) {
                dev.local.sunwindowdemo.features.edgetouch.EdgeTouchScreen(onBack = { back() })
            } else if (current == Page.Calls) {
                dev.local.sunwindowdemo.features.callblocking.CallBlockingScreen(onBack = { back() })
            } else if (current == Page.Motion) {
                dev.local.sunwindowdemo.features.motion.MotionScreen(controller.apps, controller.catalogLoaded)
            } else if (current == Page.IconLayers) {
                dev.local.sunwindowdemo.features.iconlayers.IconLayerScreen(controller.apps)
            } else if (current == Page.Apps || current == Page.Blocked) {
                AppPickerScreen(controller, blocked = current == Page.Blocked)
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                        Text(current.title, Modifier.padding(start = 12.dp, top = if(current == Page.Home) 42.dp else 4.dp, bottom = 28.dp),
                            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Normal)
                        when (current) {
                            Page.Home -> HomeScreen(controller.runtime, { navigate(Page.Window) }, { navigate(Page.Diagnostics) }, { aboutVisible = true }, { navigate(Page.Calls) }, { navigate(Page.EdgeTouch) }, { navigate(Page.Suggestions) }, { navigate(Page.Motion) }, { navigate(Page.IconLayers) }, { navigate(Page.Licenses) })
                            Page.Window -> WindowScreen(controller.preferences, controller.runtime, { navigate(it) }, controller::toggleControl)
                            Page.Suggestions -> dev.local.sunwindowdemo.features.suggestions.SuggestionScreen(controller)
                            Page.Gestures -> GestureScreen(controller.preferences, controller::boolean, controller::number)
                            Page.Behavior -> BehaviorScreen(controller.preferences, controller::boolean)
                            Page.Prevention -> PreventionScreen(controller.preferences, controller::boolean, { navigate(Page.Blocked) })
                            Page.Diagnostics -> DiagnosticsScreen(controller)
                            Page.Licenses -> dev.local.sunwindowdemo.home.LicensesScreen()
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
    if (aboutVisible) AlertDialog(onDismissRequest = { aboutVisible = false }, title = { Text("类原生补全计划") },
        text = { Text("${BuildConfig.VERSION_NAME}\n\n小窗基于 Demo8.2，保留原有手势、窗口和动画。支持普通窗与迷你窗并存，由 LSPosed / SystemUI 承载，无定时退出。\n\n窗口几何参考 Nameless / SunOS，遵循 Apache 2.0。") },
        confirmButton = { TextButton(onClick = { aboutVisible = false }) { Text("知道了") } })
}
