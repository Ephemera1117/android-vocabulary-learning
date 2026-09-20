package com.example.vocabulary.data.algorithm

/**
 * 掌握度与复习时间的计算。纯函数，不碰数据库。
 *
 * 一组学完（攒够 [StudyConstants.LEARN_PASSES] 次认对）之后才走这里：
 * 判一次卡 → 掌握度加减一档 → 按新档位排下次复习。
 */
object MasteryCalculator {

    /** 一次判卡的结果 */
    data class Outcome(
        /** 新的掌握度档位 */
        val mastery: Int,
        /** 是否毕业（到顶档还认对，以后不再复习） */
        val retired: Boolean,
        /** 下次复习时间戳；毕业的话是 null */
        val nextReviewDate: Long?,
    )

    /**
     * 按判卡结果算新状态。
     *
     * 加减档：
     * - 认识 **+1**
     * - 模糊 **-1**
     * - 忘记 **-2**
     *
     * ⚠️ 答错是**退档**不是归零 —— 学了很久的词答错一次不至于从头再来。
     *
     * @param mastery 判卡前的掌握度
     * @param answerType [StudyConstants.ANSWER_KNOW] / [ANSWER_FUZZY] / [ANSWER_FORGOT]
     * @param now 当前时间戳（毫秒）
     */
    fun apply(mastery: Int, answerType: Int, now: Long): Outcome {
        val max = StudyConstants.MAX_MASTERY

        val newMastery = when (answerType) {
            StudyConstants.ANSWER_KNOW -> (mastery + 1).coerceAtMost(max)
            StudyConstants.ANSWER_FUZZY -> (mastery - 1).coerceAtLeast(0)
            else -> (mastery - 2).coerceAtLeast(0)
        }

        // 注意判断的是**旧**档位：已经在顶档还认对，才算毕业
        val retired = answerType == StudyConstants.ANSWER_KNOW && mastery >= max

        return Outcome(
            mastery = newMastery,
            retired = retired,
            nextReviewDate = if (retired) null else nextReviewDate(newMastery, now),
        )
    }

    /** 按档位算下次复习时间。`INTERVALS_MINUTES[mastery]` 分钟之后 */
    fun nextReviewDate(mastery: Int, now: Long): Long {
        val minutes = StudyConstants.INTERVALS_MINUTES[mastery.coerceIn(0, StudyConstants.MAX_MASTERY)]
        return now + minutes * 60_000L
    }

    /**
     * 标记「已掌握」（本来就会的词）。
     *
     * 落的终态跟复习到毕业的词一模一样：学过 + 掌握度到顶 + 没有下次复习。
     * 走这条路**不算「学了」这个词**，所以不写答题记录、不进「今日已学」的统计。
     */
    fun mastered(now: Long): Outcome = Outcome(
        mastery = StudyConstants.MAX_MASTERY,
        retired = true,
        nextReviewDate = null,
    )
}
