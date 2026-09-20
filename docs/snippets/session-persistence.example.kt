// 断点续学：把当前会话存下来，退出/被杀进程后能恢复。
//
// 设计要点：**只存卡片 ID，不存卡片内容**。恢复时按 ID 重新查库，
// 这样期间如果词库内容刷新过（改了释义、加了词性），恢复出来的是新内容；
// 存整个卡片对象的话会让用户看到一份过期的数据。

package com.example.yourapp.data.study

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PersistedSession(
    val vocabId: Long,
    val mode: String,                 // "LEARN" / "REVIEW" / "UNIT"
    val unit: String? = null,
    val total: Int,

    // 队列：卡片 ID + 每张卡的状态，平行数组
    val queueCardIds: List<Long>,
    val queuePasses: List<Int>,       // 这一组里认对了几次
    val queueAttempts: List<Int>,     // 这一组里判了几次

    val doneCardIds: List<Long>,
    val doneAttempts: List<Int>,      // 总结页要显示「过了几遍才过」

    val parkedCardIds: List<Long>,    // 判太多次先放过的
    val parkedAttempts: List<Int>,
    val parkedPasses: List<Int>,

    val startedAt: Long,
    val savedAt: Long = System.currentTimeMillis(),
)

private val Context.sessionDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "study_session")

class StudySessionStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true      // ⚠️ 以后加字段时，老数据也能读进来
    }
    private val KEY_SESSION = stringPreferencesKey("persisted_session")

    suspend fun save(session: PersistedSession) {
        context.sessionDataStore.edit { prefs ->
            prefs[KEY_SESSION] = json.encodeToString(session)
        }
    }

    suspend fun load(): PersistedSession? {
        val data = context.sessionDataStore.data.map { prefs -> prefs[KEY_SESSION] }.first()
        return data?.let { json.decodeFromString<PersistedSession>(it) }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { prefs -> prefs.remove(KEY_SESSION) }
    }
}

// ---------------------------------------------------------------- 存 / 取

/** ViewModel 里：每次判卡后存一次 */
private suspend fun persistSession(session: SessionState) {
    val vocabId = currentBook?.id ?: return
    sessionStore.save(
        PersistedSession(
            vocabId = vocabId,
            mode = session.mode.name,
            unit = session.unit,
            total = session.total,
            queueCardIds = session.queue.map { it.card.id },
            queuePasses = session.queue.map { it.passes },
            queueAttempts = session.queue.map { it.attempts },
            doneCardIds = session.done.map { it.card.id },
            doneAttempts = session.done.map { it.attempts },
            parkedCardIds = session.parked.map { it.card.id },
            parkedAttempts = session.parked.map { it.attempts },
            parkedPasses = session.parked.map { it.passes },
            startedAt = session.startedAt,
        )
    )
}

/** 恢复：按 ID 重新查库，拼回 SessionState */
suspend fun restore(persisted: PersistedSession): SessionState? {
    // 逐张按 ID 查；查不到的（被删了）直接丢掉
    fun cardOf(id: Long) = cardDAO.getCardById(id) ?: return null

    val queue = persisted.queueCardIds.mapIndexed { i, id ->
        SessionCard(
            card = cardOf(id) ?: return null,
            passes = persisted.queuePasses.getOrElse(i) { 0 },
            attempts = persisted.queueAttempts.getOrElse(i) { 0 },
        )
    }
    // ...done / parked 同理

    return SessionState(
        mode = StudyMode.valueOf(persisted.mode),
        unit = persisted.unit,
        total = persisted.total,
        queue = queue,
        // done = ..., parked = ...,
        startedAt = persisted.startedAt,
    )
}

// ---------------------------------------------------------------- 什么时候问用户
//
// 恢复不要直接进学习页 —— 用户可能已经忘了上次在干什么。
// 进词库详情页时检测有没有未完成会话，有就弹框：
//
//     上次学到一半（第 7 / 10 个），继续吗？
//     [ 继续 ]  [ 重新开始 ]
//
// 点「重新开始」直接 clear() 就行：已经判过的词仍然算「未学习」
// （组内没攒够次数时不落卡片状态，见 docs/01-repository.md），
// 所以重新开始不会丢进度，只是这一组要重来。
