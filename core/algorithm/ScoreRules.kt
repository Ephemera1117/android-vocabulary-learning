package com.example.vocabulary.data.algorithm

/**
 * 积分规则。改数值改这里。
 *
 * ⚠️ 这套数值是「砍过一轮」的版本。第一次设计时给的是现在的三倍左右，
 * 结果一本一千多词的词书全学完能到五六万分，数字大到没有意义。
 * 现在的量级：一本 1256 词的词书全学完大约两万分。
 */
object ScoreRules {

    // ------------------------------------------------------------ 基础积分

    /** 学会一个词（learned 从 false 变 true） */
    const val LEARNED = 3

    /** 掌握度提档（每升一档给一次） */
    const val PROMOTED = 2

    /** 毕业（顶档再认对一次，退出复习） */
    const val GRADUATED = 5

    /** 标记熟词（手动点「已掌握」） */
    const val MASTERED = 2

    /** 复习一次（已学过的词再次答对） */
    const val REVIEWED = 1

    // ------------------------------------------------------------ 里程碑

    /**
     * 连续学习天数。⚠️ 只在**正好等于**这个天数时发一次，不是 `>=`。
     * 比如连续 4 天不会补发「3 天」那份。
     */
    const val STREAK_3_DAYS = 10
    const val STREAK_7_DAYS = 30
    const val STREAK_30_DAYS = 150

    /** 累计学完多少个词（按已掌握的卡片数算） */
    const val LEARNED_100 = 30
    const val LEARNED_500 = 150

    /** 一本词库完全掌握 */
    const val BOOK_COMPLETE = 300

    // ------------------------------------------------------------ 里程碑文案

    /**
     * 里程碑的**描述文本就是唯一键** —— 发过没发过靠它查重（见
     * `VocabularyScoreDAO.hasMilestone`）。所以这些字符串一旦上线就不能随便改，
     * 改了等于所有用户都能再领一次。
     */
    object MilestoneText {
        const val STREAK_3 = "连续学习 3 天"
        const val STREAK_7 = "连续学习 7 天"
        const val STREAK_30 = "连续学习 30 天"
        const val LEARNED_100 = "学完 100 个单词"
        const val LEARNED_500 = "学完 500 个单词"

        /** 词库全掌握。⚠️ 要拼上书名，所以是按书各算一次 */
        fun bookComplete(bookName: String) = "完全掌握词库：$bookName"
    }
}
