# 03 · AI 对话（可选模块）

学单词时开一个对话窗口，把当前进度和正在看的词喂给模型，让它讲解。

约 400 行，四个文件：生成器、ViewModel、界面、设置。**这一块是可选**——
不做 AI 对话，前面两节的东西照样是完整的背单词功能。

---

## 它做什么

- 一个**独立于主对话**的窗口
- 流式生成，**不挂任何工具**（所以一轮就完事，不需要工具循环）
- 上下文 = 学习进度快照 + 用户正在看的那张卡（如果是从卡片点进来的）
- 支持图片输入（用户拍课本照片发过来）
- 历史按**词库分开**，每条线内部再按「段」分

---

## 提示词怎么拼

```kotlin
private fun buildMessages(
    teacherSettings: TutorSettings,
    history: List<StudyMessage>,
    snapshotText: String?,
    focusCard: VocabularyCardEntity?,
): List<UIMessage> {
    val system = buildString {
        if (teacherSettings.systemPrompt.isNotBlank()) {
            appendLine(teacherSettings.systemPrompt)
            appendLine()
        }
        if (!snapshotText.isNullOrBlank()) {
            appendLine("## 当前学习进度")
            appendLine(snapshotText)
            appendLine()
        }
        focusCard?.let { card ->
            appendLine("## 正在看这个词")
            appendLine("- ${card.word}${if (card.pos.isNotBlank()) "（${card.pos}）" else ""}")
            if (card.ipa.isNotBlank()) appendLine("- 音标：${card.ipa}")
            appendLine("- 释义：${card.translation}")
            if (card.example.isNotBlank()) appendLine("- 例句：${card.example}")
            if (card.unit != null) appendLine("- 来自：${card.unit}")
        }
    }.trim()

    return buildList {
        if (system.isNotBlank()) add(UIMessage.system(system))
        history.takeLast(teacherSettings.historyLimit).forEach { message ->
            when (message.role) {
                StudyMessageRole.USER -> add(toUserMessage(message))
                StudyMessageRole.ASSISTANT -> add(UIMessage.assistant(message.content))
            }
        }
    }
}

/** 图片在前、文字在后 */
private fun toUserMessage(message: StudyMessage): UIMessage {
    if (message.attachments.isEmpty()) return UIMessage.user(message.content)
    val parts = message.attachments.map { UIMessagePart.Image(url = it) } +
        message.content.takeIf { it.isNotBlank() }?.let { listOf(UIMessagePart.Text(it)) }.orEmpty()
    return UIMessage(role = MessageRole.USER, parts = parts)
}
```

### 三个设计决定

**① 系统提示词完全交给用户配。**

`teacherSettings.systemPrompt` 是设置里的一栏，代码不带任何预设人格。
「这个窗口里的 AI 是谁」由用户自己的提示词决定 —— 可以是主对话里那个 AI 的延伸，
也可以是完全不同的人设，甚至可以是另一个模型。**代码不替用户定义这件事。**

**② 进度快照带上「今天没记住的词」。**

主对话的注入不带这个列表（太长、而且会让 AI 天天揪着那几个词），
但这个窗口是用户主动进来问的，带上很有用：

```kotlin
val snapshot = vocabularyRepository.snapshot()
snapshot?.toPromptText(includeShakyWords = true)
```

**③ 当前正在看的词单独列一块。**

用户从卡片点「问一下」进来时，把那张卡的完整信息（音标/释义/例句/单元）放进系统提示词。
这样用户可以直接问「这个词为什么用这个介词」，不用先把它复述一遍。

---

## 流式生成

不挂工具，所以很短：

```kotlin
fun reply(
    settings: Settings,
    teacherSettings: TutorSettings,
    history: List<StudyMessage>,
    focusCard: VocabularyCardEntity? = null,
    fallbackModelId: Uuid?,
): Flow<String> = flow {
    val modelId = teacherSettings.resolvedModelId() ?: fallbackModelId ?: error("未选择模型")
    val model = settings.providers.findModelById(modelId) ?: error("模型不存在")
    val provider = model.findProvider(settings.providers) ?: error("供应商不存在")
    val providerHandler = providerManager.getProviderByType(provider)

    val snapshot = runCatching { vocabularyRepository.snapshot() }.getOrNull()
    var messages = buildMessages(teacherSettings, history, snapshot?.toPromptText(true), focusCard)
    var reply = ""

    val streamChunkHandler = StreamChunkHandler(model)
    providerHandler.streamText(
        providerSetting = provider,
        messages = messages,
        params = TextGenerationParams(model = model),
    ).collect { chunk ->
        messages = streamChunkHandler.handle(messages, chunk)
        val text = sanitize(messages.lastOrNull()?.toText() ?: "")   // 见下
        if (text.isNotBlank() && text != reply) {
            reply = text
            emit(reply)
        }
    }
}.flowOn(Dispatchers.IO)
```

### ⚠️ 思考内容要在流式过程中就切掉

