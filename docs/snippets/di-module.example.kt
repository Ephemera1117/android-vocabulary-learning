// 依赖注入注册示例（Koin 版）。用 Hilt 的话对应改成 @Module + @Provides。
//
// ⚠️ 两个必须注意的点：
//   1. DAO 和 Repository 都要注册 —— 只注册一个，运行时才崩（编译能过）
//   2. Repository 拿 Context 没问题（单例），**ViewModel 拿 Context 会崩**
//      （详见 docs/04-pitfalls.md 第 13 条）

package com.example.yourapp.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import com.example.vocabulary.data.repository.VocabularyRepository
import com.example.vocabulary.data.study.StudySessionStore
import com.example.vocabulary.data.study.StudyStore
import com.example.vocabulary.data.study.StudyTutorGenerator

val vocabularyModule = module {

    // ---------------------------------------------------------------- DAO
    // ⚠️ 逐个列举。漏一个 → InstanceCreationException → App 进安全模式
    single { get<AppDatabase>().vocabularyDAO() }
    single { get<AppDatabase>().vocabularyCardDAO() }
    single { get<AppDatabase>().studyRecordDAO() }
    single { get<AppDatabase>().studySessionDAO() }
    single { get<AppDatabase>().vocabularyScoreDAO() }

    // ---------------------------------------------------------------- 存储
    single { StudySessionStore(androidContext()) }   // 会话持久化，DataStore
    single { StudyStore(androidContext()) }          // 设置、AI 对话历史

    // ---------------------------------------------------------------- 数据层
    single {
        VocabularyRepository(
            context = androidContext(),      // ✓ Repository 是单例，拿 Context 没问题
            vocabularyDAO = get(),
            cardDAO = get(),
            recordDAO = get(),
            sessionDAO = get(),
            scoreDAO = get(),                // ⚠️ 加了新参数别忘了这里
        )
    }

    // ---------------------------------------------------------------- AI（可选）
    // 只在你做 AI 对话时才需要
    single { StudyTutorGenerator(providerManager = get(), vocabularyRepository = get()) }

    // ---------------------------------------------------------------- ViewModel
    // ⚠️ ViewModel 只注入 Repository / Store，**不要注入 Context 或 Application**
    viewModel { com.example.yourapp.ui.study.StudyVM(get(), get(), get()) }
    viewModel { com.example.yourapp.ui.study.TeacherChatVM(get(), get(), get(), get(), get()) }
}
