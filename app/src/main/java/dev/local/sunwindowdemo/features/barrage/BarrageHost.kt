package dev.local.sunwindowdemo

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.*
import android.view.WindowInsets
import androidx.core.app.NotificationCompat
import dev.local.sunwindowdemo.features.barrage.BarrageStore
import dev.local.sunwindowdemo.features.iconlayers.IconAssets
import java.util.concurrent.Executors

/** Notification display is independent of small-window enablement and has no input monitor. */
class BarrageHost(private val context: Context): AutoCloseable {
    companion object {
        private const val MAX_PENDING = 32
        private const val RECENT_LIMIT = 128
        private val summaryPrefix = Regex("^[（(\\[【][0-9一二三四五六七八九十百千零〇两]+\\s*条(?:新)?消息[）)\\]】]\\s*")

        internal data class Content(val sender: String, val text: String, val messageTime: Long)

        internal fun content(notification: Notification, app: String): Content {
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
            val latest = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) }
                .getOrNull()?.messages?.lastOrNull { !it.text.isNullOrBlank() }
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.lastOrNull { !it.isNullOrBlank() }
            val structured = latest?.text ?: lines
            val fallback = extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            val rawText = (structured ?: fallback)?.toString().orEmpty()
            val text = if (structured == null) rawText.trim().replaceFirst(summaryPrefix, "") else rawText
            val sender = conversation.ifBlank { latest?.person?.name?.toString()?.trim().orEmpty() }
                .ifBlank { title }.ifBlank { app }.take(40)
            return Content(sender, text.replace('\n', ' ').replace('\r', ' ').trim().take(120), latest?.timestamp ?: 0)
        }

        // ScreenshotUI is the native screenshot window, not an arbitrary SystemUI panel.
        // Screenshot focus must not hide/recreate the in-flight line or reset its animation.
        @JvmStatic fun shouldSuspend(systemOverlay: Boolean, overlayTitle: String?, keyboard: Boolean,
                                     menu: Boolean, waiting: Boolean): Boolean =
            keyboard || menu || waiting || (systemOverlay && overlayTitle != "ScreenshotUI")
    }
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val wm=context.getSystemService(WindowManager::class.java)
    private var config=Bundle()
    private var foreground=""
    private var blocked=true
    private var closed=false
    private data class Delivery(val sender: String, val text: String, val time: Long)
    private data class Seen(val delivery: Delivery, val postTime: Long)
    private data class Notice(
        val source: NotificationListenerService,
        val key: String,
        val packageName: String,
        val postTime: Long,
        val app: String,
        val content: Content,
        val delivery: Delivery,
    )
    private class Playing(val notice: Notice, val view: BarrageLyricsView, val expire: Runnable) {
        var saving = false
        var saved = false
    }
    private val pending = ArrayDeque<Notice>()
    private val playing = linkedMapOf<Int, Playing>()
    private val recent = linkedMapOf<String, Seen>()
    private val ignoredRemovals = linkedSetOf<Pair<String, Long>>()
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(c: Context,i: Intent) {
            if(i.action==Intent.ACTION_SCREEN_OFF)hide()
            else if(i.action==BarrageStore.CHANGED) {
                if(sentFromUid==runCatching {context.packageManager.getApplicationInfo(Wire.PKG,0).uid}.getOrDefault(-1))load()
            }
        }
    }
    init {
        context.registerReceiver(receiver,IntentFilter(BarrageStore.CHANGED).apply {addAction(Intent.ACTION_SCREEN_OFF)},null,main,Context.RECEIVER_EXPORTED)
        load()
    }
    private fun load() {
        if(closed)return
        worker.execute {
            val next=runCatching {context.contentResolver.call(Uri.parse("content://${BarrageStore.AUTHORITY}"),"read",null,null)}.getOrNull()
            main.post {
                if(closed)return@post
                config=next ?: Bundle();if(!eligible())hide() else drain()
                if(next==null)main.postDelayed({load()},3000)
            }
        }
    }
    fun environment(pkg: String?,suspended: Boolean) {
        val changed=foreground!=pkg.orEmpty()
        if(changed)hide()
        foreground=pkg.orEmpty();blocked=suspended
        if(changed)return
        if(suspended){playing.values.forEach { it.view.pause() };return}
        if(!eligible()){hide();return}
        playing.values.forEach { it.view.resume() }
        drain()
    }
    private fun eligible()=!closed&&!blocked&&config.getBoolean("enabled")&&config.getStringArrayList("apps")?.contains(foreground)==true&&
        context.getSystemService(PowerManager::class.java).isInteractive&&!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun notification(source: NotificationListenerService,sbn: StatusBarNotification) {
        if(!eligible())return
        val n=sbn.notification
        if(sbn.user!=Process.myUserHandle()||n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT)!=0||
            n.visibility==Notification.VISIBILITY_SECRET||n.category in listOf(Notification.CATEGORY_CALL,Notification.CATEGORY_ALARM,Notification.CATEGORY_TRANSPORT))return
        val appLabel=runCatching {context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName,0)).toString()}
            .getOrDefault(sbn.packageName)
        val content = content(n, appLabel)
        if(content.sender.isBlank()&&content.text.isBlank())return
        val delivery = Delivery(content.sender, content.text, content.messageTime.takeIf { it > 0 } ?: sbn.postTime)
        val previous = recent.put(sbn.key, Seen(delivery, sbn.postTime))
        if (recent.size > RECENT_LIMIT) recent.remove(recent.keys.first())
        if (previous?.delivery == delivery) {
            playing.values.firstOrNull { it.notice.key == sbn.key && it.notice.delivery == delivery }
                ?.let { saveHistory(it) }
            return
        }
        // Queued notifications stay in the native tray until they are displayed and saved.
        if (pending.size >= MAX_PENDING) return
        pending.addLast(Notice(source, sbn.key, sbn.packageName, sbn.postTime, appLabel, content, delivery))
        drain()
    }

    private fun drain() {
        val limit = BarrageStore.lineLimit(config.getInt("lines",3))
        while (eligible() && pending.isNotEmpty() && (limit == 0 || playing.size < limit)) {
            val lane = generateSequence(0) { it + 1 }.first { it !in playing }
            show(pending.removeFirst(), lane)
        }
    }

    private fun show(notice: Notice, laneIndex: Int) {
        val sender = notice.content.sender
        val message = notice.content.text
        val line=when {
            message.isBlank() -> sender
            sender.isBlank() -> message
            else -> "$sender：$message"
        }
        val icon=runCatching {
            val original=context.packageManager.getApplicationIcon(notice.packageName)
            if(IconAssets.ready()) AppCatalog.icon(context,notice.packageName,original) else original
        }.getOrNull()
        var added: BarrageLyricsView? = null
        try {
            lateinit var lyrics: BarrageLyricsView
            lyrics=BarrageLyricsView(context,line,icon) {
                main.post { if (playing[laneIndex]?.view === lyrics) finish(laneIndex) }
            }
            val density=context.resources.displayMetrics.density
            val metrics=wm.currentWindowMetrics
            val safe=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars())
            val width=(metrics.bounds.width()-safe.left-safe.right).coerceAtLeast(1)
            val lane=maxOf((30*density).toInt(),(18f*context.resources.displayMetrics.scaledDensity+8*density).toInt())
            @Suppress("WrongConstant")
            val p=WindowManager.LayoutParams(width,lane,SystemUiHost.OVERLAY_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT)
            p.gravity=Gravity.TOP or Gravity.LEFT;p.x=safe.left
            val spacing = lane+(4*density).toInt()
            val rows = ((metrics.bounds.height()-safe.top-safe.bottom-(12*density).toInt())/spacing).coerceAtLeast(1)
            p.y=safe.top+(12*density).toInt()+(laneIndex%rows)*spacing
            p.setFitInsetsTypes(0);p.title="NativeComplements-barrage-$laneIndex";p.windowAnimations=0;SystemUiHost.trust(p)
            added = lyrics
            wm.addView(lyrics,p)
            val expire = Runnable { if (playing[laneIndex]?.view === lyrics) finish(laneIndex) }
            val entry = Playing(notice, lyrics, expire)
            playing[laneIndex] = entry
            main.postDelayed(expire,30_000)
            saveHistory(entry)
        } catch(error: Throwable) {
            playing.remove(laneIndex)?.let { main.removeCallbacks(it.expire) }
            added?.let { it.cancel(); runCatching { wm.removeViewImmediate(it) } }
            if (recent[notice.key] == Seen(notice.delivery, notice.postTime)) recent.remove(notice.key)
            android.util.Log.w("NativeComplements","BARRAGE_UNAVAILABLE",error)
        }
    }

    private fun saveHistory(entry: Playing) {
        if (entry.saving || entry.saved) return
        entry.saving = true
        val notice = entry.notice
        recordHistory(notice) { saved ->
            entry.saving = false
            entry.saved = saved
            if (!saved || entry !in playing.values || recent[notice.key] != Seen(notice.delivery, notice.postTime)) return@recordHistory
            val removal = notice.key to notice.postTime
            ignoredRemovals.add(removal)
            if (ignoredRemovals.size > RECENT_LIMIT) ignoredRemovals.remove(ignoredRemovals.first())
            runCatching { notice.source.cancelNotification(notice.key) }
                .onFailure { ignoredRemovals.remove(removal) }
        }
    }

    private fun recordHistory(notice: Notice,done:(Boolean)->Unit){
        worker.execute {
            val saved=runCatching {
                val extras=Bundle().apply {
                    putLong("time",notice.postTime);putString("package",notice.packageName)
                    putString("app",notice.app);putString("source",notice.content.sender);putString("text",notice.content.text)
                }
                val result=context.contentResolver.call(Uri.parse("content://${BarrageStore.AUTHORITY}"),"addHistory",null,extras)
                result?.getBoolean("saved")==true
            }.getOrDefault(false)
            main.post {done(saved)}
        }
    }
    fun notificationRemoved(removed: StatusBarNotification) {
        val removal = removed.key to removed.postTime
        if (ignoredRemovals.remove(removal)) return
        pending.removeAll { it.key == removed.key && it.postTime == removed.postTime }
        val lanes = playing.filterValues { it.notice.key == removed.key && it.notice.postTime == removed.postTime }.keys.toList()
        lanes.forEach { finish(it, advance = false) }
        drain()
    }

    private fun finish(lane: Int, advance: Boolean = true) {
        val old = playing.remove(lane) ?: return
        main.removeCallbacks(old.expire)
        old.view.cancel()
        runCatching { wm.removeViewImmediate(old.view) }
        if (advance) drain()
    }

    private fun hide() {
        pending.clear()
        playing.keys.toList().forEach { finish(it, advance = false) }
    }
    override fun close() {if(closed)return;closed=true;hide();recent.clear();ignoredRemovals.clear();context.unregisterReceiver(receiver);main.removeCallbacksAndMessages(null);worker.shutdownNow()}
}
