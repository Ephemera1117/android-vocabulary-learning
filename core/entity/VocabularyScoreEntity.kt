package com.example.vocabulary.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 积分记录。
 *
 * 每次触发积分事件（学会词、提档、毕业、熟词标记、复习、里程碑等）都写一条记录，
 * 把 `score_delta` 加起来就是总分。
 *
 * 汇总一律**按需 SUM**，不存冗余的计数列。
 */
@Entity(
    tableName = "vocabulary_score",
    foreignKeys = [
        ForeignKey(
            entity = VocabularyEntity::class,
            parentColumns = ["id"],
            childColumns = ["vocab_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("vocab_id"),
        Index("timestamp"),
        Index("event_type")
    ]
)
data class VocabularyScoreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 哪本词库触发的积分（里程碑类积分可能是汇总所有词库的，此时为 null） */
    @ColumnInfo(name = "vocab_id")
    val vocabId: Long?,

    /** 事件类型，见 [ScoreEventType] */
    @ColumnInfo(name = "event_type")
    val eventType: String,

    /** 本次积分变化（正数加分） */
    @ColumnInfo(name = "score_delta")
    val scoreDelta: Int,

    /** 描述文本，例如「学会单词 apple」「连续学习 7 天」 */
    @ColumnInfo(name = "description")
    val description: String,

    /** 关联的卡片 ID（如果是单词相关事件） */
    @ColumnInfo(name = "card_id")
    val cardId: Long? = null,

    /** 触发时间戳 */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

/** 积分事件类型 */
object ScoreEventType {
    /** 学会一个词（learned 变成 true） */
    const val LEARNED = "learned"

    /** 掌握度提档（mastery 上升） */
    const val PROMOTED = "promoted"

    /** 毕业（掌握度到顶再认对一次） */
    const val GRADUATED = "graduated"

    /** 熟词标记（手动标成「已掌握」） */
    const val MASTERED = "mastered"

    /** 复习（已学过的词再次答对） */
    const val REVIEWED = "reviewed"

    /** 里程碑（连续天数、学完词数、词库完成等） */
    const val MILESTONE = "milestone"

    /**
     * 补记。如果积分功能是后加的，之前学过的词没有记录，
     * 升级后可以按**当前卡片状态**倒推一笔补上（每词：学会 + 每档熟练度 + 毕业/熟词）。
     * 每次复习的加分无法回溯，不补。
     */
    const val BACKFILL = "backfill"
}