```kotlin
val text = sanitizeThinking(messages.lastOrNull()?.toText() ?: "")
```

`sanitize` 把 `<think>...</think>` 之类的推理内容剥掉。

`sanitizeThinking` 是个小函数，自己写一个就行：

```kotlin
private fun sanitizeThinking(text: String): String =
    text.replace(Regex("(?s)<think(?:ing)?>.*?</think(?:ing)?>"), "").trim()
```

**必须流式时就剥**，不能等生成完再处理 —— 否则用户会先看到一大段思考内容刷刷刷地出来，
然后突然消失，像是界面出了故障。

### ⚠️ 模型可以单独配

`teacherSettings.resolvedModelId() ?: fallbackModelId`：用户没单独设就退回主对话的模型。

值得单独配的原因：讲解单词这件事不需要最强的模型，
用一个便宜的模型做这个窗口是合理的省钱方式。

---

## 历史是分段的

「段」= 一个连续的对话上下文。这三种情况会开新的一段：

1. 从卡片点「问一下」进来（上下文变了）
2. 隔了一天再进这个窗口
3. 用户手动点「新段」

**为什么要分段**：如果所有历史堆在一条线里，一个月后这个窗口的上下文会变成
几百条无关的问答，「今天这个词怎么用」会被上周的内容淹没。分段让每次对话都在一个干净的上下文里。

历史按**词库**分开（每本书一条线），因为不同词库的学习是独立的心智空间。

---

## 这个模块**没有**什么

这是最重要的一节。

### ❌ 没有记忆系统

它**不记得**用户上次问了什么、哪个词反复问过、有哪些弱项。

**为什么不做**：完整的记忆系统跟主对话窗口深度耦合 ——
查询要用全文检索 + 触发词打分 + 疲劳机制，写入要走工具审批流程，
数据结构还绑定了主助手的 ID。把这套剥出来的成本远大于用户自己实现。

**这是有意的设计，不是没做完。** 这个窗口的职责是「讲解当前这个词」，
它拿到的上下文（进度快照 + 当前卡片）已经够干这件事了。

### ❌ 没有工具调用

不能查日历、不能搜索、不能写记忆。因为不挂工具，所以一轮就返回，
不需要主对话那套工具循环。

**想加的话**：把 `providerHandler.streamText` 的一次调用换成带工具循环的实现，
工具定义按你的需求挂。主对话（或不带 AI 的那个版本）里如果有现成的工具循环，
复用它的结构就行。

### ❌ 和主对话窗口不联动

两个窗口**不共享对话历史和记忆**。

- 在教师窗口问「这个词什么意思」，主对话不知道
- 在主对话说「我今天学了 20 个词」，教师窗口不会自动记住

**为什么**：职责分离 —— 学习是学习、日常聊天是日常聊天。分开能让用户学单词时
不被其他话题打断，在主对话说话时也不用顾虑「会不会打断它教我」。

主对话那边能看到**学习进度**（靠 `GenerationLoop` 里注入快照），
但那条路是单向的：主对话能知道用户在学什么，教师窗口不知道用户在聊什么。

---

## 想给这个窗口加记忆？三条路

如果确实需要，按实现成本从低到高：

### 方案 A：关键词捞历史（最简单，推荐先试）

历史本来就存着。用户问新问题时，从历史里捞出相关几条塞进系统提示词：

```kotlin
// 在 buildMessages 里加一段
val related = studyStore.teacherChatFlow(bookId).first()
    .messages
    .filter { it.content.contains(currentWord, ignoreCase = true) }   // 或换 BM25 / TF-IDF
    .takeLast(3)

if (related.isNotEmpty()) {
    appendLine("## 之前聊到过")
    related.forEach { appendLine("- ${it.content.take(100)}") }
}
```

成本几乎为零，效果一般但能解决「这个词我们上次说过」这类需求。

### 方案 B：本地向量检索

用 ONNX Runtime + 一个小嵌入模型（`all-MiniLM-L6-v2` 约 25MB），
把历史消息转成向量存进数据库，查询时按余弦相似度取 top-k。

- 代价：APK +35MB，首次生成向量要几秒（可以后台跑）
- 效果：能捞到语义相关但不含相同词的历史，比 A 好很多

### 方案 C：外挂记忆服务

本机跑一个记忆服务（比如 Mem0），App 走局域网 API。
出门在外时降级到方案 A。

代价是要维护一个服务，移动场景下不稳定，一般不值得。

---

## 接入点

| 要改什么 | 改哪 |
|---|---|
| 加记忆 | `buildMessages()` 里拼 `system` 那一段 |
| 挂工具 | 生成器里的 `streamText` 调用，换成工具循环 |
| 改上下文 | `snapshot()` 返回的字段 + `toPromptText()` |
| 换 AI Provider | `providerManager.getProviderByType()` 那一层，换成你自己的调用 |

---

下一篇：[04-pitfalls.md](04-pitfalls.md) —— 实机踩过的坑，**最省时间的部分**。
