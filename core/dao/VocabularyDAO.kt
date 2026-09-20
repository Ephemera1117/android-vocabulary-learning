package com.example.vocabulary.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.example.vocabulary.data.entity.VocabularyEntity

@Dao
interface VocabularyDAO {
    @Insert
    suspend fun insert(vocabulary: VocabularyEntity): Long

    @Update
    suspend fun update(vocabulary: VocabularyEntity)

    @Delete
    suspend fun delete(vocabulary: VocabularyEntity)

    /** 列表页看的：没被删掉的 */
    @Query("SELECT * FROM vocabulary WHERE deleted_at IS NULL ORDER BY created_at DESC")
    fun getVisibleVocabularies(): Flow<List<VocabularyEntity>>

    /**
     * 内部判重用的：连软删除的一起看。
     * 内置词库的导入判重必须能看到软删除的书，否则会把它当「没导过」又导一份。
     */
    @Query("SELECT * FROM vocabulary ORDER BY created_at DESC")
    fun getAllVocabularies(): Flow<List<VocabularyEntity>>

    /** 「已删除词库」页看的 */
    @Query("SELECT * FROM vocabulary WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun getDeletedVocabularies(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE id = :id")
    suspend fun getVocabularyById(id: Long): VocabularyEntity?

    @Query("SELECT * FROM vocabulary WHERE id = :id")
    fun getVocabularyByIdFlow(id: Long): Flow<VocabularyEntity?>
}
