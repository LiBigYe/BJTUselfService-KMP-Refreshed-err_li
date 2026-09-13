package team.bjtuss.bjtuselfservice.shared

/**
 * 桌面平台识别结果。
 *
 * `currentPlatform()` 会在每个滚动容器的每次重组里被调用（见 `desktopTouchScroll`），
 * 现场读系统属性并拼接字符串属于纯浪费；`os.name` 在进程生命周期内不会变，缓存即可。
 */
private val desktopPlatformInfo: PlatformInfo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    PlatformInfo(
        family = PlatformFamily.MacOS,
        displayName = if (isWindows) {
            "Windows ${System.getProperty("os.version")}"
        } else {
            "macOS ${System.getProperty("os.version")}"
        },
        isWindows = isWindows,
    )
}

actual fun currentPlatform(): PlatformInfo = desktopPlatformInfo
