package dev.local.sunwindowdemo;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.app.BroadcastOptions;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** Optional system accessibility button only; the existing SystemUI host still owns every menu/window. */
public final class WindowAccessibilityService extends AccessibilityService {
    private final AccessibilityButtonController.AccessibilityButtonCallback callback=
        new AccessibilityButtonController.AccessibilityButtonCallback(){
            @Override public void onClicked(AccessibilityButtonController controller){
                if(!new Config(WindowAccessibilityService.this).enabled())return;
                Intent show=new Intent(Wire.SHOW_MENU).setPackage(BackBridge.PACKAGE).putExtra("protocol",Wire.PROTOCOL);
                sendBroadcast(show,null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            }
        };
    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        getAccessibilityButtonController().registerAccessibilityButtonCallback(callback);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {} // No event types or content requested.
    @Override public void onInterrupt() {}
    @Override public void onDestroy(){
        getAccessibilityButtonController().unregisterAccessibilityButtonCallback(callback);
        super.onDestroy();
    }
}
