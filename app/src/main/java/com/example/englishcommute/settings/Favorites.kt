package com.example.englishcommute.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 收藏（星标）。
 *
 * 存的是 episodeId 集合。**不存整条 Episode** —— 条目元信息以 `episodes.json` 为准，
 * 存在两处必然会出现「收藏里还是三天前的旧标题」这类不同步。
 *
 * 用 SharedPreferences 的 `StringSet`：收藏是低频操作（不是每 5 秒写一次），
 * 一份 key 装完，不需要一 id 一行的结构。
 */
class Favorites(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _ids = MutableStateFlow(
        prefs.getStringSet(KEY_IDS, emptySet())?.toSet() ?: emptySet()
    )
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /** 切换收藏，返回切换后的状态（true = 已收藏） */
    fun toggle(episodeId: String): Boolean {
        val current = _ids.value
        val next = if (episodeId in current) current - episodeId else current + episodeId
        persist(next)
        return episodeId in next
    }

    /**
     * 取消收藏（幂等，1.2.2）。
     *
     * 与 [toggle] 的差别是「不做判断」：调用方（长按菜单的删除）已经知道
     * 自己要的是「取消」而不是「切换」，让它去读一次状态再决定，
     * 就多了一种「读到的和删掉的不是同一刻」的可能。
     */
    fun remove(episodeId: String) {
        val current = _ids.value
        if (episodeId !in current) return
        persist(current - episodeId)
    }

    /**
     * 条目已从索引里消失时清理。
     *
     * 目前索引裁剪不会波及收藏（收藏的条目一般不会被裁），但源被替换后
     * 历史条目会被整批清掉，那时收藏里会留下永远点不开的 id。
     */
    fun retainOnly(aliveIds: Set<String>) {
        val current = _ids.value
        val next = current intersect aliveIds
        if (next.size != current.size) persist(next)
    }

    private fun persist(value: Set<String>) {
        // ⚠️ 必须写一份新的 Set。SharedPreferences 内部对 StringSet 是**引用持有**，
        //    直接把同一个实例写回去，下次读出来还是旧对象，改动会「看起来没生效」。
        prefs.edit().putStringSet(KEY_IDS, HashSet(value)).apply()
        _ids.value = value
    }

    companion object {
        private const val PREFS_NAME = "favorites"
        private const val KEY_IDS = "ids"
    }
}
