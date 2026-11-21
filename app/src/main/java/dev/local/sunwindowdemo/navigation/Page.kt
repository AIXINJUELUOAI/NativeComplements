package dev.local.sunwindowdemo.navigation

enum class Page(val title: String) {
    Home("类原生补全计划"), Window("小窗设置"), Shade("外观与控制"), GameMode("游戏模式"), GameQuick("快捷应用与功能"), GameCorner("角落防误触"), Barrage("弹幕通知"), BarrageHistory("弹幕记录"), Suggestions("快捷建议"),
    IconLayers("图标适配"), IconApp("图标设置"), Alive("Alive 壁纸"), Motion("加速度传感器控制"), EdgeTouch("边缘防误触"), Calls("来电拦截"), Records("拦截记录"), Sms("短信拦截"), SmsRecords("短信记录"),
    Gestures("小窗入口与菜单"), Behavior("窗口与多任务"), Prevention("小窗触发限制"), Blocked("禁用应用"),
    About("关于"), Logs("诊断日志"), Licenses("开源许可"), Tools("开发者工具"), Diagnostics("小窗诊断与恢复"), IconDiagnostics("图标实验与诊断"),
    Harassment("骚扰拦截"),
    SuggestionApps("链接与分享口令"),
    BackgroundWakeLocks("后台唤醒锁限制"),
    Background("墓碑"), BackgroundApp("进程设置"),
}
