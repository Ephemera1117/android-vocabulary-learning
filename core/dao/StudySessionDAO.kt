package com.example.vocabulary.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.example.vocabulary.data.entity.StudySessionEntity

@Dao
interface StudySessionDAO {
    @Insert
    suspend fun insert(session: StudySessionEntity): Long

    @Update
    suspend fun update(session: StudySessionEntity)

    @Query("SELECT * FROM study_session WHERE vocab_id = :vocabId ORDER BY start_time DESC LIMIT :limit")
    fun getRecentSessions(vocabId: Long, limit: Int): Flow<List<StudySessionEntity>>

    @Query("SELECT * FROM study_session WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): StudySessionEntity?
}
