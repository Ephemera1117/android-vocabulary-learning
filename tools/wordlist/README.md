# 词表转换器

把公开的英语词表转成「App」能导入的 CSV。

**词书本身不进这个仓库**，所以这里给的是一条自己动手生成的路：跑一下脚本、把生成的 CSV
传到手机、在 App 里导入就行，**不需要重新编译 App**。

## 需要什么

Node 18 或更新（自带 `fetch`，不用装依赖）。检查一下：

```bash
node --version
```

## 最快的一次

```bash
node build-wordlist.mjs --book 四级 --out cet4.csv
```

然后把 `cet4.csv` 传到手机上（数据线、聊天软件、网盘都行），打开 App：

**单词 → 右上角「导入」→ 选中那个 CSV → 起个名字 → 导入。**

## 有哪些词书

```bash
node build-wordlist.mjs --list
```

四级 / 六级 / 考研 / 托福 / SAT / GRE / 专四 / 专八 / 高中 / 初中 / 人教系列。

## 常用选项

| 选项 | 作用 |
|---|---|
| `--out <路径>` | 输出文件，默认是 `<词书>.csv` |
| `--units <数字>` | 每 N 个词切一个单元，App 里就会出现「按课学习」。例：`--units 100` |
| `--limit <数字>` | 只要前 N 个词（试试效果的时候用，省得等下载） |
| `--no-example` | 不要例句列，文件小一点 |
| `--file <路径>` | 不联网，转自己的表格（`.jsonl` / `.json` / `.csv`） |

```bash
# 每 100 词一个单元，六级
node build-wordlist.mjs --book 六级 --units 100 --out cet6.csv

# 不要例句的考研词表
node build-wordlist.mjs --book 考研 --no-example --out kaoyan.csv

# 转自己整理的表格
node build-wordlist.mjs --file my-words.csv --out my-words-out.csv
```

## 生成出来的 CSV 长什么样

```csv
word,ipa,translation,example,unit,pos,audio
abruptly,ə'brʌptli,突然地,The path ends off abruptly.,,副词,
```

| 列 | 必填 | 说明 |
|---|---|---|
| `word` | 是 | 单词 |
| `translation` | 是 | 释义，显示在卡片正面 |
| `ipa` | 否 | 音标，卡片第 1 轮显示 |
| `example` | 否 | 例句。**它是第 2 轮的提示，所以别把答案写进去** |
| `unit` | 否 | 单元/课次。只要有一行填了，这本词库就带单元划分 |
| `pos` | 否 | 词性（中文，如「名词」），显示成卡片上的小标签 |
| `audio` | 否 | 一律忽略 —— 音频得是随 App 发的资源目录，一份 CSV 带不了 |

第一行必须是列名；`word` 和 `translation` 必须有，其余列没有就空着。
大小写和空格不敏感，认不出来的列会被忽略。

## 数据从哪来

默认从 GitHub 上公开的
[KyleBing/english-vocabulary](https://github.com/KyleBing/english-vocabulary) 取，
它整理了常见考试词表，音标（美/英）、词性、例句都齐。
想换数据源的话，看 `build-wordlist.mjs` 顶部的 `SOURCE_BASE` 和 `fromSourceEntry()`。

## 自己整理表格的注意事项

- **存成 UTF-8 编码**，不然中文释义会乱码
- 第一行写列名
- 字段里如果有逗号、换行或引号，用 `"` 把整个字段包起来，字段内的 `"` 写成 `""`
- 只有两列也行（`word,translation`），但**列名那一行不能省**
