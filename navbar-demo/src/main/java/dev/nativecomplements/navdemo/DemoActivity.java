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
        explanation.setText("导航算法观察 Demo 0.1.7\n只观察和记录，不修改颜色、布局或 Insets。\n"
                + "使用前关闭正式模块的导航总开关；LSPosed 中只勾选系统框架（system），然后重启。\n"
                + "悬浮条显示“延伸／悬浮”：判断对点“肯定”，错点“否定”，拿不准点“待定”。按住结果文字可拖动。\n"
                + "每次标注都会保存算法实际分析的那块画面（导航上方约320dp＋导航区域，不含悬浮条），请停留到“已记录”提示。\n"
                + "算法v6：应用自己在导航区域画了内容→悬浮；等距的底部导航栏→延伸（半透明模糊的除外）；底部是空白或固定底栏→延伸；内容被底边截断或与上方重复→悬浮。\n"
                + "ZIP 含日志和标注截图（最多400张），可能含聊天、订单等隐私，只存在本机。");
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
        button(body, "导出 ZIP（日志＋标注截图）", () -> {
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
