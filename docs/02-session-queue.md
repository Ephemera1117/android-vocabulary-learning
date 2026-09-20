# 02 · 一轮怎么组织（会话队列）

这一节是一组学习的**状态机**：什么算学过、答错怎么处置、什么时候出队。
`core/` 里给了参数（`StudyConstants`），组织队列的代码在 ViewModel 里 —— 就是这一节。

---

## 数据结构

```kotlin
/** 队列里的一张卡。除了卡片本身，还要记住这一组里它被认对了几次、被问了几次 */
data class SessionCard(
    val card: VocabularyCardEntity,
    val passes: Int = 0,      // 这一组里认对了几次
    val attempts: Int = 0,    // 这一组里一共判了几次
)

data class SessionState(
    val mode: StudyMode,                    // LEARN / REVIEW / UNIT
    val unit: String? = null,
    val total: Int,                         // 这一组计划过多少词（进度分母，开局定死）
    val queue: List<SessionCard>,           // 还没过的；first() 就是当前这张
    val done: List<SessionCard> = emptyList(),      // 攒够次数出队的
    val parked: List<SessionCard> = emptyList(),    // 判太多次先放过的
    val mastered: Int = 0,                  // 这一组里标成「已掌握」的词数
    val revealed: Boolean = false,          // 当前这张翻开答案了没有
    val startedAt: Long,
) {
    val current: SessionCard? get() = queue.firstOrNull()
    val finished: Boolean get() = queue.isEmpty()

    /** 这一组里要认对几次才算过 */
    val target: Int
        get() = if (mode == StudyMode.REVIEW) REVIEW_PASSES else LEARN_PASSES
}
```

---

## 判卡逻辑

```kotlin
fun answer(answerType: Int) {
    val session = currentSession() ?: return
    val current = session.current ?: return
    if (!session.revealed) return          // 没翻开答案不能判

    val passes = when (answerType) {
        ANSWER_KNOW -> current.passes + 1   // 认识 → 认对次数 +1
        ANSWER_FUZZY -> current.passes      // 模糊 → 次数不动
        else -> 0                           // 忘记 → 清零重来
    }
    val stepped = current.copy(passes = passes, attempts = current.attempts + 1)
    val rest = session.queue.drop(1)

    val graduated = passes >= session.target                                    // 攒够了
    val giveUp = !graduated && stepped.attempts >= MAX_ATTEMPTS_PER_CARD        // 卡太多次

    val advanced = when {
        // 出队
        graduated -> session.copy(queue = rest, done = session.done + stepped, revealed = false)

        // 先放过，这一组不再出现
        giveUp -> session.copy(queue = rest, parked = session.parked + stepped, revealed = false)

        // 隔几个词再排回来
        else -> {
            val at = minOf(REQUEUE_GAP, rest.size)
            session.copy(
                queue = rest.toMutableList().also { it.add(at, stepped) },
                revealed = false,
            )
        }
    }
    // ...更新 UI，然后异步写库
}
```

### 三种结果

| 判卡 | passes | 后果 |
|---|---|---|
| 认识 | **+1** | 攒够 `LEARN_PASSES`（3）就出队 |
| 模糊 | 不变 | 隔 4 个词再出现 |
| 忘记 | **清零** | 隔 4 个词再出现，之前的次数白攒 |

`attempts` 单独记：一张卡在一组里判满 `MAX_ATTEMPTS_PER_CARD`（4）次还没过，就先放过
（进 `parked`），免得用户被一个词卡死在这一组里出不去。

### 为什么「模糊」不加也不减

模糊是「想起来了但不确定」，给它算通过太松（用户会一路模糊过去），算失败太重
（其实记住了大半）。原地不动 = 这个词还会再来一次，但之前攒的次数不作废。

### 为什么「忘记」要清零

一组里认对 3 次的目的是让这个词在短期内被反复激活。如果忘记只减 1，
用户靠「认识、认识、忘记、认识」也能凑够 3 次通过 —— 但中间那次是彻底想不起来，
说明它没有真的记住。

---

## 排回队里为什么隔 4 个

```kotlin
val at = minOf(REQUEUE_GAP, rest.size)
queue = rest.toMutableList().also { it.add(at, stepped) }
```

- **不直接放队尾**：队尾意味着要过完这一组所有词才再见到它，中间隔了几分钟，
  等于做了一次「新鲜度测试」，效果差
