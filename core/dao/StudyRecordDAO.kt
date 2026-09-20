package com.example.vocabulary.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.example.vocabulary.data.entity.StudyRecordEntity

@Dao
interface StudyRecordDAO {
    @Insert
    suspend fun insert(record: StudyRecordEntity): Long

    @Query("SELECT * FROM study_record WHERE vocab_id = :vocabId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentRecords(vocabId: Long, limit: Int): Flow<List<StudyRecordEntity>>

    @Query("SELECT COUNT(*) FROM study_record WHERE vocab_id = :vocabId AND timestamp >= :startTime AND timestamp < :endTime")
    suspend fun countRecordsInRange(vocabId: Long, startTime: Long, endTime: Long): Int

    /** 今天学了多少个词（一张卡反复判也只算一个词） */
    @Query(
        "SELECT COUNT(DISTINCT card_id) FROM study_record " +
            "WHERE vocab_id = :vocabId AND timestamp >= :startTime AND timestamp < :endTime"
    )
    suspend fun countWordsInRange(vocabId: Long, startTime: Long, endTime: Long): Int

    /** 今天有几个词是第一次判就点「认识」的（首答正确） */
    @Query(
        "SELECT COUNT(*) FROM study_record r " +
            "WHERE r.vocab_id = :vocabId AND r.timestamp >= :startTime AND r.timestamp < :endTime " +
            "AND r.answer_type = 2 AND r.timestamp = (" +
            "  SELECT MIN(m.timestamp) FROM study_record m " +
            "  WHERE m.card_id = r.card_id AND m.vocab_id = r.vocab_id " +
            "  AND m.timestamp >= :startTime AND m.timestamp < :endTime)"
    )
    suspend fun countFirstTryInRange(vocabId: Long, startTime: Long, endTime: Long): Int

    /** 今天判过「忘记」或「模糊」的词（去重），给系统提示词注入和 AI 对话用 */
    @Query(
        "SELECT DISTINCT c.word FROM study_record r " +
            "JOIN vocabulary_card c ON c.id = r.card_id " +
            "WHERE r.vocab_id = :vocabId AND r.timestamp >= :startTime AND r.timestamp < :endTime " +
            "AND r.answer_type < 2 LIMIT :limit"
    )
    suspend fun getShakyWordsInRange(
        vocabId: Long,
        startTime: Long,
        endTime: Long,
        limit: Int,
    ): List<String>

    /** 这本书最早一条答题记录的时间（补记历史积分时当时间戳，免得算进「今日获得」） */
    @Query("SELECT MIN(timestamp) FROM study_record WHERE vocab_id = :vocabId")
    suspend fun firstRecordTime(vocabId: Long): Long?

    /** 所有词库加起来今天过了多少个词（card_id 全局唯一，直接 COUNT DISTINCT 就行） */
    @Query(
        "SELECT COUNT(DISTINCT card_id) FROM study_record " +
            "WHERE timestamp >= :startTime AND timestamp < :endTime"
    )
    suspend fun countWordsInRangeAll(startTime: Long, endTime: Long): Int
}
