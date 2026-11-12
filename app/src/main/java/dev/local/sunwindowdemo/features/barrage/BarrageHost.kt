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
import dev.local.sunwindowdemo.features.barrage.BarrageStore
import dev.local.sunwindowdemo.features.iconlayers.IconAssets
import java.util.concurrent.Executors

/** Notification display is independent of small-window enablement and has no input monitor. */
class BarrageHost(private val context: Context): AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val wm=context.getSystemService(WindowManager::class.java)
    private var config=Bundle()
    private var foreground=""
    private var blocked=true
    private var closed=false
    private var view: BarrageLyricsView?=null
    private var key: String?=null
    private var ignoredRemovalKey: String?=null
    private val expire=Runnable {hide()}
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
                config=next ?: Bundle();if(!eligible())hide()
                if(next==null)main.postDelayed({load()},3000)
            }
        }
    }
    fun environment(pkg: String?,suspended: Boolean) {
        val changed=foreground!=pkg.orEmpty()
        if(changed)hide()
        foreground=pkg.orEmpty();blocked=suspended
        if(changed)return
        if(suspended){view?.pause();return}
        if(!eligible()){hide();return}
        view?.resume()
    }
    private fun eligible()=!closed&&!blocked&&config.getBoolean("enabled")&&config.getStringArrayList("apps")?.contains(foreground)==true&&
        context.getSystemService(PowerManager::class.java).isInteractive&&!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun notification(source: NotificationListenerService,sbn: StatusBarNotification) {
        if(!eligible())return
        val n=sbn.notification
        if(sbn.user!=Process.myUserHandle()||n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT)!=0||
            n.visibility==Notification.VISIBILITY_SECRET||n.category in listOf(Notification.CATEGORY_CALL,Notification.CATEGORY_ALARM,Notification.CATEGORY_TRANSPORT))return
        val title=n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(80).orEmpty()
        val text=n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(180).orEmpty()
        val appLabel=runCatching {context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName,0)).toString()}
            .getOrDefault(sbn.packageName)
        val conversation=n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val sender=conversation.ifBlank {title.trim()}.ifBlank {appLabel}.take(40)
        val message=text.replace('\n',' ').replace('\r',' ').trim().take(120)
        if(sender.isBlank()&&message.isBlank())return
        val line=when {
            message.isBlank() -> sender
            sender.isBlank() -> message
            else -> "$sender：$message"
        }
        val icon=runCatching {
            val original=context.packageManager.getApplicationIcon(sbn.packageName)
            if(IconAssets.ready()) AppCatalog.icon(context,sbn.packageName,original) else original
        }.getOrNull()
        hide()
        try {
            lateinit var lyrics: BarrageLyricsView
            lyrics=BarrageLyricsView(context,line,icon) {main.post {if(lyrics===view)hide()}}
            val density=context.resources.displayMetrics.density
            val metrics=wm.currentWindowMetrics
            val safe=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars())
            val width=(metrics.bounds.width()-safe.left-safe.right).coerceAtLeast(1)
            val lane=maxOf((30*density).toInt(),(18f*context.resources.displayMetrics.scaledDensity+8*density).toInt())
            @Suppress("WrongConstant")
            val p=WindowManager.LayoutParams(width,lane,SystemUiHost.OVERLAY_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT)
            p.gravity=Gravity.TOP or Gravity.LEFT;p.x=safe.left;p.y=safe.top+(12*density).toInt()
            p.setFitInsetsTypes(0);p.title="NativeComplements-barrage";p.windowAnimations=0;SystemUiHost.trust(p)
            wm.addView(lyrics,p);view=lyrics;key=sbn.key
            main.postDelayed(expire,30_000)
            recordHistory(sbn,appLabel,sender,message) {saved->
                if(saved&&lyrics===view){
                    ignoredRemovalKey=sbn.key
                    runCatching {source.cancelNotification(sbn.key)}
                }
            }
        } catch(error: Throwable) {hide();android.util.Log.w("NativeComplements","BARRAGE_UNAVAILABLE",error)}
    }
    private fun recordHistory(sbn: StatusBarNotification,app:String,source:String,text:String,done:(Boolean)->Unit){
        worker.execute {
            val saved=runCatching {
                val extras=Bundle().apply {
                    putLong("time",System.currentTimeMillis());putString("package",sbn.packageName)
                    putString("app",app);putString("source",source);putString("text",text)
                }
                val result=context.contentResolver.call(Uri.parse("content://${BarrageStore.AUTHORITY}"),"addHistory",null,extras)
                result?.getBoolean("saved")==true
            }.getOrDefault(false)
            main.post {done(saved)}
        }
    }
    fun notificationRemoved(removed: String) {
        if(removed==ignoredRemovalKey){ignoredRemovalKey=null;return}
        if(key==removed)hide()
    }
    private fun hide() {
        main.removeCallbacks(expire)
        val old=view;view=null;key=null;ignoredRemovalKey=null
        old?.cancel()
        old?.let {runCatching {wm.removeViewImmediate(it)}}
    }
    override fun close() {if(closed)return;closed=true;hide();context.unregisterReceiver(receiver);main.removeCallbacksAndMessages(null);worker.shutdownNow()}
}
