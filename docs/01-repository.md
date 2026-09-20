# 01 · 业务逻辑层（Repository）

这一层把 `core/` 的查询和算法串成完整流程：建书、导 CSV、出题、判卡、发积分、生成进度快照。

依赖：`Context` + 5 个 DAO + `SharedPreferences`。约 1100 行。

---

## 职责

| 干什么 | 什么时候跑 |
|---|---|
| 把随 App 发的词库落地到数据库和文件目录 | 首次启动 / 版本变化 |
| 解析并导入用户选的 CSV | 用户点「导入」 |
| 按模式取出要过的卡 | 开始学一组 |
| 判卡：更新掌握度、排下次复习、发积分 | 每判一次 |
| 统计：今日已学、待复习、每本进度 | 主页和详情页 |
| 生成进度快照文本 | AI 对话、系统提示词注入 |

---

## 一、内置词库怎么落地

如果 App 不带词库（比如开源版就是空的），这段可以直接跳过。要带的话：

```kotlin
/**
 * 内置词库清单。加一本就在这儿加一条，再往 assets 里放好 CSV。
 * assetPath 是这本书的身份（数据库里也存它），改路径等于换了一本书。
 */
private val BUILTIN_BOOKS = listOf(
    BuiltinBook(
        assetPath = "vocabulary/cet4.csv",
        name = "四级词汇",
        description = "大学英语四级词汇",
        hasUnit = false,
        audioAssetDir = null,      // 没有朗读音频就写 null
        audioVersion = 0,
        contentVersion = 1,        // 改了 CSV 的内容就 +1
    ),
)

suspend fun ensureImported() {
    for (spec in BUILTIN_BOOKS) {
        // 判重看的是「行在不在」，不是「内容对不对」。
        // ⚠️ 所以查的是 getAllVocabularies()（连软删除的一起看）——
        // 用户把这本书软删了，行还在，这里就该跳过；
        // 用 getVisibleVocabularies() 的话会以为「没导过」又导一份回来。
        val existing = vocabularyDAO.getAllVocabularies().first()
            .find { it.assetPath == spec.assetPath }

        if (existing == null) {
            // 彻底删过的书别再导回来（行已经没了，查不到，只能靠 SharedPreferences 记的记号）
            if (spec.assetPath in purgedBuiltins()) continue
            importBuiltin(spec)
        } else if (existing.contentVersion < spec.contentVersion) {
            // 只刷内容列，学习进度一列都不动
            refreshContent(existing, spec)
            vocabularyDAO.update(existing.copy(contentVersion = spec.contentVersion))
        }
    }
}
```

### ⚠️ 两个必须做的事

**1. 「彻底删除」要留记号。**

软删除（打 `deletedAt` 时间戳）能挡住重复导入，因为行还在。但**彻底删除会把行真删掉**，
下次启动 `ensureImported()` 就查不到了，会把这本书又导一遍。所以彻底删除时要把 assets 路径
记进 `SharedPreferences`：

```kotlin
suspend fun purgeBook(vocabId: Long) {
    vocabularyDAO.getVocabularyById(vocabId)?.let { book ->
        if (book.source == VocabularyEntity.SOURCE_BUILTIN) {
            book.assetPath?.let { setPurged(it, true) }   // 记一笔
        }
        vocabularyDAO.delete(book)
    }
    // 音频目录跟着清
    File(File(context.filesDir, AUDIO_DIR), vocabId.toString()).deleteRecursively()
}
```

**2. 内容刷新要幂等。**

`refreshContent` 按 `word` 匹配已有卡片、只改内容列（释义/音标/例句/词性）。
这样重复跑没有代价，所以 `contentVersion` 故意可以从 0 开始 —— 下次启动跑一遍就补上了。

---

## 二、音频怎么放

音频走**文件目录**，不走 `file:///android_asset`：

