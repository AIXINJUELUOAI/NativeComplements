package dev.local.sunwindowdemo

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.local.sunwindowdemo.core.ui.ComplementsTheme

internal fun gameQuickFeedback(view: View) {
    // No ignore-settings flags: respect the user's system haptic preference.
    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
}

internal data class GameQuickItem(val id: String, val label: String, val icon: ImageBitmap?)

/** The existing native window owns blur and bounds; only its quick page composes Material controls. */
internal class GameQuickView(context: Context, actions: List<QuickActionCatalog.Action>, quick: (String) -> Unit,
    private val themeChanged: (ColorScheme) -> Unit = {}) : FrameLayout(context) {
    internal val sliders = GameSliderState(context)
    private val media = GameMediaState(context)
    private val quickState = GameQuickState(context)
    private var owner: OverlayComposition? = null
    private var compositionToken: Any? = null
    private var failed = false
    private var suspended = false
    internal fun suspendControls() {
        suspended = true
        sliders.close(); media.close(); quickState.close()
    }
    private val entries = actions.map { action ->
        GameQuickItem(action.id(), action.label(), QuickActionCatalog.icon(context, action)?.toBitmap(96, 96)?.asImageBitmap())
    }
    private val action = quick
    private var ready = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (owner == null) {
            val token = Any()
            compositionToken = token
            val composition = OverlayComposition(context) { failure ->
                android.util.Log.w("NativeComplements", "GAMEMODE_COMPOSITION", failure)
                post {
                    if (compositionToken === token) {
                        failed = true; sliders.close(); media.close(); quickState.close(); owner?.close(); owner = null; removeAllViews()
                    }
                }
            }
            owner = composition
            try {
                addView(composition.view {
                    ComplementsTheme {
                        val colors = MaterialTheme.colorScheme
                        // Native chrome shares this configuration-aware palette, not a construction-time theme snapshot.
                        SideEffect { themeChanged(colors) }
                        GameQuickContent(sliders, media, entries, { id ->
                            if (!suspended && isShown) {
                                gameQuickFeedback(this@GameQuickView)
                                action(id)
                                quickState.refresh()
                            }
                        }, quickState.states)
                    }
                }, LayoutParams(-1, -1))
            } catch (failure: RuntimeException) {
                android.util.Log.w("NativeComplements", "GAMEMODE_COMPOSITION", failure)
                composition.close(); owner = null
            }
        }
        syncVisibility()
    }
    private fun syncVisibility() {
        if (!ready) return
        // Native visibility is authoritative, including same-frame library switches.
        if (!failed && !suspended && owner != null && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            sliders.start(); media.start(); quickState.start()
        } else { sliders.close(); media.close(); quickState.close() }
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready) syncVisibility()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ready) syncVisibility()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (ready) {
            if (isVisible) syncVisibility() else { sliders.close(); media.close(); quickState.close() }
        }
    }
    override fun onDetachedFromWindow() {
        compositionToken = null
        sliders.close(); media.close(); quickState.close()
        owner?.close(); owner = null; removeAllViews()
        super.onDetachedFromWindow()
    }
}

internal fun gamePanelSectionColor(colors: ColorScheme) =
    if (colors.surface.luminance() > .5f) colors.surfaceContainerLow else colors.surfaceContainerHigh

/** One shared content width, with no independent row padding: every item uses the same four columns. */
@Composable
internal fun GameQuickContent(sliders: GameSliderState, media: GameMediaState, entries: List<GameQuickItem>,
    quick: (String) -> Unit, states: Map<String, Boolean?> = emptyMap()) {
    val colors = MaterialTheme.colorScheme
    val sectionColor = gamePanelSectionColor(colors)
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.testTag("brightness-section"), shape = RoundedCornerShape(20.dp), color = sectionColor) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.gm_brightness), null, Modifier.size(24.dp).testTag("brightness-icon"))
                    }
                    Slider(sliders.brightness, {
                        sliders.beginBrightness(); sliders.setBrightnessProgress(it)
                    }, Modifier.weight(3f).padding(end = 12.dp).testTag("brightness-slider").semantics { contentDescription = "屏幕亮度" },
                        enabled = sliders.brightnessEnabled, valueRange = 0f..1000f, onValueChangeFinished = sliders::endBrightness)
                }
        }
        Surface(Modifier.testTag("volume-section"), shape = RoundedCornerShape(20.dp), color = sectionColor) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.gm_volume), null, Modifier.size(24.dp).testTag("volume-icon"))
                    }
                    Slider(sliders.volume, {
                        sliders.beginVolume(); sliders.setVolumeProgress(it)
                    }, Modifier.weight(3f).padding(end = 12.dp).testTag("volume-slider").semantics { contentDescription = "媒体音量" },
                        enabled = sliders.volumeEnabled, valueRange = sliders.volumeMin..maxOf(sliders.volumeMin + 1f, sliders.volumeMax),
                        onValueChangeFinished = sliders::endVolume)
                }
        }
        Surface(Modifier.testTag("media-section"), shape = RoundedCornerShape(20.dp), color = sectionColor) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        val cover = media.cover
                        if (cover != null && !cover.isRecycled) {
                            Image(cover.asImageBitmap(), "媒体封面", Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).testTag("media-cover"), contentScale = ContentScale.Fit)
                        } else {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHighest).testTag("media-cover"))
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(media::previous, enabled = media.canPrevious, modifier = Modifier.testTag("media-previous")) {
                            Icon(painterResource(R.drawable.gm_previous), "上一首", Modifier.size(24.dp))
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(media::toggle, enabled = media.canToggle, modifier = Modifier.testTag("media-toggle")) {
                            Icon(painterResource(if (media.playing) R.drawable.gm_pause else R.drawable.gm_play), if (media.playing) "暂停" else "播放", Modifier.size(24.dp))
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(media::next, enabled = media.canNext, modifier = Modifier.testTag("media-next")) {
                            Icon(painterResource(R.drawable.gm_next), "下一首", Modifier.size(24.dp))
                        }
                    }
                }
        }
        Surface(Modifier.weight(1f).fillMaxWidth().testTag("quick-section"), shape = RoundedCornerShape(20.dp), color = sectionColor) {
            LazyColumn(Modifier.fillMaxSize().testTag("quick-grid"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                itemsIndexed(entries.chunked(4)) { rowIndex, row ->
                    Row(Modifier.fillMaxWidth().height(76.dp)) {
                        repeat(4) { column ->
                            val item = row.getOrNull(column)
                            if (item == null) Spacer(Modifier.weight(1f)) else {
                                val active = states[item.id]
                                val discColor = if (active == true) colors.primary else colors.surfaceContainerHighest
                                val iconColor = if (active == true) colors.onPrimary else colors.onSurfaceVariant
                                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable { quick(item.id) }
                                    .semantics { if (states.containsKey(item.id)) stateDescription = when (active) {
                                        true -> "已开启"; false -> "已关闭"; null -> "状态未知"
                                    } }.testTag("quick-${rowIndex * 4 + column}"), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                                    Box(Modifier.size(36.dp).background(discColor, CircleShape).testTag("quick-icon-${rowIndex * 4 + column}"), contentAlignment = Alignment.Center) {
                                        item.icon?.let { Icon(it, null, Modifier.size(20.dp), tint = iconColor) }
                                    }
                                    Text(item.label, Modifier.padding(horizontal = 2.dp), fontSize = 11.sp, lineHeight = 13.sp,
                                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