- **不放到队首**：立刻再问一遍，答案是刚从眼前晃过去的短期记忆，通过了也不能说明什么
- **隔 3-4 个**：中间有别的词干扰一下，又还在同一个上下文里 —— 这个位置最接近
  「刚才那个词我记住了吗」的自然回忆

队列比 4 个还短（快过完了）就直接放队尾，`minOf` 处理这种情况。

---

## 每次判卡都要存盘

用户可能随时退出、App 可能被系统杀掉。所以每判一次就把整个会话状态落盘：

```kotlin
viewModelScope.launch {
    repository.answer(current.card, answerType, graduated = graduated)

    if (!advanced.finished) {
        persistSession(advanced)        // 一步一存
    } else {
        finish(advanced)                // 过完了 → 出总结页
    }
}
```

### ⚠️ `finish()` 必须在 `answer()` 返回之后

总结页要显示「本轮 +N 分」。这个 N 是**加「本轮开始之后的积分记录」**算出来的 ——
而积分是 `answer()` 写进去的。如果抢在写库之前算，就会**少最后一个词的分**
（实测该 +5 只给了 +2）。

**判断标准**：只要「读」的东西依赖刚写进去的行，读就必须在**同一个协程里排在写之后**。

### 恢复的时候要问一句

`persistSession` 存的会话下次启动读出来，**不要直接进学习页**，先弹个框问：

```
上次学到一半（第 7 / 10 个），继续吗？
```

因为用户可能已经忘了上次在干什么，直接扔回学习页会很突然。用户点「重新开始」的话
把存的那份丢弃就行 —— 已经判过的词仍然算「未学习」（`!graduated` 时不落卡片状态，
见 [01-repository.md](01-repository.md)），所以重新开始不会丢进度，只是这一组要重来。

---

## 「已掌握」按钮

```kotlin
fun markMastered() {
    val session = currentSession() ?: return
    val current = session.current ?: return

    val advanced = session.copy(
        queue = session.queue.drop(1),
        mastered = session.mastered + 1,
        revealed = false,
    )
    // 注意：不需要 revealed，随时可以点
    viewModelScope.launch {
        repository.markMastered(current.card)
        if (!advanced.finished) persistSession(advanced) else finish(advanced)
    }
}
```

三个决定：

1. **放在主按钮的下方**，做成灰色小字 —— 它是次要动作（「我本来就会」），
   跟「认识/模糊/不认识」不是一个层级
2. **不用翻开答案也能点** —— 用户看到单词就知道自己会，凭什么要先翻？
3. **点了立刻生效，没有二次确认** —— 加个确认框反而显得不信任用户

---

## 一组出多少词

```kotlin
const val DEFAULT_SESSION_SIZE = 10
```

可以做成用户可调（我们做成了 5 / 10 / 15 / 20 四档）。

⚠️ **「按课学习」也要受这个限制。** 一课可能有 141 个词，不限的话一次全压上来，
用户看到进度是「1 / 141」直接就退了。

```kotlin
fun startSession(book: VocabularyEntity, mode: StudyMode, unit: String? = null) {
    val limit = _sessionSize.value          // 按课学习也用这个值
    val cards = repository.loadCards(book.id, mode, unit, limit)
    if (cards.isEmpty()) {
        _notice.value = when (mode) {
            StudyMode.LEARN -> "这些都学完了"
            StudyMode.REVIEW -> "暂无需要复习的词"
            StudyMode.UNIT -> "本课无词汇"
        }
        return
    }
    // 开始学了就记一笔，AI 那边按它认「当前在学哪本」
    repository.touchBook(book.id)
    // ...
}
```

---

## 进度怎么显示

```kotlin
val answeredCount: Int get() = done.size + parked.size + mastered
val firstTryCount: Int get() = done.count { it.attempts == 1 }    // 一次就过的
val repeatCount: Int get() = done.count { it.attempts > 1 }       // 过了几遍才过的
```

总结页显示这几个数。「一遍就过」和「过了三遍才过」都算学会了，但前者说明这个词
对用户更简单 —— 把它单独列出来，用户能看出哪些词是硬骨头。

---

## 出总结页的时机

`SessionState.finished`（`queue` 空了）就是过完了。注意 `parked` 里的词也算过完
（它已经从 `queue` 里出去了），总结页要把它单独列出来：「有 2 个词这次没记住，下次还会出现」。

---

下一篇：[03-ai-teacher.md](03-ai-teacher.md) —— AI 对话。
