package dev.local.sunwindowdemo.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.BuildConfig
import dev.local.sunwindowdemo.core.ui.*

/** A referenced or bundled project, how it is used here and under which license. */
private data class Notice(val name: String, val owner: String, val license: String, val usage: String, val url: String?)

private val references = listOf(
    Notice("HyperNavBar Rules", "Ianzb 与社区贡献者", "MIT",
        "导航栏应用与 Activity 默认模板，包含社区补充规则的转换数据",
        "https://github.com/HyperNavBar/HyperNavBarRules"),
    Notice("SunOS android_sun-sdk", "The Nameless-AOSP Project", "Apache-2.0",
        "小窗缩放常数、9:16 画布、手势阈值、操作条尺寸、转场时长与曲线，以及迷你窗菜单图标资源",
        "https://github.com/SunOS-Project/android_sun-sdk"),
    Notice("SunOS android_frameworks_base", "The Nameless-AOSP Project", "Apache-2.0",
        "长侧滑默认阈值、迷你窗生命周期判断、弹出窗口系统栏排除等行为参考",
        "https://github.com/SunOS-Project/android_frameworks_base_old"),
    Notice("Opticon", "IamCanincan", "MIT",
        "图标自适应视口缩放与非方形图标等比绘制思路参考，未复制源码与素材",
        "https://github.com/IamCanincan/Opticon"),
    Notice("完美图标计划", "社区图标项目", "素材权属归原作者",
        "仅兼容其 icons/<包名>/0.png、1.png 目录格式；模块不内置、不下载任何素材",
        null),
)

private val libraries = listOf(
    Notice("libxposed API", "libxposed", "Apache-2.0", "LSPosed 模块接口，仅编译期依赖", "https://github.com/libxposed/api"),
    Notice("Jetpack Compose", "The Android Open Source Project", "Apache-2.0", "界面框架（UI、Foundation、Material 3）", "https://developer.android.com/jetpack/compose"),
    Notice("AndroidX Activity / Lifecycle", "The Android Open Source Project", "Apache-2.0", "Activity 与生命周期集成", "https://developer.android.com/jetpack/androidx"),
    Notice("Material Icons / Symbols", "Google", "Apache-2.0", "界面图标与模块应用图标", "https://fonts.google.com/icons"),
)

@Composable
private fun NoticeGroup(title: String, items: List<Notice>) {
    val uri = LocalUriHandler.current
    GroupTitle(title)
    SettingsGroup {
        for (item in items) SettingRow(item.name, "${item.owner} · ${item.license}",
            item.url?.let { url -> { runCatching { uri.openUri(url) } } })
    }
}

@Composable
fun LicensesScreen() {
    SettingsGroup {
        SettingRow("类原生补全计划", "版本 ${BuildConfig.VERSION_NAME} · 本模块以 Apache-2.0 发布")
    }
    NoticeGroup("参考项目", references)
    NoticeGroup("依赖库", libraries)
}
