package com.example.vocabulary.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 词库。一本书一行。
 *
 * 卡片、进度、答题记录都按 `vocab_id` 分开，所以多本词库并存不用额外加锁。
 */
@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "description")
    val description: String = "",
    @ColumnInfo(name = "has_unit")
    val hasUnit: Boolean = false,
    /** builtin = 随 App 内置的资源词库；imported = 用户自己导进来的 CSV */
    @ColumnInfo(name = "source", defaultValue = "builtin")
    val source: String = SOURCE_BUILTIN,
    /** 内置词库的 assets 路径，导入的是 null。也是「这本内置书导没导过」的凭据 */
    @ColumnInfo(name = "asset_path")
    val assetPath: String? = null,
    /** 这本词库的内容版本，跟内置清单里的版本号比对，决定要不要刷内容 */
    @ColumnInfo(name = "content_version", defaultValue = "0")
    val contentVersion: Int = 0,
    /** 最近一次开始学习的时间，注入和 AI 上下文按它挑「当前在学哪本」 */
    @ColumnInfo(name = "last_studied_at", defaultValue = "0")
    val lastStudiedAt: Long = 0,
    /**
     * 要不要在背单词主页的「正在学习」里显示。
     * 默认不显示；真开始学一本就自动打开，之后可以手动关掉。
     */
    @ColumnInfo(name = "on_homepage", defaultValue = "0")
    val onHomepage: Boolean = false,
    /**
     * 软删除时间。非 null = 躺在「已删除词库」里，单词和进度都还在，随时能恢复。
     * **内置词库也一样**：删了不销号，只是从列表里挪走，这样恢复能连进度一起找回。
     */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SOURCE_BUILTIN = "builtin"
        const val SOURCE_IMPORTED = "imported"
    }
}
