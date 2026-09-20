# 集成教程（需要 App 骨架的部分）

`core/` 那一层是纯数据层，抄过去就能用。这一层不一样 —— 它的代码跟**应用骨架**绑在一起：

- **Android Context**（读 assets、访问文件目录、读 SharedPreferences）
- **Room** 的 DAO（来自 `core/`）
- **DataStore**（会话持久化、设置）
- **Jetpack Compose + Material 3**（界面）
- **一个 AI Provider 抽象**（要做 AI 对话的话）

所以我们**不直接给完整源文件**，而是把每一块的职责、关键实现和坑讲清楚，配可以取用的代码片段。
你需要按自己的项目调整 —— 这件事绕不过去，因为这部分代码里跟骨架相关的部分本来就得改。

> 这套东西最初是在 [Rikkahub](https://github.com/rikkahub/rikkahub)（一个 Android AI 聊天前端）上二改的。
> 教程里提到的 API 名字（`SettingsStore` / `ProviderManager` / `Settings`）是那个项目的。
> 你的项目里对应的东西叫什么、长什么样，自己对应一下。

---

## 这一层包含什么

| 文档 | 讲什么 | 大概要看多久 |
|---|---|---|
| [01-repository.md](01-repository.md) | 业务逻辑层：怎么建书、导 CSV、判卡发积分、生成进度快照 | 20 分钟 |
| [02-session-queue.md](02-session-queue.md) | **一轮怎么组织** —— 认对 3 次、答错排回队里、卡太多次先放过 | 15 分钟 |
| [03-ai-teacher.md](03-ai-teacher.md) | AI 对话：上下文怎么拼、模型怎么调、**没有什么** | 15 分钟 |
| [04-pitfalls.md](04-pitfalls.md) | 实机踩过的坑，**最省时间的部分** | 10 分钟 |

代码片段在 [snippets/](snippets/)。

---

## 两种集成方式

### 方式 A：照搬

你的项目也是「Compose + Room + DataStore + Koin」那一套，而且不介意跟我们的结构一样 → 按文档和片段照着搭。

### 方式 B：只用思路

你的架构差得多（比如用 Hilt、或者不是 Compose）→ 把 `core/` 拿走，业务逻辑自己写，
文档用来看**有哪些非做不可的事**（比如判卡要等写库返回再出总结页，不然会少最后一个词）。

两种方式都建议先看 [04-pitfalls.md](04-pitfalls.md)。

---

## 依赖清单

```
core/                        5 个 Entity + 5 个 DAO + 4 个算法文件
├── Room 2.6+                数据层
├── DataStore                会话持久化、设置
├── Compose + Material 3     界面
└── 一个 AI Provider          仅 AI 对话需要
```

---

## 和 `core/` 的分工

```
core/          表结构、查询、算法常量     ← 抄走就能用
   ↓
这一层          Repository / VM / UI        ← 看教程自己接
   ↓
你的 App        导航、主题、AI Provider      ← 你的东西
```
