package com.example.vocabulary.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单词卡片。一个词一行，归属某本词库。
 *
 * 状态由两列决定：
 * - [learned] —— 是不是「学过」（攒够次数出师了）。只碰过一次不算
 * - [nextReviewDate] —— 下次该复习的时间。`learned=true` 且它是 null 表示已毕业
 */
@Entity(
    tableName = "vocabulary_card",
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
        Index(value = ["next_review_date"])
    ]
)
data class VocabularyCardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "vocab_id")
    val vocabId: Long,
    @ColumnInfo(name = "word")
    val word: String,
    @ColumnInfo(name = "translation")
    val translation: String,
    @ColumnInfo(name = "ipa")
    val ipa: String = "",
    @ColumnInfo(name = "example")
    val example: String = "",
    /** 词性：名词/动词/形容词/副词/介词… */
    @ColumnInfo(name = "pos", defaultValue = "")
    val pos: String = "",
    /** assets 里的相对路径，如 vocabulary/audio/word_0001.mp3 */
    @ColumnInfo(name = "audio")
    val audio: String? = null,
    /** 单元/课次。只要有一行填了，这本词库就带单元划分 */
    @ColumnInfo(name = "unit")
    val unit: String? = null,
    /** 掌握度档位，0-MAX_MASTERY。下标对应复习间隔表 */
    @ColumnInfo(name = "mastery")
    val mastery: Int = 0,
    /** 攒够次数、真正学过了；只碰过一次不算 */
    @ColumnInfo(name = "learned", defaultValue = "0")
    val learned: Boolean = false,
    /** null 且 learned=false 表示从没学过；learned=true 时表示已毕业 */
    @ColumnInfo(name = "next_review_date")
    val nextReviewDate: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
