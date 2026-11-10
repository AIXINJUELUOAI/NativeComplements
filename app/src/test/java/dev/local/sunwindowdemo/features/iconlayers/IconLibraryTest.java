package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IconLibraryTest {
    private Context context;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final IconArchive.Progress progress = (text, done, total) -> {};
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        IconLayerStore.prefs(context).edit().clear().commit();
        IconArchive.delete(IconLibrary.root(context));
    }
    @After public void cleanup() { IconArchive.delete(IconLibrary.root(context)); }

    @Test public void wrappedRepositoryKeepsBaseAndThemesSeparate() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        add(entries, "project-main/icons/", "com.example.one", Color.RED);
        add(entries, "project-main/themes/blue/icons/", "com.example.one", Color.BLUE);
        try (IconArchive archive = archive(entries)) {
            assertEquals(2, archive.sources.size());
            assertEquals(1, archive.sources.get(0).completeCount());
            var result = IconLibrary.install(context, archive, archive.sources.get(1), cancel, progress);
            assertEquals(1, result.imported);
            Bundle state = IconLayerStore.read(context);
            assertTrue(state.getBoolean("enabled"));
            String generation = state.getString("generation");
            File file = new File(IconLibrary.generation(context, generation), "com.example.one/0.png");
            Bitmap stored = BitmapFactory.decodeFile(file.getPath());
            assertEquals(Color.BLUE, stored.getPixel(16, 16));
            assertEquals(32, stored.getWidth()); // Canvas is never cropped or padded again.
            assertEquals(List.of("com.example.one"), IconLibrary.packages(context, generation));
        }
    }
    @Test public void missingOrCorruptPairsAreCountedAndGoodPairsSurvive() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        add(entries, "icons/", "com.example.good", Color.GREEN);
        entries.put("icons/com.example.missing/0.png", png(Color.RED));
        entries.put("icons/com.example.corrupt/0.png", new byte[]{1,2,3});
        entries.put("icons/com.example.corrupt/1.png", png(Color.BLUE));
        try (IconArchive archive = archive(entries)) {
            var result = IconLibrary.install(context, archive, archive.sources.get(0), cancel, progress);
            assertEquals(1, result.imported); assertEquals(1, result.skipped); assertEquals(1, result.failed);
        }
    }
    @Test public void differentLayerResolutionsAreImportedWithoutCroppingOrPadding() throws Exception {
        Bitmap foreground = Bitmap.createBitmap(63,64,Bitmap.Config.ARGB_8888);
        foreground.setPixel(62,63,Color.BLUE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); foreground.compress(Bitmap.CompressFormat.PNG,100,bytes); foreground.recycle();
        try (IconArchive archive = archive(Map.of("icons/com.example.one/0.png", png(Color.RED), "icons/com.example.one/1.png", bytes.toByteArray()))) {
            assertEquals(1, IconLibrary.install(context, archive, archive.sources.get(0), cancel, progress).imported);
            File folder = new File(IconLibrary.generation(context, IconLayerStore.read(context).getString("generation")), "com.example.one");
            Bitmap stored = BitmapFactory.decodeFile(new File(folder, "1.png").getPath());
            assertEquals(63, stored.getWidth()); assertEquals(64, stored.getHeight());
            assertEquals(Color.BLUE, stored.getPixel(62,63)); assertEquals(0,Color.alpha(stored.getPixel(0,0)));
        }
    }
    @Test public void failedAndCancelledImportsPreserveActiveGeneration() throws Exception {
        installOne(); String original = IconLayerStore.read(context).getString("generation");
        try (IconArchive archive = archive(Map.of("com.example.bad/0.png", png(Color.BLACK)))) {
            assertThrows(IOException.class, () -> IconLibrary.install(context, archive, archive.sources.get(0), cancel, progress));
        }
        assertEquals(original, IconLayerStore.read(context).getString("generation"));
        Map<String, byte[]> entries = new LinkedHashMap<>(); add(entries, "icons/", "com.example.new", Color.BLUE);
        try (IconArchive archive = archive(entries)) {
            assertThrows(CancellationException.class, () -> IconLibrary.install(context, archive, archive.sources.get(0), cancel,
                (text, done, total) -> cancel.set(true)));
        }
        assertEquals(original, IconLayerStore.read(context).getString("generation"));
        assertEquals(1, Objects.requireNonNull(IconLibrary.root(context).listFiles()).length);
    }
    @Test public void pathTraversalAndAbsolutePathsAreRejected() throws Exception {
        for (String path : List.of("../com.example.bad/0.png", "/com.example.bad/0.png", "icons/../../escape", "C:/escape", "icons\\escape"))
            assertThrows(IOException.class, () -> archive(Map.of(path, png(Color.RED))));
        assertFalse(IconArchive.validPackage("../escape"));
        assertFalse(IconLibrary.validGeneration("g_../../escape"));
    }
    @Test public void nestedModuleContainerReadsOnlyItsIconArchive() throws Exception {
        Map<String, byte[]> inner = new LinkedHashMap<>(); add(inner, "res/drawable-xxhdpi/", "com.example.one", Color.CYAN);
        try (IconArchive archive = archive(Map.of("system/media/theme/default/icons", zip(inner), "customize.sh", "DO NOT EXECUTE".getBytes()))) {
            assertEquals(1, archive.sources.size());
            assertEquals(1, IconLibrary.install(context, archive, archive.sources.get(0), cancel, progress).imported);
        }
    }
    @Test public void originalOverrideAndRemoveKeepRestorationIndexUntilAcknowledged() throws Exception {
        installOne(); Bundle state = IconLayerStore.read(context); String generation = state.getString("generation");
        IconLayerStore.original(context, "com.example.one", true);
        assertTrue(IconLayerStore.read(context).getStringArrayList("originals").contains("com.example.one"));
        IconLayerStore.original(context, "com.example.one", false);
        assertTrue(IconLayerStore.read(context).getStringArrayList("originals").isEmpty());
        IconLibrary.remove(context);
        assertFalse(IconLayerStore.read(context).getBoolean("enabled"));
        assertTrue(IconLayerStore.read(context).getStringArrayList("restore").contains("com.example.one"));
        assertTrue(IconLibrary.generation(context, generation).exists());
        IconLibrary.acknowledge(context, "old-generation");
        assertTrue(IconLibrary.generation(context, generation).exists());
        IconLibrary.acknowledge(context, "");
        assertFalse(IconLibrary.generation(context, generation).exists());
    }
    @Test public void importedDrawableCopiesRetainIndependentLayersAndCanvas() {
        Bitmap bg = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); bg.eraseColor(Color.RED);
        Bitmap fg = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); fg.setPixel(16, 16, Color.BLUE);
        ImportedIcon icon = new ImportedIcon(context.getResources(), bg, fg);
        ImportedIcon copy = (ImportedIcon) icon.getConstantState().newDrawable();
        icon.setBounds(0,0,120,120); Rect before = new Rect(icon.getForeground().getBounds());
        copy.setBounds(0,0,60,60); copy.getForeground().setAlpha(0);
        assertNotSame(icon.getForeground(), copy.getForeground());
        assertNotSame(icon.getBackground(), copy.getBackground());
        assertEquals(255, icon.getForeground().getAlpha());
        assertEquals(before, icon.getForeground().getBounds());
        assertEquals(0, Color.alpha(fg.getPixel(0,0)));
        assertEquals(Color.BLUE, fg.getPixel(16,16));
    }
    @Test public void providerAllowsOnlyIndexedPngReads() throws Exception {
        installOne(); String generation = IconLayerStore.read(context).getString("generation");
        IconLayerProvider provider = Robolectric.buildContentProvider(IconLayerProvider.class).create().get();
        String prefix = "content://dev.local.sunwindowdemo.iconlayers/layers/" + generation;
        try (var fd = provider.openFile(Uri.parse(prefix + "/com.example.one/0.png"), "r")) { assertTrue(fd.getStatSize() > 0); }
        assertThrows(SecurityException.class, () -> provider.openFile(Uri.parse(prefix + "/com.example.one/0.png"), "w"));
        assertThrows(FileNotFoundException.class, () -> provider.openFile(Uri.parse(prefix + "/com.example.one/index.json"), "r"));
        assertThrows(FileNotFoundException.class, () -> provider.openFile(Uri.parse(prefix + "/com.example.absent/0.png"), "r"));
        assertThrows(FileNotFoundException.class, () -> provider.openFile(Uri.parse(prefix + "/../0.png"), "r"));
    }
    private void installOne() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>(); add(entries, "icons/", "com.example.one", Color.RED);
        try (IconArchive archive = archive(entries)) { IconLibrary.install(context, archive, archive.sources.get(0), cancel, progress); }
    }
    private IconArchive archive(Map<String, byte[]> entries) throws Exception {
        File directory = new File(context.getCacheDir(), "test-icons-" + UUID.randomUUID());
        assertTrue(directory.mkdirs()); Files.write(new File(directory, "source.zip").toPath(), zip(entries));
        return new IconArchive(directory, cancel);
    }
    private byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private void add(Map<String, byte[]> entries, String prefix, String pkg, int color) {
        entries.put(prefix + pkg + "/0.png", png(color)); entries.put(prefix + pkg + "/1.png", png(Color.TRANSPARENT));
    }
    private byte[] png(int color) {
        Bitmap bitmap = Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888); bitmap.eraseColor(color);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); return out.toByteArray();
    }
}
