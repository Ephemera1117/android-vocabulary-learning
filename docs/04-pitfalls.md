# 04 · 踩坑总结

每一条都是**实机出过问题**才记下来的，照着能绕过我们走过的弯路。
如果你时间不多，**只看这一篇**。

---

## 一、数据层

### 1. 加一个 DAO 要改**两处**，只改一处编译能过、运行时崩

给 `AppDatabase` 加了抽象方法之后，还得在依赖注入的地方把它注册上。

**症状**：编译通过，装完点进相关页面直接崩，App 落到安全模式页。
异常是 `InstanceCreationException: Could not create instance for '[Factory: '...StudyVM']'`。

**为什么难查**：这台设备上 `logcat` 读不到。但**安全模式页的「崩溃报告」里有完整堆栈**
（`uiautomator dump` 能拿到），比日志好用 —— 以后遇到「进页面即崩」，先去那个页面拿堆栈。

**同类**：Repository 的构造参数也多了一个（`scoreDAO = get()`），
**两处都要跟着改**。

---

### 2. 汇总数字要**轮询重算**，不能只挂 Room 的 Flow

主页「每本书学了多少」第一版是这么写的：

```kotlin
launch { repository.books.collect { books -> /* 在这里算每本书的统计 */ } }
```

`repository.books` 是 **`vocabulary` 表**的 Flow。可**学词改的是 `vocabulary_card` 表** ——
那张表真的变了，这个 Flow 不重发，数字就**冻在进学习页之前那一份**。

**改法**：一条聚合查询 + 定时重算。

```kotlin
homeStatsJob = viewModelScope.launch {
    while (true) { refreshHomeStats(); delay(2000) }
}
```

**判断标准**：

| 这个数字跟什么有关 | 用什么 |
|---|---|
| 只跟某一张表的行有关 | Flow 可以 |
| 跟「现在几点」有关（比如「待复习」） | **必须轮询** —— 时间过去了 Room 不会发通知 |
| 跟另一张表有关 | **必须轮询**（或者挂两张表的 Flow 一起算） |

聚合 SQL 一次把所有词库的 `total / newCount / studying / due / mastered` 查出来
（`VocabularyCardDAO.statsByBook(now)`），别按词库循环发 N 条查询。

---

### 3. 异步写库 + 立刻读 → **少最后一条**

判卡那里，`repository.answer()` 挂在 `launch` 里，**外面**又写了 `if (finished) finish()` ——
`finished` 是判卡**之前**算的，写库还没返回就出了总结页。
结果「本轮 +N 分」永远少最后一个词（实测该 +5 只给 +2）。

**改法**：把 `finish()` 挪进 `launch` 里，等 `answer()` 返回之后再判、再出页。

**判断标准**：只要「读」的东西依赖刚写进去的行，读就必须在**同一个协程里排在写之后**。

---

### 4. 排序别用 `created_at`

批量导入词表时，1000 多张卡写的是**同一个时间戳**，`ORDER BY created_at` 的排序键全相等，
实际顺序变成靠 rowid 蒙对 —— 今天看着是对的顺序，换个 SQLite 版本可能就变了。

**改法**：`ORDER BY id`。导入顺序 = id 顺序 = 课本顺序。

---

### 5. 软删除要挡住「内置词库自动导入」

`ensureImported()` 判重看的是「行在不在」。软删除只打时间戳、行还在 → 天然挡住 ✓

但**彻底删除会把行真删掉**，下次启动就又导一份回来。所以彻底删除时要额外记一笔：

```kotlin
if (book.source == VocabularyEntity.SOURCE_BUILTIN) {
    book.assetPath?.let { setPurged(it, true) }   // 记进 SharedPreferences
}
```

⚠️ 判重时查的必须是 `getAllVocabularies()`（**连软删除的一起看**），
用 `getVisibleVocabularies()` 的话软删除的书会被当成「没导过」。

---

### 6. 新增 NOT NULL 列必须写 `defaultValue`，而且要用字面量

```kotlin
@ColumnInfo(name = "learned", defaultValue = "0")     // ✓ 字面量
val learned: Boolean = false

@ColumnInfo(name = "on_homepage", defaultValue = SOME_CONST)   // ✗ 引用常量
val onHomepage: Boolean = false
```

