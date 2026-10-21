package com.example.englishcommute.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 收听记录：断点位置 + 「已听完」标记。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 *
 * 这里每 5 秒就要写一次，而 DataStore 每次 `edit` 都是「读全量 → 改 → 写全量」的事务，
 * 高频写入下既费 IO 又没意义。SharedPreferences 的 `apply()` 是内存先行 + 异步落盘，
 * 正好适配「频繁写、偶尔读」的场景。
 *
 * ## 为什么要额外维护一份内存 Map
 *
 * 素材库要在每条卡片上显示进度条。如果界面直接读 `prefs.getLong()`，
 * 那么「播放页在写、列表页在显示」这条链路上没有任何通知机制 ——
 * 用户从播放页返回列表，进度条还是进播放页之前的旧值。
 * 所以这里把记录同时放进 [StateFlow]，写的时候顺手推一次，
 * 列表就能实时跟随（只要有人订阅，写盘仍然是异步的）。
 *
 * ## 为什么「听完」要单独记一笔
 *
 * 播到结尾时断点位置会被清掉（下次从头开始，这是对的）。
 * 但「这条我听过了」这个信息不能跟着一起消失 —— 否则列表上刚听完的和从没碰过的长得一模一样。
 * 所以听完时写一个 `done:<id>` 标记，位置清零、标记留下。
 */
class PlaybackPositions(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _positions = MutableStateFlow(loadPositions())
    val positions: StateFlow<Map<String, Long>> = _positions.asStateFlow()

    private val _done = MutableStateFlow(loadDone())
    val done: StateFlow<Set<String>> = _done.asStateFlow()

    fun get(episodeId: String): Long = _positions.value[episodeId] ?: 0L

    fun isDone(episodeId: String): Boolean = episodeId in _done.value

    /**
     * 记录断点位置。
     *
     * 顺带清掉「听完」标记：重新听了几秒就不再是「听完」状态了。
     * 放在这里而不是「点播放就清」—— 调用方要求位置超过 3 秒才会写，
     * 所以「点开看一眼又退出去」不会把标记抹掉。
     */
    fun put(episodeId: String, positionMs: Long) {
        val value = positionMs.coerceAtLeast(0L)
        prefs.edit().putLong(episodeId, value).apply()
        _positions.update { it + (episodeId to value) }
        if (episodeId in _done.value) clearDone(episodeId)
    }

    fun clear(episodeId: String) {
        prefs.edit().remove(episodeId).apply()
        _positions.update { it - episodeId }
    }

    /** 播到结尾：位置清零 + 打「听完」标记 */
    fun markDone(episodeId: String) {
        prefs.edit()
            .remove(episodeId)
            .putBoolean(doneKey(episodeId), true)
            .apply()
        _positions.update { it - episodeId }
        _done.update { it + episodeId }
    }

    fun clearDone(episodeId: String) {
        prefs.edit().remove(doneKey(episodeId)).apply()
        _done.update { it - episodeId }
    }

    /**
     * 清空全部记录。
     *
     * 清缓存时必须一起调用 —— 文件都删了，留着一堆指向不存在音频的进度和「听完」标记，
     * 列表上会出现「已听完」但点开提示「音频文件不在了」的鬼条目。
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        _positions.value = emptyMap()
        _done.value = emptySet()
    }

    private fun loadPositions(): Map<String, Long> =
        prefs.all.mapNotNull { (key, value) ->
            // 只认 Long：同一份 prefs 里还存着 Boolean 的 done 标记
            if (value is Long) key to value else null
        }.toMap()

    private fun loadDone(): Set<String> =
        prefs.all.filterValues { it == true }
            .keys
            .filter { it.startsWith(DONE_PREFIX) }
            .map { it.removePrefix(DONE_PREFIX) }
            .toSet()

    private fun doneKey(episodeId: String) = DONE_PREFIX + episodeId

    companion object {
        private const val PREFS_NAME = "playback_positions"

        /** 前缀不能和 episodeId 撞：id 是十六进制哈希，不含冒号 */
        private const val DONE_PREFIX = "done:"
    }
}
