package com.example.vocabulary.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.example.vocabulary.data.entity.VocabularyScoreEntity

@Dao
interface VocabularyScoreDAO {

    @Insert
    suspend fun insert(score: VocabularyScoreEntity): Long

    @Insert
    suspend fun insertAll(scores: List<VocabularyScoreEntity>)

    /** 获取总积分（所有词库汇总） */
    @Query("SELECT COALESCE(SUM(score_delta), 0) FROM vocabulary_score")
    fun getTotalScore(): Flow<Int>

    /** 获取今日积分 */
    @Query(
        """
        SELECT COALESCE(SUM(score_delta), 0)
        FROM vocabulary_score
        WHERE timestamp >= :startOfDay AND timestamp < :endOfDay
        """
    )
    suspend fun getTodayScore(startOfDay: Long, endOfDay: Long): Int

    /** 获取某本词库的总积分 */
    @Query("SELECT COALESCE(SUM(score_delta), 0) FROM vocabulary_score WHERE vocab_id = :vocabId")
    suspend fun getScoreByVocab(vocabId: Long): Int

    /** 某个时间点之后拿到的积分（学习总结里算「本轮 +N 分」） */
    @Query("SELECT COALESCE(SUM(score_delta), 0) FROM vocabulary_score WHERE timestamp >= :since")
    suspend fun getScoreSince(since: Long): Int

    /** 获取所有积分记录（最新的在前） */
    @Query("SELECT * FROM vocabulary_score ORDER BY timestamp DESC")
    fun getAllScores(): Flow<List<VocabularyScoreEntity>>

    /** 获取某本词库的积分记录 */
    @Query("SELECT * FROM vocabulary_score WHERE vocab_id = :vocabId ORDER BY timestamp DESC")
    fun getScoresByVocab(vocabId: Long): Flow<List<VocabularyScoreEntity>>

    /** 获取某类型的积分记录 */
    @Query("SELECT * FROM vocabulary_score WHERE event_type = :eventType ORDER BY timestamp DESC")
    fun getScoresByType(eventType: String): Flow<List<VocabularyScoreEntity>>

    /** 获取今日的积分记录 */
    @Query(
        """
        SELECT * FROM vocabulary_score
        WHERE timestamp >= :startOfDay AND timestamp < :endOfDay
        ORDER BY timestamp DESC
        """
    )
    fun getTodayScores(startOfDay: Long, endOfDay: Long): Flow<List<VocabularyScoreEntity>>

    /** 检查某个里程碑是否已触发过（连续天数、学完词数这类只触发一次） */
    @Query(
        """
        SELECT COUNT(*) > 0
        FROM vocabulary_score
        WHERE event_type = 'milestone' AND description = :milestoneDesc
        """
    )
    suspend fun hasMilestone(milestoneDesc: String): Boolean

    /** 补记（[com.example.vocabulary.data.entity.ScoreEventType.BACKFILL]）跑过没有 */
    @Query("SELECT COUNT(*) > 0 FROM vocabulary_score WHERE event_type = 'backfill'")
    suspend fun hasBackfill(): Boolean

    /** 获取最近 N 天每天的积分 */
    @Query(
        """
        SELECT DATE(timestamp / 1000, 'unixepoch', 'localtime') as date,
               SUM(score_delta) as daily_score
        FROM vocabulary_score
        WHERE timestamp >= :startTime
        GROUP BY date
        ORDER BY date DESC
        """
    )
    suspend fun getDailyScores(startTime: Long): List<DailyScore>

    /** 删除某本词库的所有积分记录（词库被彻底删除时用） */
    @Query("DELETE FROM vocabulary_score WHERE vocab_id = :vocabId")
    suspend fun deleteByVocab(vocabId: Long)
}

/** 每日积分统计（用于可视化） */
data class DailyScore(
    val date: String,
    val daily_score: Int
)