引用同一个类 companion 里的 `const val` 会让 Room 的注解处理器犯难。
字符串字面量就行。

**另外**：加完列有时还要在迁移**之后回填数据**。比如加「是否显示在主页」这个开关时，
如果只给默认值 0，升级完主页是空的 —— 用户得重新点进每本书学一次。要挂 `onPostMigrate`
把「已经学过单词的词库」回填成 1：

```kotlin
@AutoMigration(from = 34, to = 35, spec = Migration_34_35::class)
@Database(/* ... */)
```

```kotlin
@RenameColumn.Entries()
class Migration_34_35 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            UPDATE vocabulary SET on_homepage = 1
            WHERE id IN (SELECT DISTINCT vocab_id FROM vocabulary_card WHERE learned = 1)
        """)
    }
}
```

---

## 二、Compose / UI

### 7. `remember` 里的状态会丢，放 ViewModel

场景：用户在「导入词库」里选好了 CSV 文件，然后切出去接了个电话 —— 回来 URI 没了，
导入按钮点了没反应（还不报错）。

**根因**：把 URI 存在了 `remember { mutableStateOf<Uri?>(null) }` 里。
Compose 的 `remember` 生命周期跟着 composition 走，某些情况下（进程被杀、
某些系统弹窗导致的重组）会被重建。

**判断标准**：**所有「用户选过但还没提交」的状态一律放 ViewModel。**

```kotlin
// ViewModel 里
private val _pendingImportUri = MutableStateFlow<Uri?>(null)
val pendingImportUri: StateFlow<Uri?> = _pendingImportUri.asStateFlow()
```

**另外**：如果 App 有应用锁之类会拆掉 composition 的机制（用户离开再回来时整棵 Compose 树
被重建），`remember` 丢得更勤。这类应用里更要坚持放 ViewModel。

---

### 8. `remember(list)` **不监听列表内部对象的变化**

```kotlin
// ✗ 列表里对象的字段变了，这里不会重算
val text = remember(messages) { buildText(messages) }

// ✓ 加个 hashCode 参与比较
val text = remember(messages, messages.hashCode()) { buildText(messages) }
```

`remember(key)` 比的是 **`key == key`**，而 `List` 的 `equals` 是**逐元素**比的 ——
看起来应该能检测到内部变化。但实际踩到的场景是：列表本身是**同一个对象**（比如
`StateFlow` 里那个 List 引用没换），或者元素的 `equals` 因为是 data class 而只比部分字段。

**症状**：工具调用后某段文字消失、界面不刷新。修了这个地方**三次**才发现真因在这。

**判断标准**：`remember` 的 key 是集合时，**带上 `hashCode()`**，别赌它。

---

### 9. `weight()` 容器会把固定内容压扁

把内容塞进带 `weight()` 的容器里时，如果那个容器是纵向排列且空间不够，
固定高度的内容会被压成 0 尺寸 —— **看不见，但代码没报错**。

**查法**：先量 bounds（`uiautomator dump`），别先怀疑代码逻辑。尺寸是 0 就说明是被压扁了。

---

### 10. UI 文案：别写「说明书腔」

不是技术坑，但**同一个错我们犯了两次**，记下来。

**规律**：写确认框时习惯写「会发生什么 + 后果」，觉得是在帮用户判断。
但用户要的是**一句话说清是什么事**，不解释机制、不预告后果。

| | 写法 |
|---|---|
| ✗ | 「删除之后就找不回来了。」 |
| ✓ | 「确定删除？」 |
| ✗ | 「导入会把框里现在的内容整个换掉，换完没法撤销。」 |
| ✓ | 「用「xxx.md」覆盖？」 |

**自查四条**：

1. 能不能**一句话**说完 —— 文件名这类信息塞进标题，就别再开正文
2. 有没有在**解释 App 自己怎么运作**（「会把…换掉」「算作…」）→ 删
3. 有没有在**预告后果**（「没法撤销」「就找不回来了」）→ 删
4. 按钮用**动词**（覆盖／删除／取消），别用「确定/好的」这种没信息量的

---

## 三、Android / 构建

### 11. Kotlin 的块注释**会嵌套**

`/* ... */` 在 Kotlin 里是**可嵌套**的：注释里出现 `/*` 就开始一层嵌套，
得再配一个 `*/` 才闭合。

