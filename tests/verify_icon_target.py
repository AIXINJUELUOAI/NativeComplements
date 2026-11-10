"""Verify icon Hook reflection members against the archived target's DEX symbols."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
inventory = json.loads((root / "tests/target/icon-members.json").read_text(encoding="utf-8"))
assert inventory["artifacts"][0]["sha256"] == "a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972"
base = "Lcom/android/wm/shell/startingsurface/SplashscreenContentDrawer"
checks = [
    ("Lcom/android/systemui/statusbar/StatusBarIconView;", "methods", "getIcon", "(Lcom/android/internal/statusbar/StatusBarIcon;)Landroid/graphics/drawable/Drawable;"),
    ("Lcom/android/systemui/statusbar/StatusBarIconView;", "methods", "updateDrawable", "(Z)Z"),
    ("Lcom/android/systemui/statusbar/StatusBarIconView;", "fields", "mNotification", "Landroid/service/notification/StatusBarNotification;"),
    ("Lcom/android/systemui/statusbar/StatusBarIconView;", "fields", "mShowsConversation", "Z"),
    ("Lcom/android/internal/statusbar/StatusBarIcon;", "fields", "icon", "Landroid/graphics/drawable/Icon;"),
    ("Lcom/android/systemui/statusbar/ScalingDrawableWrapper;", "methods", "<init>", "(Landroid/graphics/drawable/Drawable;F)V"),
    (base + "$SplashViewBuilder;", "methods", "build", "()Landroid/window/SplashScreenView;"),
    (base + "$SplashViewBuilder;", "fields", "mActivityInfo", "Landroid/content/pm/ActivityInfo;"),
    (base + "$SplashViewBuilder;", "fields", "mSuggestType", "I"),
    (base + "$SplashViewBuilder;", "fields", "this$0", base + ";"),
    (base + ";", "fields", "mTmpAttrs", base + "$SplashScreenWindowAttrs;"),
    (base + "$SplashScreenWindowAttrs;", "fields", "mSplashScreenIcon", "Landroid/graphics/drawable/Drawable;"),
    (base + "$SplashScreenWindowAttrs;", "fields", "mIconBgColor", "I"),
]
for cls, kind, name, signature in checks:
    key = "type" if kind == "fields" else "descriptor"
    assert any(m["name"] == name and m[key] == signature for m in inventory["classes"][cls][kind]), (cls, name, signature)
print(f"PASS: {len(checks)} exact icon Hook target members; live rendering remains unverified")