```kotlin
/** 音频落在 filesDir 下的目录名，里面按词库 id 再分一层子目录 */
private const val AUDIO_DIR = "vocabulary_audio"

private fun ensureAudioDir(bookId: Long, assetDir: String, version: Int) {
    val root = File(context.filesDir, AUDIO_DIR)
    root.mkdirs()

    // 按词库 id 分一层子目录：不同词库可能有同名音频文件，平铺会互相覆盖
    val target = File(root, bookId.toString())
    val stamp = File(target, ".version")

    // 版本没变就跳过，别每次启动几百个文件抄一遍
    if (target.isDirectory && stamp.exists() && stamp.readText().trim() == version.toString()) return

    target.deleteRecursively()
    target.mkdirs()
    copyAssetDir(assetDir, target)
    stamp.writeText(version.toString())
}

fun audioFileOf(card: VocabularyCardEntity): File? {
    val name = card.audio ?: return null
    val file = File(File(File(context.filesDir, AUDIO_DIR), card.vocabId.toString()), name)
    return file.takeIf { it.isFile }
}
```

**为什么不用 `MediaPlayer.create(context, Uri.parse("file:///android_asset/..."))`**：
那依赖「aapt 打包时不会压缩 mp3」这个隐含前提。`aaptOptions.noCompress` 一改、或者换个
AGP 版本，音频就打不开了，而且报错跟音频本身没关系，很难查。落到 `filesDir` 就绕开了这个前提。

> 用 CSV 导入的词库没有音频（CSV 带不了一整个音频目录）。想补的话看
> [04-pitfalls.md](04-pitfalls.md) 最后一节。

---

## 三、判卡：这里最容易写错

```kotlin
suspend fun answer(
    card: VocabularyCardEntity,
    answerType: Int,
    graduated: Boolean,      // 这一组里攒够次数了吗
): VocabularyCardEntity {
    val now = System.currentTimeMillis()

    if (!graduated) {
        // 组内还没过完：**只写答题记录，不动卡片状态**
        // 这样中途退出的话这个词仍然算「未学习」，下次还能拿到
        recordDAO.insert(StudyRecordEntity(/* ... */))
        return card
    }

    // 攒够了 → 正式记一笔
    val oldMastery = card.mastery
    val wasLearned = card.learned

    val newMastery = when (answerType) {
        ANSWER_KNOW -> (card.mastery + 1).coerceAtMost(MAX_MASTERY)
        ANSWER_FUZZY -> (card.mastery - 1).coerceAtLeast(0)
        else -> (card.mastery - 2).coerceAtLeast(0)
    }
    val retired = answerType == ANSWER_KNOW && card.mastery >= MAX_MASTERY   // ⚠️ 判断的是旧值

    val updated = card.copy(
        mastery = newMastery,
        learned = true,
        nextReviewDate = if (retired) null else now + INTERVALS_MINUTES[newMastery] * 60_000L,
        updatedAt = now,
    )
    cardDAO.update(updated)
    recordDAO.insert(StudyRecordEntity(/* ... */))

    // 发积分（下面细说）
    if (!wasLearned && updated.learned) awardLearned(card.vocabId, updated)
    if (newMastery > oldMastery) awardPromoted(card.vocabId, updated, oldMastery, newMastery)
    if (retired) awardGraduated(card.vocabId, updated)
    if (wasLearned && answerType == ANSWER_KNOW) awardReviewed(card.vocabId, updated)

    return updated
}
```

### 三个容易错的地方

**① `!graduated` 时不能改卡片状态。**
如果组内每判一次都写 `mastery`，那用户学到一半退出，这个词的掌握度已经涨了、`learned` 也成
`true` 了 —— 下次它就不再是「未学习」，永远拿不到第二次、第三次。必须只在攒够次数时才落状态。

**② `retired` 判断的是旧掌握度。**
`mastery >= MAX_MASTERY`（旧值）**并且**这次答对 → 毕业。如果拿新值判断，
`newMastery` 被 `coerceAtMost` 限制在 `MAX_MASTERY`，永远满足不了条件，毕业就永远触发不了。

**③ `markMastered` 不写答题记录。**

```kotlin
suspend fun markMastered(card: VocabularyCardEntity): VocabularyCardEntity {
    val updated = card.copy(
        learned = true,
        mastery = MAX_MASTERY,
        nextReviewDate = null,      // 终态跟复习到毕业的词一模一样
        updatedAt = System.currentTimeMillis(),
    )
    cardDAO.update(updated)
    awardMastered(card.vocabId, updated)
    return updated
}
```
「已掌握」是「我本来就会」，不是「我学了」。写答题记录的话这个词会算进「今日已学」
和「首答正确」，指标就假了。

