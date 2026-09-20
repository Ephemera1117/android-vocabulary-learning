# 核心数据层

背单词模块的数据层。**只依赖 Room 注解**，不碰 Android SDK 的业务部分，任何 Android 项目都能直接用。

- 你的项目用 Room → 抄过去就能读写数据
- 你想自己写 UI 和业务逻辑 → 这一层是现成的
- 你的项目不是 Rikkahub → 这一层跟它没有任何关系

> ⚠️ 这段代码是从一个实际项目里提取的。它在那个场景下跑得没问题，但不保证覆盖了所有边界情况，
> 也可能有更合适的写法。发现问题欢迎反馈。

---

## 包含什么

```
core/
├── entity/            5 个表定义
│   ├── VocabularyEntity.kt        词库（一本书一行）
│   ├── VocabularyCardEntity.kt    单词卡片
│   ├── StudyRecordEntity.kt       答题记录
│   ├── StudySessionEntity.kt      学习会话
│   └── VocabularyScoreEntity.kt   积分记录
├── dao/               5 个查询接口
│   ├── VocabularyDAO.kt
│   ├── VocabularyCardDAO.kt       出题、统计的主要查询都在这
│   ├── StudyRecordDAO.kt
│   ├── StudySessionDAO.kt
│   └── VocabularyScoreDAO.kt
└── algorithm/         4 个纯逻辑文件（零依赖）
    ├── StudyConstants.kt          学习机制的参数：认对几次、间隔表、掌握度上限
    ├── MasteryCalculator.kt       掌握度与复习时间计算
    ├── ScoreRules.kt              积分规则
    └── CsvParser.kt               CSV 解析与校验
```

---

## 怎么用

### 1. 复制文件

三个目录整个拷到你的项目，然后**把包名换成你自己的**（下面按 `com.example.vocabulary` 写的）：

```bash
cp -r core/entity/*   你的项目/src/main/java/<你的包>/data/entity/
cp -r core/dao/*      你的项目/src/main/java/<你的包>/data/dao/
cp -r core/algorithm/* 你的项目/src/main/java/<你的包>/data/algorithm/
```

包名要改三处：每个文件第一行的 `package`、文件里的 `import`。用 IDE 的
「Refactor → Rename Package」一次改完最省事。

依赖：

```kotlin
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")   // Flow 和 suspend 支持
ksp("androidx.room:room-compiler:2.6.1")
```

### 2. 注册到 AppDatabase

```kotlin
@Database(
    entities = [
        VocabularyEntity::class,
        VocabularyCardEntity::class,
        StudyRecordEntity::class,
        StudySessionEntity::class,
        VocabularyScoreEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vocabularyDAO(): VocabularyDAO
    abstract fun vocabularyCardDAO(): VocabularyCardDAO
    abstract fun studyRecordDAO(): StudyRecordDAO
    abstract fun studySessionDAO(): StudySessionDAO
    abstract fun vocabularyScoreDAO(): VocabularyScoreDAO
}
```

数据库已经存在的话，这些表要**加进现有的 `AppDatabase`**，版本号 +1 并写迁移。
Room 的 `AutoMigration` 对「新增表」是能自动处理的，参考下面「迁移」一节。

### 3. 开始用

建书 → 灌词：

```kotlin
val bookId = vocabularyDAO.insert(
    VocabularyEntity(name = "四级词汇", description = "4544 词", hasUnit = false)
)

vocabularyCardDAO.insertAll(
    listOf(
        VocabularyCardEntity(vocabId = bookId, word = "abandon", translation = "放弃", ipa = "ə'bændən"),
        VocabularyCardEntity(vocabId = bookId, word = "abruptly", translation = "突然地", ipa = "ə'brʌptli"),
    )
)
```

出题：

```kotlin
// 学新词：还没学过的
val newCards = vocabularyCardDAO.getNewCards(bookId, limit = StudyConstants.DEFAULT_SESSION_SIZE)

// 复习：到日子该复习的
val dueCards = vocabularyCardDAO.getReviewCards(bookId, System.currentTimeMillis(), limit = 10)
```

判卡：

```kotlin
val outcome = MasteryCalculator.apply(
    mastery = card.mastery,
    answerType = StudyConstants.ANSWER_KNOW,
    now = System.currentTimeMillis(),
)

vocabularyCardDAO.update(
    card.copy(
        mastery = outcome.mastery,
        learned = true,
        nextReviewDate = outcome.nextReviewDate,
        updatedAt = System.currentTimeMillis(),
    )
)

studyRecordDAO.insert(
    StudyRecordEntity(
        vocabId = card.vocabId,
        cardId = card.id,
        isCorrect = true,
        answerType = StudyConstants.ANSWER_KNOW,
    )
)
```

导一份 CSV：

