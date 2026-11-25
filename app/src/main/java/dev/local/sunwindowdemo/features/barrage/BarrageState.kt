package dev.local.sunwindowdemo.features.barrage

/** Value-only delivery ledger. Native notification sources, views and main-thread ownership stay in the host. */
internal class BarrageState {
    data class Delivery(val sender: String, val text: String, val time: Long)
    data class Seen(val delivery: Delivery, val postTime: Long)
    private val recent = linkedMapOf<String, Seen>()

    fun observe(key: String, delivery: Delivery, postTime: Long): Boolean {
        val previous = recent.put(key, Seen(delivery, postTime))
        if (recent.size > 128) recent.remove(recent.keys.first())
        return previous?.delivery == delivery
    }
    fun current(key: String, delivery: Delivery, postTime: Long) = recent[key] == Seen(delivery, postTime)
    fun displayFailed(key: String, delivery: Delivery, postTime: Long) {
        if (current(key, delivery, postTime)) recent.remove(key)
    }
    fun clear() { recent.clear() }

    companion object {
        fun enabled(closed: Boolean, blocked: Boolean, enabled: Boolean, selected: Boolean) = !closed && !blocked && enabled && selected
        fun admissible(sameUser: Boolean, summary: Boolean, ongoing: Boolean, secret: Boolean, category: String?) =
            sameUser && !summary && !ongoing && !secret && category !in listOf("call", "alarm", "transport")
        fun shouldSuspend(systemOverlay: Boolean, title: String?, keyboard: Boolean, menu: Boolean, waiting: Boolean) =
            keyboard || menu || waiting || (systemOverlay && title != "ScreenshotUI")
        fun lineLimit(value: Int) = if (value == 0 || value == 5) value else 3
        fun canEnqueue(pending: Int) = pending < 32
        // A lower limit only gates new admissions; existing lanes are never evicted.
        fun canPlay(pending: Int, playing: Int, limit: Int) = pending > 0 && (limit == 0 || playing < limit)
        fun lane(occupied: Set<Int>): Int = generateSequence(0) { it + 1 }.first { it !in occupied }
        fun row(lane: Int, rows: Int) = lane % rows.coerceAtLeast(1)
        fun canSave(saving: Boolean, saved: Boolean) = !saving && !saved
    }
}