---

## 四、积分怎么发

```kotlin
private object ScoreRule {
    const val LEARNED = 3       // 学会一词
    const val PROMOTED = 2      // 掌握度提档
    const val GRADUATED = 5     // 毕业
    const val MASTERED = 2      // 熟词标记
    const val REVIEWED = 1      // 复习
}

private suspend fun awardLearned(vocabId: Long, card: VocabularyCardEntity) {
    scoreDAO.insert(
        VocabularyScoreEntity(
            vocabId = vocabId,
            eventType = ScoreEventType.LEARNED,
            scoreDelta = ScoreRule.LEARNED,
            description = "学会单词 ${card.word}",
            cardId = card.id,
        )
    )
    checkMilestones(vocabId)
}
```

### 里程碑：描述文本是唯一键

```kotlin
private suspend fun checkStreakMilestones() {
    when (calculateStreak()) {          // ⚠️ when 不是 if >=
        3 -> awardMilestoneIfNew("连续学习 3 天", Milestone.STREAK_3_DAYS)
        7 -> awardMilestoneIfNew("连续学习 7 天", Milestone.STREAK_7_DAYS)
        30 -> awardMilestoneIfNew("连续学习 30 天", Milestone.STREAK_30_DAYS)
    }
}

private suspend fun awardMilestoneIfNew(desc: String, delta: Int) {
    if (!scoreDAO.hasMilestone(desc)) {     // 靠 description 查重
        scoreDAO.insert(VocabularyScoreEntity(
            vocabId = null,
            eventType = ScoreEventType.MILESTONE,
            scoreDelta = delta,
            description = desc,
        ))
    }
}
```

**为什么用 `when` 不用 `if (streak >= 3)`**：`>=` 的话连续第 4 天会**再发一次**「3 天」那份
（因为第 4 天的 `hasMilestone("连续学习 3 天")` 是 true，不会重发…… 但连续 7 天时会同时满足
`>= 3` 和 `>= 7`，逻辑分支就乱了）。用 `when` 精确匹配，一个天数只对应一档，不会互相干扰。

**⚠️ 描述文本上线后不能改**：查重靠它，改了等于所有人都能再领一次。
所以 `ScoreRules.MilestoneText` 里把这些字符串集中放着，别散在代码里。

**连续天数怎么算**（从今天往回数，断一天就停）：

```kotlin
private suspend fun calculateStreak(): Int {
    var streak = 0
    var checkDate = LocalDate.now()
    while (true) {
        val start = checkDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = start + 24 * 60 * 60 * 1000L
        val studied = books.first().any { recordDAO.countWordsInRange(it.id, start, end) > 0 }
        if (!studied) break
        streak++
        checkDate = checkDate.minusDays(1)
    }
    return streak
}
```

---

## 五、统计：注意「一天」怎么切

```kotlin
/** 今天学了多少个词（一张卡反复判也只算一个词） */
suspend fun countToday(vocabId: Long): Int {
    val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val endOfDay = startOfDay + 24 * 60 * 60 * 1000L
    return recordDAO.countWordsInRange(vocabId, startOfDay, endOfDay)
}
```

⚠️ **用 `LocalDate.now().atStartOfDay(系统时区)`，不要用「当前时间减 24 小时」。**
减 24 小时的话，用户凌晨 1 点看「今日已学」会把昨天下午的算进来。

「一张卡反复判也只算一个词」靠 SQL 里的 `COUNT(DISTINCT card_id)` 实现（见 `core/` 的 DAO）。

### 主页汇总：一条 SQL 拿全部

`VocabularyCardDAO.statsByBook(now)` 一次查完所有词库的进度，按 `vocab_id` 分组：

```kotlin
suspend fun statsByBook(now: Long): Map<Long, BookStatsRow> =
    cardDAO.statsByBook(now).associateBy { it.vocabId }
```

⚠️ **这个查询不能做成 Flow。** 里面的 `due`（到点该复习的）依赖你传进去的 `now`，
时间过去了 Room 不会重新发。要刷新得外面定时重查：

```kotlin
private fun startWatchingHomeStats() {
    viewModelScope.launch {
        while (true) {
            refreshHomeStats()
            delay(60_000)      // 一分钟重算一次
        }
    }
}
```

