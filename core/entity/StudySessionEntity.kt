package com.example.vocabulary.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次学习会话。学完一组写一条，用来记录历史。
 *
 * [aiSummary] 是给 AI 写的评价留的位置（比如「今天这组记得不错，有三个词要盯一下」），
 * 如果不用 AI 功能，留空即可。
 */
@Entity(
    tableName = "study_session",
    foreignKeys = [
        ForeignKey(
            entity = VocabularyEntity::class,
            parentColumns = ["id"],
            childColumns = ["vocab_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["vocab_id"]),
        Index(value = ["start_time"])
    ]
)
data class StudySessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "vocab_id")
    val vocabId: Long,
    @ColumnInfo(name = "start_time")
    val startTime: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "end_time")
    val endTime: Long? = null,
    @ColumnInfo(name = "total_cards")
    val totalCards: Int = 0,
    @ColumnInfo(name = "correct_count")
    val correctCount: Int = 0,
    /** AI 对这次学习的评价，可选 */
    @ColumnInfo(name = "ai_summary")
    val aiSummary: String? = null,
)
