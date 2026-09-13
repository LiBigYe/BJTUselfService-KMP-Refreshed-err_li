package team.bjtuss.bjtuselfservice.shared.data.home

import team.bjtuss.bjtuselfservice.shared.data.homework.parseLenientJsonObject
import team.bjtuss.bjtuselfservice.shared.data.homework.string
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeStatus

sealed interface HomeStatusParseResult {
    data class Success(val status: HomeStatus) : HomeStatusParseResult

    /**
     * 整份响应不可用（不是 JSON 对象，或三个字段全缺）。
     * 单个字段缺失不算失败，见 [parseHomeStatusJson]。
     */
    data class Failure(val field: String) : HomeStatusParseResult
}

/**
 * 解析 MIS 首页状态。
 *
 * 旧实现要求 `newmail_count` / `ecard_yuer` / `net_fee` 同时存在且非空，任一字段缺失
 * 就把三个值全部丢弃——用户看到的是校园卡和校园网余额同时变成 “—”，即使服务器
 * 只是没返回其中一个字段。这里改为逐字段降级：缺失或空串只让对应卡片显示未知，
 * 其余字段照常展示。只有整份响应不是 JSON 对象、或三个字段全都取不到时才判失败。
 *
 * 正文用宽松解析：MIS 老接口可能在 JSON 之后追加内容或带 UTF-8 BOM。
 */
fun parseHomeStatusJson(body: String): HomeStatusParseResult {
    val root = parseLenientJsonObject(body) ?: return HomeStatusParseResult.Failure("root")
    val mail = root.string("newmail_count").orEmpty().trim()
    val card = root.string("ecard_yuer").orEmpty().trim()
    val network = root.string("net_fee").orEmpty().trim()
    if (mail.isBlank() && card.isBlank() && network.isBlank()) {
        return HomeStatusParseResult.Failure("fields")
    }
    return HomeStatusParseResult.Success(HomeStatus(mail, card, network))
}
