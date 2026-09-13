package team.bjtuss.bjtuselfservice.shared.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 学校数据的解析（Ksoup / 严格 JSON / GB18030）、SQLite 读写与缓存编解码统一在这里执行。
 *
 * 这些调用本身都是同步阻塞的，而调用方几乎全部是 Compose 的 `LaunchedEffect` 和
 * `rememberCoroutineScope().launch`，也就是 Android 主线程与桌面 AWT EDT。留在调用方的
 * 调度器上，等于每次进页面和每次刷新都在 UI 线程解析整页 HTML 再写一次 SQLite，
 * 高刷屏上表现为明显的掉帧。
 *
 * 用 `Dispatchers.Default` 而不是平台 IO 调度器：解析本身就是 CPU 工作；本项目的
 * SQLite 只有单账号的课程/成绩/作业几张很小的表，不值得为此再加一层 expect/actual。
 * 已确认 Android、iOS、desktop 三端都能提供多线程的 `Dispatchers.Default`。
 */
internal val schoolWorkDispatcher: CoroutineDispatcher get() = Dispatchers.Default

/** 在 [schoolWorkDispatcher] 上执行阻塞式数据层工作，见该属性说明。 */
internal suspend fun <T> onSchoolWork(block: suspend () -> T): T =
    withContext(schoolWorkDispatcher) { block() }
