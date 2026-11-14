package dev.nativecomplements.navdemo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Standalone observer controls. Never changes application or navigation-bar rules. */
public final class DemoActivity extends Activity {
    static final Uri DATA = Uri.parse("content://dev.nativecomplements.navdemo.data");
    private static final int EXPORT = 1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("导航观察 Demo");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            body.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });
        scroll.requestApplyInsets();
        TextView explanation = new TextView(this);
        explanation.setText("导航算法观察 Demo 0.1.3\n仅采样导航区域上方的应用内容，不修改颜色、布局或 Insets。\n"
                + "先关闭正式模块的导航总开关；在 LSPosed 启用本 Demo，仅勾选系统框架（system），然后手动重启。\n"
                + "支持普通应用、竖屏、手势导航；无法判断时显示“等待”，按钮暂时禁用。\n"
                + "请手动授予悬浮窗权限。采样悬浮面板不属于被捕获的应用容器。\n"
                + "悬浮条只显示“延伸／悬浮”，判断正确点“肯定”，错误点“否定”。按住结果文字拖动。无需锁定，详细数据自动记入日志。\n悬浮表示本次不建议延伸的候选判断，不执行透明或布局修改。停止观察和导出在本页。\n"
                + "拿不准点“待定”，会保存当前应用画面（不含系统导航和悬浮条），请停留到保存提示。截图稍晚于采样，仅核对窗口和底部条带，不保证整页同帧。\n"
                + "算法v3先统计上方12–24dp近似颜色占比：≥75%初判延伸，否则悬浮；70%～80%或≥98%时单次向上补采最多320dp，比较底部与上方的边缘块排列。纯色不算结构相似，无结论保留初判，均为实验规则。\n"
                + "补采图只在内存分析，不自动保存，大区域采样最多每秒一次；相同窗口和底条短期复用复核结果，最多3秒后重查，不能保证识别同Activity的所有页面变化。“待定”仅供人工点击，不由算法自动显示。\n"
                + "ZIP包含日志和待定截图（最多100张）；可能含聊天/订单等隐私，仅本地保存、不上传。导出前确认分享对象，可清空记录及截图。停止观察会关闭采样配置。");
        body.addView(explanation);
        status = new TextView(this);
        body.addView(status);
        button(body, "授予悬浮窗权限", () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        button(body, "开始观察", this::startObservation);
        button(body, "停止观察", () -> {
            stopService(new Intent(this, DemoOverlay.class));
            worker.execute(() -> {
                try {
                    Bundle extras = new Bundle();
                    extras.putBoolean("enabled", false);
                    getContentResolver().call(DATA, "control", null, extras);
                    runOnUiThread(() -> status.setText("观察已停止"));
                } catch (Exception e) { report("停止失败", e); }
            });
        });
        button(body, "导出 ZIP（日志＋待定截图）", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "navbar-observations.zip");
            startActivityForResult(intent, EXPORT);
        });
        button(body, "清空记录", () -> new AlertDialog.Builder(this).setTitle("清空观察记录？")
                .setMessage("此操作无法撤销，请先导出需要保留的记录。")
                .setNegativeButton("取消", null).setPositiveButton("清空", (dialog, which) -> worker.execute(() -> {
                    try {
                        Bundle result = getContentResolver().call(DATA, "clear", null, null);
                        if (result == null || result.containsKey("error"))
                            throw new java.io.IOException(result == null ? "未收到清空确认" : result.getString("error"));
                        runOnUiThread(() -> Toast.makeText(this, "记录已清空", Toast.LENGTH_SHORT).show());
                    } catch (Exception e) { report("清空失败", e); }
                })).show());
    }

    private void startObservation() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先手动授予悬浮窗权限，再点击开始", Toast.LENGTH_LONG).show();
            return;
        }
        try { startForegroundService(new Intent(this, DemoOverlay.class)); }
        catch (Exception e) { report("启动失败", e); }
    }

    @Override protected void onResume() {
        super.onResume();
        worker.execute(() -> {
            try {
                Bundle config = getContentResolver().call(DATA, "config", null, null);
                boolean enabled = config != null && config.getBoolean("enabled", false);
                runOnUiThread(() -> status.setText("采样配置：" + (enabled ? "开启" : "关闭")
                        + "；悬浮窗权限：" + (Settings.canDrawOverlays(this) ? "已授予" : "未授予")));
            } catch (Exception e) { report("读取配置失败", e); }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri target = data.getData();
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                if (output == null) throw new java.io.IOException("无法打开导出文件");
                DemoStore.exportZip(this, output);
                runOnUiThread(() -> Toast.makeText(this, "导出完成", Toast.LENGTH_SHORT).show());
            } catch (Exception e) { report("导出失败（文件可能不完整）", e); }
        });
    }

    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
    }

    private void report(String message, Exception error) {
        runOnUiThread(() -> Toast.makeText(this, message + "：" + error.getMessage(), Toast.LENGTH_LONG).show());
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