写注释时提到 `text/*` 这种 MIME 通配，或者贴一段带 `/* */` 的代码，
**会把后面的代码全吃进注释里**，而报错报在**文件末尾**的 `Unclosed comment` ——
让你以为末尾那行有问题，其实错在上面几百行。

**绕开**：注释里别写 `/*`。

---

### 12. 音频别依赖 `file:///android_asset`

```kotlin
// ✗ 依赖「打包工具不压缩 mp3」这个隐含行为
MediaPlayer.create(context, Uri.parse("file:///android_asset/vocabulary/audio/word_0001.mp3"))

// ✓ 启动时复制到 filesDir，之后走普通文件
private const val AUDIO_DIR = "vocabulary_audio"
```

`file:///android_asset` 能播 mp3 是依赖「aapt 不压缩 mp3」这个隐含前提。
换个 AGP 版本、改一下 `androidResources.noCompress`，音频就打不开了 ——
而且报错跟音频本身没关系，很难查。

**另外**：音频要**按词库分目录**（`filesDir/vocabulary_audio/<词库id>/`）。
不同词库可能有同名文件，平铺在一个目录里会互相覆盖。

---

### 13. 不要给 ViewModel 的构造函数塞 `Context` / `Application`

踩过，**三种写法全崩**：

```kotlin
// ✗ 全都不行
class StudyVM(private val app: Application) : AndroidViewModel(app)
class StudyVM(private val context: Context) : ViewModel()
```

**症状**：Koin 抛 `InstanceCreationException`，App 进安全模式。

**为什么**：ViewModel 的生命周期跟 Application 不一致，注入框架没法安全地持有它。
（用 `AndroidViewModel` 是官方支持的例外，但在 Koin 的注册方式下仍然会崩。）

**正确做法**：

- **Repository 拿 Context 没问题** —— 它是单例，活到进程结束
- **ViewModel 里要 Context** → 在 Composable 里拿 `LocalContext.current` 传进去

```kotlin
// Composable 里
val context = LocalContext.current
Button(onClick = { vm.importCsv(context, uri) }) { /* ... */ }
```

---

### 14. 后台保活：给 ViewModel 加 Application 作用域也会崩

想给日历/学习页加「切后台不断连」时试过给 ViewModel 挂 `AppScope` / `Application` ——
同样是 Koin 崩。**已经全部撤回。**

要走这条路的话，用**前台 Service**，不要动 ViewModel 的依赖结构。

---

### 15. 选文件时 mime 传 `*/*`

```kotlin
// ✗ .csv 在各家文件管理器里报的类型很杂，限死会导致文件可见但点不动
ActivityResultContracts.OpenDocument()   // 配合 arrayOf("text/csv")

// ✓
ActivityResultContracts.OpenDocument()   // 配合 arrayOf("*/*")
```

`text/csv`、`text/comma-separated-values`、`application/vnd.ms-excel`、
`application/octet-stream`……各家都不一样。放开之后在解析阶段挡二进制文件就行。

---

## 四、两个「想清楚再动手」

### 16. 「听懂了」和「真记住了」是两件事

不要因为用户点了「认识」就认为这个词掌握了 —— 一组里认对 3 次才算。
反过来，也不要因为用户点了一次「忘记」就把掌握度清零 —— 退 1-2 档就够。

**这一条影响的是产品而不是代码**：判卡的三个按钮（认识/模糊/忘记）对应
+1 / 不变 / 清零，这个比例是反复调过的，别随手改成 +1/-1/-1。

---

### 17. 分数不要给太松

第一版积分是现在的三倍（学会 +10、提档 +5、毕业 +20…）。
结果一本 1256 词的词书全学完能到**五六万分**，数字大到没意义，用户不会在乎。

砍到三分之一之后（学会 +3、提档 +2、毕业 +5），一本 1256 词的词书学完约 **2 万分** ——
「两万分」这个量级用户能感知到「我在积累」。

**判断标准**：积分总量应该让用户看到**五位数的增长**，而不是六位数。

---

## 相关文档

- [01-repository.md](01-repository.md) —— 业务逻辑层
- [02-session-queue.md](02-session-queue.md) —— 会话队列
- [03-ai-teacher.md](03-ai-teacher.md) —— AI 对话
