package dev.local.sunwindowdemo

import android.app.Notification
import android.app.Person
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.WindowManager
import dev.local.sunwindowdemo.features.barrage.BarrageStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class BarrageHostTest {
    class Listener : NotificationListenerService()

    private class Provider : ContentProvider() {
        val history = mutableListOf<Bundle>()
        var lines = 3
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
            "read" -> Bundle().apply {
                putBoolean("enabled", true)
                putInt("lines",lines)
                putStringArrayList("apps", arrayListOf("example.game"))
            }
            "addHistory" -> {
                history += Bundle(requireNotNull(extras))
                Bundle().apply { putBoolean("saved", true) }
            }
            else -> error(method)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private lateinit var context: Context
    private lateinit var host: BarrageHost
    private lateinit var listener: Listener
    private lateinit var provider: Provider
    private lateinit var worker: ExecutorService

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        context = RuntimeEnvironment.getApplication()
        provider = Provider()
        provider.attachInfo(context, ProviderInfo().apply { authority = BarrageStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(BarrageStore.AUTHORITY, provider)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        listener = Robolectric.buildService(Listener::class.java).create().get()
        host = BarrageHost(context)
        worker = ReflectionHelpers.getField(host, "worker")
        drain()
        host.environment("example.game", false)
    }

    @After fun close() { host.close(); ShadowChoreographer.setPaused(false) }

    private fun drain() {
        // Advance a frame to clear addView's traversal barrier before history callbacks.
        repeat(2) {
            worker.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS)
        }
    }

    private fun entries(): Map<Int, Any> = ReflectionHelpers.getField(host, "playing")
    private fun view(entry: Any): BarrageLyricsView = ReflectionHelpers.getField(entry, "view")
    private fun text(entry: Any): String = ReflectionHelpers.getField(view(entry), "text")
    private fun texts() = entries().values.map(::text)
    private fun finish(lane: Int) {
        ReflectionHelpers.getField<Runnable>(entries().getValue(lane), "expire").run()
    }

    private fun notification(text: String, time: Long, pkg: String = "example.qq", id: Int = 1): StatusBarNotification {
        val n = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "QQ")
            extras.putString(Notification.EXTRA_TEXT, text)
        }
        return StatusBarNotification(pkg, pkg, id, null, 12345, 0, 0, n, Process.myUserHandle(), time)
    }

    private fun post(sbn: StatusBarNotification) {
        // The native notification tray keeps one version for a reused key.
        listener.cancelNotification(sbn.key)
        shadowOf(listener).addActiveNotification(sbn)
        host.notification(listener, sbn)
    }

    @Test fun sameNotificationUpdatesKeepThreeIndependentLinesAndQueueTheFourth() {
        post(notification("测试1", 1))
        val first = entries().getValue(0)
        post(notification("（两条消息）测试2", 2))
        post(notification("测试3", 3))
        post(notification("测试4", 4))
        assertSame(first, entries().getValue(0))
        assertEquals(listOf("QQ：测试1", "QQ：测试2", "QQ：测试3"), texts())
        val positions = entries().values.map { view(it).layoutParams as WindowManager.LayoutParams }
        assertTrue(positions[0].y + positions[0].height <= positions[1].y)
        assertTrue(positions[1].y + positions[1].height <= positions[2].y)
        val second = entries().getValue(1)
        finish(0)
        assertEquals("QQ：测试4", text(entries().getValue(0)))
        assertSame(second, entries().getValue(1))
        drain()
        assertEquals(4, provider.history.size)
    }

    @Test fun anotherApplicationDoesNotReplaceTheCurrentLineAndDuplicatesDoNotRestartIt() {
        val first = notification("测试1", 1)
        post(first)
        val original = entries().getValue(0)
        post(notification("另一个应用", 2, "example.other"))
        post(first)
        assertEquals(2, entries().size)
        assertSame(original, entries().getValue(0))
        drain()
        assertEquals(2, provider.history.size)
    }

    @Test fun lineLimitChangesToFiveAndUnlimitedWithoutRemovingCurrentMessages() {
        repeat(7) { post(notification("消息$it",it.toLong())) }
        val first=entries().getValue(0)
        assertEquals(3,entries().size)
        val load=host.javaClass.getDeclaredMethod("load").apply { isAccessible=true }
        provider.lines=5;load.invoke(host);drain()
        assertEquals(5,entries().size)
        assertSame(first,entries().getValue(0))
        provider.lines=0;load.invoke(host);drain()
        repeat(35) { post(notification("无限$it",100L+it)) }
        assertEquals(42,entries().size)
        val screen=context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
        entries().values.forEach {
            val p=view(it).layoutParams as WindowManager.LayoutParams
            assertTrue(p.y>=0&&p.y+p.height<=screen)
        }
        provider.lines=3;load.invoke(host);drain()
        assertEquals(42,entries().size)
        assertSame(first,entries().getValue(0))
    }

    @Test fun anOldListenerCancellationDoesNotRemoveANewerVersionWithTheSameKey() {
        val first = notification("测试1", 1)
        post(first)
        drain()
        finish(0)
        val second = notification("测试2", 2)
        post(second)
        val current = entries().getValue(0)
        host.notificationRemoved(first)
        assertSame(current, entries().getValue(0))
        host.notificationRemoved(second)
        assertTrue(entries().isEmpty())
    }

    @Test fun savingAnOlderLineDoesNotCancelTheNewerNotificationThatIsStillQueued() {
        val blocked = CountDownLatch(1)
        worker.execute { blocked.await(5, TimeUnit.SECONDS) }
        try {
            post(notification("测试1", 1))
            post(notification("其他1", 2, "example.one"))
            post(notification("其他2", 3, "example.two"))
            val newest = notification("测试2", 4)
            post(newest)
            blocked.countDown()
            drain()
            assertTrue(listener.activeNotifications.any { it.key == newest.key && it.postTime == newest.postTime })
            assertFalse(texts().contains("QQ：测试2"))
            finish(1)
            drain()
            assertTrue(texts().contains("QQ：测试2"))
            assertTrue(ReflectionHelpers.getField<Boolean>(entries().getValue(1), "saved"))
            assertFalse(listener.activeNotifications.any { it.key == newest.key })
        } finally { blocked.countDown() }
    }

    @Test fun suspensionPausesAllLinesAndResumesTheirExistingViews() {
        repeat(3) { post(notification("测试$it", it.toLong())) }
        val before = entries().mapValues { view(it.value) }
        host.environment("example.game", true)
        before.values.forEach { assertEquals(android.view.View.INVISIBLE, it.visibility) }
        host.environment("example.game", false)
        before.forEach { (lane, original) ->
            assertSame(original, view(entries().getValue(lane)))
            assertEquals(android.view.View.VISIBLE, original.visibility)
        }
        host.environment("example.other", false)
        assertTrue(entries().isEmpty())
    }

    @Test fun structuredNotificationsUseTheLatestMessageAndPreserveLiteralMessageText() {
        val sender = Person.Builder().setName("发件人").build()
        val style = Notification.MessagingStyle(Person.Builder().setName("我").build())
            .addMessage("测试1", 10, sender)
            .addMessage("（两条消息）这是正文", 20, sender)
        val n = Notification.Builder(context, "test").setStyle(style).build()
        val parsed = BarrageHost.content(n, "QQ")
        assertEquals("发件人", parsed.sender)
        assertEquals("（两条消息）这是正文", parsed.text)
        assertEquals(20L, parsed.messageTime)
    }

    @Test fun inboxUsesTheLatestLineAndFallbackSummaryRemovesOnlyTheLeadingCounter() {
        val n = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "QQ")
            extras.putString(Notification.EXTRA_TEXT, "（两条消息）测试2")
        }
        assertEquals("测试2", BarrageHost.content(n, "QQ").text)
        n.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("测试1", "测试2"))
        assertEquals("测试2", BarrageHost.content(n, "QQ").text)
        n.extras.remove(Notification.EXTRA_TEXT_LINES)
        n.extras.putString(Notification.EXTRA_TEXT, "正文里的（两条消息）保留")
        assertEquals("正文里的（两条消息）保留", BarrageHost.content(n, "QQ").text)
    }
}
