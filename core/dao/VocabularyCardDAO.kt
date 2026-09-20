package com.example.vocabulary.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.example.vocabulary.data.entity.VocabularyCardEntity

@Dao
interface VocabularyCardDAO {
    @Insert
    suspend fun insert(card: VocabularyCardEntity): Long

    @Insert
    suspend fun insertAll(cards: List<VocabularyCardEntity>)

    @Update
    suspend fun update(card: VocabularyCardEntity)

    @Update
    suspend fun updateAll(cards: List<VocabularyCardEntity>)

    @Delete
    suspend fun delete(card: VocabularyCardEntity)

    // 顺序一律走 id（= 导入顺序 = 书本顺序）。
    // ⚠️ 不要用 created_at 排序：批量导入时所有卡写的是同一个时间戳，排序键全相等，
    // 实际顺序会变成靠 rowid 蒙对。
    @Query("SELECT * FROM vocabulary_card WHERE vocab_id = :vocabId ORDER BY id")
    fun getCardsByVocabId(vocabId: Long): Flow<List<VocabularyCardEntity>>

    @Query("SELECT * FROM vocabulary_card WHERE id = :cardId")
    suspend fun getCardById(cardId: Long): VocabularyCardEntity?

    /** 按词面找卡：从学习界面点「问 AI」进来时用它把那个词捞出来 */
    @Query("SELECT * FROM vocabulary_card WHERE vocab_id = :vocabId AND word = :word LIMIT 1")
    suspend fun getCardByWord(vocabId: Long, word: String): VocabularyCardEntity?

    /** 未学习：还没攒够次数出师的 */
    @Query("SELECT * FROM vocabulary_card WHERE vocab_id = :vocabId AND learned = 0 ORDER BY id LIMIT :limit")
    suspend fun getNewCards(vocabId: Long, limit: Int): List<VocabularyCardEntity>

    /** 待复习：学过的、且到日子了。毕业的词 next_review_date 是 null，天然被排除 */
    @Query(
        "SELECT * FROM vocabulary_card WHERE vocab_id = :vocabId AND learned = 1 " +
            "AND next_review_date IS NOT NULL AND next_review_date <= :currentTime " +
            "ORDER BY next_review_date, id LIMIT :limit"
    )
    suspend fun getReviewCards(vocabId: Long, currentTime: Long, limit: Int): List<VocabularyCardEntity>

    /** 按单元取未学的卡（「按课学习」用） */
    @Query("SELECT * FROM vocabulary_card WHERE vocab_id = :vocabId AND unit = :unit AND learned = 0 ORDER BY id")
    suspend fun getCardsByUnit(vocabId: Long, unit: String): List<VocabularyCardEntity>

    // ------------------------------------------------------------------ 统计

    @Query("SELECT COUNT(*) FROM vocabulary_card WHERE vocab_id = :vocabId")
    fun countAllCards(vocabId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM vocabulary_card WHERE vocab_id = :vocabId AND learned = 0")
    fun countNewCards(vocabId: Long): Flow<Int>

    /** 学习中 = 学过但还没毕业（毕业的词 next_review_date 被置空） */
    @Query("SELECT COUNT(*) FROM vocabulary_card WHERE vocab_id = :vocabId AND learned = 1 AND next_review_date IS NOT NULL")
    fun countStudiedCards(vocabId: Long): Flow<Int>

    /** 已掌握 = 间隔拉到顶再认对一次，退出复习循环 */
    @Query("SELECT COUNT(*) FROM vocabulary_card WHERE vocab_id = :vocabId AND learned = 1 AND next_review_date IS NULL")
    fun countMasteredCards(vocabId: Long): Flow<Int>

    /** 到点该复习的 */
    @Query(
        "SELECT COUNT(*) FROM vocabulary_card WHERE vocab_id = :vocabId AND learned = 1 " +
            "AND next_review_date IS NOT NULL AND next_review_date <= :currentTime"
    )
    fun countReviewCards(vocabId: Long, currentTime: Long): Flow<Int>

    /**
     * 每个单元的卡片数量（「按课学习」的入口要显示）。
     *
     * ⚠️ 不带 ORDER BY：SQLite 按字典序返回（Lektion 1, Lektion 10, Lektion 2…），
     * 排序交给 Kotlin 按课号数字来。
     */
    @Query(
        "SELECT unit, COUNT(*) AS count, SUM(CASE WHEN learned = 0 THEN 1 ELSE 0 END) AS newCount " +
            "FROM vocabulary_card WHERE vocab_id = :vocabId AND unit IS NOT NULL GROUP BY unit"
    )
    suspend fun countCardsByUnit(vocabId: Long): List<UnitCount>

    /** 获取所有单元（去重） */
    @Query("SELECT DISTINCT unit FROM vocabulary_card WHERE vocab_id = :vocabId AND unit IS NOT NULL")
    suspend fun getUnits(vocabId: Long): List<String>

    /** 已经学过多少个词（补记历史积分用） */
    @Query("SELECT COUNT(*) FROM vocabulary_card WHERE vocab_id = :vocabId AND learned = 1")
    suspend fun countLearned(vocabId: Long): Int

    /**
     * 按**当前卡片状态**倒推这本书一共该有多少分（补记历史积分用）。
     * 每词：学会 + 每档熟练度 + 毕业/熟词。
     *
     * ⚠️ 这里的常数要和 `ScoreRules` 里的对齐。
     */
    @Query(
        "SELECT COALESCE(SUM(3 + 2 * mastery + CASE WHEN next_review_date IS NULL THEN 5 ELSE 0 END), 0) " +
            "FROM vocabulary_card WHERE vocab_id = :vocabId AND learned = 1"
    )
    suspend fun historicalScoreOf(vocabId: Long): Int

    /**
     * 所有词库的进度汇总，**一条查询搞定**（主页卡片用）。
     *
     * ⚠️ 不要拿它当 Flow 用：`due` 依赖传进来的 [now]，时间过去了 Room 不会自动重发，
     * 得外面定时重查。
     */
    @Query(
        """
        SELECT vocab_id AS vocabId,
               COUNT(*) AS total,
               SUM(CASE WHEN learned = 0 THEN 1 ELSE 0 END) AS newCount,
               SUM(CASE WHEN learned = 1 AND next_review_date IS NOT NULL THEN 1 ELSE 0 END) AS studying,
               SUM(CASE WHEN learned = 1 AND next_review_date IS NOT NULL AND next_review_date <= :now
                        THEN 1 ELSE 0 END) AS due,
               SUM(CASE WHEN learned = 1 AND next_review_date IS NULL THEN 1 ELSE 0 END) AS mastered
        FROM vocabulary_card
        GROUP BY vocab_id
        """
    )
    suspend fun statsByBook(now: Long): List<BookStatsRow>
}

/** [VocabularyCardDAO.statsByBook] 的一行：一本词库的进度汇总 */
data class BookStatsRow(
    val vocabId: Long,
    val total: Int,
    val newCount: Int,
    val studying: Int,
    val due: Int,
    val mastered: Int,
)

/** 一个单元的卡片统计 */
data class UnitCount(
    @androidx.room.ColumnInfo(name = "unit") val unit: String,
    @androidx.room.ColumnInfo(name = "count") val count: Int,
    @androidx.room.ColumnInfo(name = "newCount") val newCount: Int,
)
