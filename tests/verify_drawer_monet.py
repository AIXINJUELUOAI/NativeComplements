"""Check drawer-only native themed binding against the archived launcher (no build/device)."""
import hashlib
from pathlib import Path
import zipfile
import verify_signal_target as dex

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "verification/pixel-qpr2-20261113/apk/NexusLauncherRelease.apk"
assert hashlib.sha256(APK.read_bytes()).hexdigest() == "109196f424d10102896b2b6a96ac284ec42012db12fdf923632d4a9a7c58159c"
PREFIX = "Lcom/android/launcher3/"
BUBBLE = PREFIX + "BubbleTextView;"
ADAPTER = PREFIX + "allapps/BaseAllAppsAdapter;"
BITMAP = PREFIX + "icons/BitmapInfo;"
ITEM = PREFIX + "model/data/ItemInfoWithIcon;"
SEARCH = PREFIX + "allapps/SearchRecyclerView;"
RECYCLER = PREFIX + "allapps/AllAppsRecyclerView;"
MONO = PREFIX + "icons/mono/MonoThemedBitmap;"
FACTORY = PREFIX + "graphics/theme/MonoIconThemeFactory;"
CONTROLLER = PREFIX + "icons/mono/MonoIconThemeController;"
ADAPTER_BASE = "Landroidx/recyclerview/widget/f1;"
RECYCLER_IMPL = "Landroidx/recyclerview/widget/a2;"
dex.TARGETS = [BUBBLE, ADAPTER, BITMAP, ITEM, SEARCH, RECYCLER, MONO, FACTORY, CONTROLLER, ADAPTER_BASE, RECYCLER_IMPL]
classes, code = {}, {}
with zipfile.ZipFile(APK) as archive:
    for name in archive.namelist():
        if name.startswith("classes") and name.endswith(".dex"):
            parsed = dex.Dex(archive.read(name))
            classes.update(parsed.classes)
            for key in parsed.code:
                code[key] = parsed.instructions(*key)
assert set(classes) == set(dex.TARGETS)
assert classes[SEARCH]["superclass"] == RECYCLER

def method(owner, name, signature):
    assert any(m["name"] == name and m["descriptor"] == signature for m in classes[owner]["methods"]), (owner, name, signature)
    return code[(owner, name, signature)]

def calls(instructions, owner, name):
    return any(i.get("method", {}).get("owner") == owner and i["method"]["name"] == name for i in instructions)

method(ADAPTER, "getIconOnCreateSetup", "(Landroid/view/ViewGroup;)" + BUBBLE)
# The Recycler owning field goes into v2; createViewHolder passes v2 as its parent.
recycle = method(RECYCLER_IMPL, "m", "(IJ)Landroidx/recyclerview/widget/l2;")
assert recycle[2]["field"]["type"] == "Landroidx/recyclerview/widget/RecyclerView;"
assert recycle[2]["units"][0] >> 8 & 15 == 2
creation = next(i for i in recycle if i.get("method", {}).get("name") == "createViewHolder")
assert creation["units"][2] >> 4 & 15 == 2
wrapper = method(ADAPTER_BASE, "createViewHolder", "(Landroid/view/ViewGroup;I)Landroidx/recyclerview/widget/l2;")
assert calls(wrapper, ADAPTER_BASE, "onCreateViewHolder")
create_holder = method(ADAPTER, "onCreateViewHolder", "(Landroid/view/ViewGroup;I)Landroidx/recyclerview/widget/l2;")
assert calls(create_holder, ADAPTER, "getIconOnCreateSetup")
bind = method(ADAPTER, "onBindViewHolder", "(Landroidx/recyclerview/widget/l2;I)V")
assert calls(bind, BUBBLE, "applyFromApplicationInfo")
apply = method(BUBBLE, "applyFromApplicationInfo", "(" + PREFIX + "model/data/AppInfo;)V")
assert calls(apply, BUBBLE, "applyIconAndLabel")
label = method(BUBBLE, "applyIconAndLabel", "(" + ITEM + ")V")
assert calls(label, BUBBLE, "setStandardIcon")
standard = method(BUBBLE, "setStandardIcon", "(" + ITEM + ")V")
assert calls(standard, ITEM, "newIcon")
item = method(ITEM, "newIcon", "(ILandroid/content/Context;)" + PREFIX + "icons/FastBitmapDrawable;")
assert calls(item, BITMAP, "newIcon")
assert any(i["opcode"] == "dd" and i["units"][1] >> 8 == 254 for i in item), "native item clears themed bit when global theme is off"
create = method(BITMAP, "newIcon", "(Landroid/content/Context;I" + PREFIX + "icons/IconShape;)" + PREFIX + "icons/FastBitmapDrawable;")
assert any(i["opcode"] == "dd" and i["units"][1] >> 8 == 1 for i in create), "native themed flag must be bit 1"
assert calls(create, PREFIX + "icons/ThemedBitmap;", "newDelegateFactory")
for name in ["themedBitmap", "delegateFactory"]:
    assert any(i.get("field", {}).get("name") == name for i in create), "missing material falls back natively"

