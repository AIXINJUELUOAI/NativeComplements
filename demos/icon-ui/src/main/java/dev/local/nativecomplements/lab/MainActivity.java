package dev.local.nativecomplements.lab;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import java.nio.charset.StandardCharsets;

/** Local HTML UI prototype. No remote pages, JavaScript/native bridge or device controls. */
public final class MainActivity extends Activity {
    private WebView web;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFFF7F8FC);
        getWindow().setNavigationBarColor(0xFFF7F8FC);
        getWindow().setDecorFitsSystemWindows(false);
        try {
            String html;
            try (var input = getAssets().open("index.html")) { html = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
            web = new WebView(this);
            web.setBackgroundColor(0xFFF7F8FC);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.getSettings().setBlockNetworkLoads(true);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            });
            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(0xFFF7F8FC);
            root.addView(web, new FrameLayout.LayoutParams(-1, -1));
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            setContentView(root);
            getWindow().getInsetsController().setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            web.loadDataWithBaseURL("https://lab.nativecomplements.local/", html, "text/html", "UTF-8", null);
        } catch (Exception failure) {
            TextView message = new TextView(this); message.setText("预览加载失败，请重新打开 Demo。"); setContentView(message);
        }
    }
    @Override public void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