---

## 六、进度快照（给 AI 看的那份）

```kotlin
/** 一句话讲清当前进度，给系统提示词注入和 AI 对话共用 */
fun StudySnapshot.toBriefText(): String = buildString {
    appendLine("正在背的词书：$bookName（共 $total 个词，已掌握 $mastered，正在学 $studying）")
    if (todayCount == 0) appendLine("今天还没开始")
    else appendLine("今天已学 $todayCount 个，其中 $firstTry 个是第一次就答对的")
    if (dueCount > 0) appendLine("有 $dueCount 个到点该复习了")
}
```

**两个口径要定下来，不然两处会不一致：**

1. **多本词库时报哪本** —— 报 `lastStudiedAt` 最新那本，不要全报（提示词会爆长）
2. **「今天没记住的词」要不要带上** —— 带上很有用（AI 能针对性讲解），但它是**很长的列表**，
   而且每次都喂过去既费 token，也会让 AI 一直揪着这几个词不放。我们的选择是：
   **AI 对话带上，主窗口注入不带**。

```kotlin
fun StudySnapshot.toPromptText(includeShakyWords: Boolean = false): String
fun StudySnapshot.toBriefText(includeShakyWords: Boolean = false): String
```

---

## 七、CSV 导入

解析和校验在 `core/` 的 `CsvParser` 里，这一层只管读文件、入库、报错：

```kotlin
suspend fun importCsvFromUri(uri: Uri, name: String): Result<Long> = withContext(Dispatchers.IO) {
    runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("读不到这个文件")

        when (val parsed = CsvParser.parse(text)) {
            is ParseResult.Failure -> error(parsed.message)     // 消息可以直接给用户看
            is ParseResult.Success -> insertBook(
                name = name.trim().ifBlank { "导入的词库" },
                description = "导入的词库 · ${parsed.rows.size} 词",
                hasUnit = parsed.hasUnit,
                source = VocabularyEntity.SOURCE_IMPORTED,
                records = parsed.rows,
            )
        }
    }
}
```

⚠️ **选文件时 mime 传 `*/*`，不要传 `text/csv`。**
各家文件管理器给 `.csv` 报的类型很杂（`text/comma-separated-values`、`application/vnd.ms-excel`、
干脆 `application/octet-stream`），限死 `text/csv` 会导致用户看得见文件但点不动。

---

## 八、软删除

删除只打时间戳，不动数据：

```kotlin
/** 软删除：单词、进度、答题记录全留着，随时能连进度一起恢复 */
suspend fun deleteBook(vocabId: Long) {
    val book = vocabularyDAO.getVocabularyById(vocabId) ?: return
    val now = System.currentTimeMillis()
    vocabularyDAO.update(book.copy(deletedAt = now, updatedAt = now))
}

/** 从「已删除词库」捡回来，进度接着用 */
suspend fun restoreBook(vocabId: Long) {
    val book = vocabularyDAO.getVocabularyById(vocabId) ?: return
    vocabularyDAO.update(book.copy(deletedAt = null, updatedAt = System.currentTimeMillis()))
}
```

**为什么不做真删除**：用户误删一本书，进度是几十上百小时。留个「已删除」页能捡回来，
成本只是一个 `Long?` 字段。内置书和导入的书一视同仁 —— 用户不关心这书哪来的。

---

## 九、注入依赖

我们的版本用 Koin：

```kotlin
single {
    VocabularyRepository(
        context = androidContext(),
        vocabularyDAO = get(),
        cardDAO = get(),
        recordDAO = get(),
        sessionDAO = get(),
        scoreDAO = get(),
    )
}
```

用 Hilt 的话就是一个 `@Singleton` + `@Inject constructor`。

> ⚠️ **不要给 ViewModel 的构造函数塞 `Application` 或 `Context`。**
> 我们踩过这个坑，三种写法全崩（详见 [04-pitfalls.md](04-pitfalls.md)）。
> Repository 拿 Context 没问题（它是单例），ViewModel 拿就不行 —— 要 Context 就在
> Composable 里拿 `LocalContext` 传进去。

---

下一篇：[02-session-queue.md](02-session-queue.md) —— 一轮里的队列怎么组织。