method(MONO, "<init>", "(Landroid/graphics/Bitmap;Lpd/c;Ljava/lang/Double;)V")
assert any(f["name"] == "MONO_THEME_CONTROLLER" and f["type"] == CONTROLLER for f in classes[FACTORY]["fields"])
assert any(f["name"] == "colorProvider" and f["type"] == "Lpd/c;" for f in classes[CONTROLLER]["fields"])
method(BITMAP, "<init>", "(Landroid/graphics/Bitmap;II" + PREFIX + "icons/IconShape;" + PREFIX + "icons/ThemedBitmap;" + BITMAP + PREFIX + "icons/FastBitmapDrawableDelegate$DelegateFactory;" + PREFIX + "BadgeProvider;)V")
mono = method(CONTROLLER, "createThemedBitmap", "(Landroid/graphics/drawable/AdaptiveIconDrawable;" + BITMAP + PREFIX + "icons/BaseIconFactory;" + PREFIX + "icons/SourceHint;)" + PREFIX + "icons/ThemedBitmap;")
assert calls(mono, "Landroid/graphics/drawable/AdaptiveIconDrawable;", "getMonochrome")
assert calls(mono, "Landroid/graphics/drawable/InsetDrawable;", "<init>")
assert any(i["opcode"] == "7f" for i in mono), "native mono negates extra inset"

src = ROOT / "app/src/main/java/dev/local/sunwindowdemo"
drawer = (src / "features/iconlayers/DrawerMonet.java").read_text(encoding="utf-8")
store = (src / "features/shade/ShadeStore.java").read_text(encoding="utf-8")
provider = (src / "features/shade/ShadeProvider.java").read_text(encoding="utf-8")
assert 'getBoolean("drawer_monet", false)' in store
assert 'putBoolean("drawer_monet", enabled)' in store
assert 'IconLayerStore.LAUNCHER), null' in store and "setShareIdentityEnabled(true)" in store
assert '"drawer".equals(method)' in provider and 'getApplicationInfo(launcher, 0).uid' in provider
projection = provider.split('if ("drawer".equals(method)) {', 1)[1].split('return result;', 1)[0]
assert projection.count('result.put') == 1 and 'result.putBoolean("drawerMonet"' in projection
assert 'throw new SecurityException("drawer reader")' in projection
assert 'uid != getContext().getApplicationInfo().uid' in projection
assert 'if (!"read".equals(method)) throw new IllegalArgumentException' in provider
assert 'Map<View, java.lang.ref.WeakReference<View>> gridIcons = new WeakHashMap<>()' in drawer
assert 'gridIcons.get(view)' in drawer and 'searchRecycler.isInstance(parent)' in drawer
assert 'view.getParent() == null' in drawer and '!searchRecycler.isInstance(createdFor)' in drawer
assert 'appInfo.isInstance(chain.getArg(0))' in drawer and '&& isDrawer((View) chain.getThisObject())' in drawer
assert 'finally { if (previous == null) binding.remove(); else binding.set(previous); }' in drawer
assert 'if (!enabled || !Boolean.TRUE.equals(binding.get())) return chain.proceed();' in drawer
assert 'args[1] = ((Integer) args[1]) | 1;' in drawer
assert 'copies.size() >= 128' in drawer and 'generation != token' in drawer
assert 'bitmap == key.bitmap && item == key.item' in drawer
assert 'Boolean.TRUE.equals(lowRes.invoke(original))' in drawer
module = (src / 'features/iconlayers/IconLayerModule.java').read_text(encoding='utf-8')
assert module.index('drawerMonet.artworkChanged();', module.index('if (!IconAssets.load')) > module.index('if (!IconAssets.load')
assert 'Intent.ACTION_MANAGED_PROFILE_AVAILABLE' in module and 'Intent.ACTION_USER_UNLOCKED' in module
assert 'read.live() && read.config() != null' in drawer, "failed read must restore native, not stale enable"
assert 'main.post(() -> {' in drawer and 'standard.invoke(view, view.getTag())' in drawer
assert 'IconLayerStore.CONTROL, null, Context.RECEIVER_EXPORTED' in drawer
assert 'getDeclaredMethod("onBindViewHolder"' not in drawer, "avoid obfuscated bridge/overload hook"
for page in ["home/HomeScreen.kt", "navigation/ModuleApp.kt"]:
    assert '"外观与控制"' in (src / page).read_text(encoding="utf-8")
print("PASS: exact launcher binding/themed fallback signatures and drawer setting/security/lifecycle guards; live visuals unverified")