```kotlin
when (val result = CsvParser.parse(csvText)) {
    is ParseResult.Success -> {
        vocabularyCardDAO.insertAll(
            result.rows.map { row ->
                VocabularyCardEntity(
                    vocabId = bookId,
                    word = row["word"].orEmpty(),
                    translation = row["translation"].orEmpty(),
                    ipa = row["ipa"].orEmpty(),
                    example = row["example"].orEmpty(),
                    unit = row["unit"]?.takeIf { it.isNotBlank() },
                    pos = row["pos"].orEmpty(),
                )
            }
        )
    }
    is ParseResult.Failure -> showError(result.message)   // 可以直接显示给用户
}
```

### 4. 测试

`algorithm/` 里的四个文件没有任何 Android 依赖，可以直接在 JVM 单元测试里跑：

```kotlin
class MasteryCalculatorTest {
    @Test
    fun `到顶档再认对就毕业`() {
        val outcome = MasteryCalculator.apply(
            mastery = StudyConstants.MAX_MASTERY,
            answerType = StudyConstants.ANSWER_KNOW,
            now = 0L,
        )
        assertTrue(outcome.retired)
        assertNull(outcome.nextReviewDate)
    }
}
```

---

## 学习机制是什么样

理解数据字段之前先理解这套机制，否则会看不懂 `learned` / `mastery` / `nextReviewDate` 三个字段的关系。

### 一个词怎么算「学过」

在一组里**认对 3 次**才算学过（`StudyConstants.LEARN_PASSES`）。这 3 次看到的内容逐轮减少：

1. 第 1 次：音标 + 释义 + 例句
2. 第 2 次：只给例句
3. 第 3 次：只有单词本身

答错会**退档**而不是清零：模糊退 1 档、忘记退 2 档，然后这个词排回队里继续出现。

次数没攒够就退出的话，这个词仍然是「未学习」——下次开始学习还会拿到它。

### 学过之后怎么复习

`mastery`（掌握度，0-7）决定下次复习隔多久：

| mastery | 间隔 |
|---|---|
| 0 | 4 小时 |
| 1 | 12 小时 |
| 2 | 1 天 |
| 3 | 3 天 |
| 4 | 7 天 |
| 5 | 15 天 |
| 6 | 30 天 |
| 7 | 60 天 |

判卡时掌握度 **认识 +1 / 模糊 -1 / 忘记 -2**（都不超过 0-7 的范围）。

**毕业**：mastery 已经是 7 档时再认对一次，`nextReviewDate` 置为 `null`，以后不再进复习队列。
所以 `nextReviewDate IS NULL AND learned = 1` 就等于「已掌握」。

### 三个字段怎么配合

| learned | nextReviewDate | 意思 |
|---|---|---|
| `false` | `null` | 没学过（或者学了一半还没出师） |
| `true` | 有时间 | 学过，等时间到了复习 |
| `true` | `null` | 已掌握，不再出现 |

---

## 积分怎么算

| 事件 | 分 |
|---|---|
| 学会一个词 | +3 |
| 掌握度提档（每升一档） | +2 |
| 毕业 | +5 |
| 标记熟词 | +2 |
| 复习一次 | +1 |

里程碑（**只发一次**，靠 `description` 字段查重）：连续 3 天 +10 / 7 天 +30 / 30 天 +150；
学完 100 词 +30 / 500 词 +150；一本词库全掌握 +300。

数值都在 `ScoreRules` 里，想改直接改。⚠️ 里程碑的**描述文本是唯一键**，
上线之后就别改了 —— 改了等于给所有人再发一次。

总分一律**按需 SUM**（`VocabularyScoreDAO` 里都是 `SUM(score_delta)`），不存冗余的计数列。

---

## 迁移

如果你是在现有数据库上加这些表，参考这几个 AutoMigration 的写法。Room 对新增表是能自动处理的：

```kotlin
@Database(
    entities = [/* ...含上面的 5 个表... */],
    version = 2,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AppDatabase : RoomDatabase() { /* ... */ }
```

如果新加了带默认值的列（`@ColumnInfo(defaultValue = "...")` 是必须的，NOT NULL 列不能没有默认值），
有时还需要在迁移**之后**回填数据——比如给「已经学过的词库」把某个新开关打开，
否则升级完界面会是空的。那种情况要用 `AutoMigration` 的 `spec` 参数挂一个 `onPostMigrate`。

---

## 不包含什么

这一层**没有**：

- UI（没有 Compose，也没有 XML 布局）
- ViewModel（没有状态管理）
- **学习会话的队列逻辑** —— 「一组出几个词、这个词再排回队里隔几个位置、一屏最多卡几次」
  这些跑在上一层的 ViewModel 里。`StudyConstants` 里给了这些参数（`DEFAULT_SESSION_SIZE` /
  `REQUEUE_GAP` / `MAX_ATTEMPTS_PER_CARD`），但组织队列的代码在集成教程那一层
- Repository（没有业务逻辑封装 —— 建书、导 CSV、判卡、发积分这些流程串起来的那一层）
- 内置词库的落地逻辑（assets 读取、音频复制）
- AI 对话

需要完整功能的话，看上一层的集成教程。

---

## 许可

AGPL-3.0（继承上游项目的许可）。
