package com.example.vocabulary.data.algorithm

/**
 * 词库 CSV 的解析与校验。纯字符串处理，不碰 Android。
 *
 * ## 格式
 *
 * 第一行必须是列名，逗号分隔。认这几列（大小写和前后空格都不敏感）：
 *
 * | 列名 | 必填 | 说明 |
 * |---|---|---|
 * | `word` | 是 | 单词。空白的行会被丢掉 |
 * | `translation` | 是 | 释义，显示在卡片正面 |
 * | `ipa` | 否 | 音标，卡片第 1 轮显示 |
 * | `example` | 否 | 例句，它是第 2 轮的提示 |
 * | `unit` | 否 | 单元/课次。有一行填了，这本词库就带单元划分 |
 * | `pos` | 否 | 词性（中文，如「名词」） |
 * | `audio` | 否 | **一律忽略** —— 音频得是随 App 发的资源目录，一份 CSV 带不了 |
 *
 * 认不出来的列名忽略、不报错。引号规则是标准 CSV：字段里有逗号/换行/引号就用 `"` 包起来，
 * 字段内的 `"` 写成 `""`。
 *
 * **容错**：第一行里没有 `word` 这一列，就当作「没写列名」，
 * 按 `word, ipa, translation, example, unit, pos, audio` 这个固定顺序硬认。
 */
object CsvParser {

    /** CSV 里认得的列。除了 word，其余缺了就当空字符串 */
    val COLUMNS = listOf("word", "ipa", "translation", "example", "unit", "pos", "audio")

    /** 按这个顺序找列，第一行没有列名的话就按这个顺序硬认 */
    const val COLUMN_HINT = "word, ipa, translation, example, unit, pos, audio"

    /**
     * 解析一份词库 CSV。
     *
     * @param text 文件全文（UTF-8）
     */
    fun parse(text: String): ParseResult {
        val withHeader = parseWithHeader(text)
        val hasHeader = withHeader.firstOrNull()?.containsKey("word") == true
        val records = if (hasHeader) withHeader else parsePositional(text)

        if (records.isEmpty()) return ParseResult.Failure("这个文件里没有单词")

        val usable = records.filter { it["word"].orEmpty().isNotBlank() }
        if (usable.isEmpty()) {
            return ParseResult.Failure("没找到有效单词。第一行要么写列名，要么按 $COLUMN_HINT 这个顺序排")
        }

        // 有词没释义的表导进来没法背 —— 不要静默收下，直接说哪儿不对
        if (usable.none { !it["translation"].isNullOrBlank() }) {
            return ParseResult.Failure(
                if (hasHeader) "第一行缺少 translation 列，至少要有 word 和 translation 两列"
                else "第一行要写列名：至少 word 和 translation 两列"
            )
        }

        return ParseResult.Success(
            rows = usable,
            hasUnit = usable.any { !it["unit"].isNullOrBlank() },
        )
    }

    /**
     * 第一行当列名解析。返回的是**去掉表头之后**的数据行。
     * 列名做 trim + 小写，列数对不上的行直接丢掉。
     */
    fun parseWithHeader(text: String): List<CsvRow> {
        val rows = splitRows(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        return rows.drop(1)
            .filter { it.size == header.size }
            .map { values ->
                header.indices.associate { header[it].trim().lowercase() to values[it] }
            }
    }

    /**
     * 没有列名时的兜底：按固定列序硬认 [COLUMNS]。
     * 只写了两列的表格（word, translation）也按这个顺序读，读到的就是这两列。
     */
    fun parsePositional(text: String): List<CsvRow> {
        val rows = splitRows(text)
        if (rows.isEmpty()) return emptyList()
        return rows.map { values ->
            COLUMNS.take(values.size).withIndex().associate { (i, name) -> name to values[i] }
        }
    }

    /**
     * 把 CSV 正文切成一行行的字段列表。引号内的逗号和换行都不当分隔。
     *
     * 就是标准的 CSV 转义规则：`"` 包起来的字段里，`""` 表示一个字面量引号。
     */
    fun splitRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    // 两个连续引号 = 字段内的一个字面量引号
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }

                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }

                c == '"' -> inQuotes = true
                c == ',' -> {
                    row.add(field.toString())
                    field.clear()
                }

                c == '\n' -> {
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = mutableListOf()
                }

                // \r 直接丢掉（Windows 换行的 \r\n）
                c == '\r' -> Unit
                else -> field.append(c)
            }
            i++
        }

        // 最后一行没有换行结尾时补上
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}

/** 一行解析出来的字段。键是列名（小写），值是原始字符串 */
typealias CsvRow = Map<String, String>

/**
 * 解析结果。要么成功（[ParseResult.Success.rows] 非空），要么带着一句能直接给用户看的说明。
 */
sealed interface ParseResult {
    /** 解析成功。[hasUnit] 为 true 时详情页会出现「按课学习」 */
    data class Success(val rows: List<CsvRow>, val hasUnit: Boolean) : ParseResult

    /** 失败，[message] 是给用户看的说明 */
    data class Failure(val message: String) : ParseResult
}
