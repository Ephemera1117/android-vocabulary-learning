// 把 core/ 的 5 个 Entity 和 5 个 DAO 注册进你自己的 AppDatabase。
//
// 这个文件是「示例」，不是可以直接编译的完整文件 —— 换成你自己的包名、
// 你自己的数据库版本号和迁移策略。

package com.example.yourapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.AutoMigration
import com.example.vocabulary.data.entity.StudyRecordEntity
import com.example.vocabulary.data.entity.StudySessionEntity
import com.example.vocabulary.data.entity.VocabularyCardEntity
import com.example.vocabulary.data.entity.VocabularyEntity
import com.example.vocabulary.data.entity.VocabularyScoreEntity
import com.example.vocabulary.data.dao.StudyRecordDAO
import com.example.vocabulary.data.dao.StudySessionDAO
import com.example.vocabulary.data.dao.VocabularyCardDAO
import com.example.vocabulary.data.dao.VocabularyDAO
import com.example.vocabulary.data.dao.VocabularyScoreDAO

@Database(
    entities = [
        // ---- 你原有的表 ----
        // YourExistingEntity::class,

        // ---- 背单词的 5 张表 ----
        VocabularyEntity::class,
        VocabularyCardEntity::class,
        StudyRecordEntity::class,
        StudySessionEntity::class,
        VocabularyScoreEntity::class,
    ],
    version = 2,                       // ⚠️ 改成你的下一个版本号
    exportSchema = false,
    autoMigrations = [
        // 纯新增表，Room 能自动迁移。如果是往已有的表上加列，
        // 带 default 值的也能自动处理；要回填数据就得挂 spec（见 04-pitfalls.md 第 6 条）
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    // ---- 你原有的 DAO ----
    // abstract fun yourDao(): YourDao

    // ---- 背单词的 5 个 DAO ----
    // ⚠️ 一个一个列出来，别指望有什么批量注册的写法。
    // 漏了哪个，编译能过，但运行时依赖注入会解析不到 → 进页面即崩。
    abstract fun vocabularyDAO(): VocabularyDAO
    abstract fun vocabularyCardDAO(): VocabularyCardDAO
    abstract fun studyRecordDAO(): StudyRecordDAO
    abstract fun studySessionDAO(): StudySessionDAO
    abstract fun vocabularyScoreDAO(): VocabularyScoreDAO
}
