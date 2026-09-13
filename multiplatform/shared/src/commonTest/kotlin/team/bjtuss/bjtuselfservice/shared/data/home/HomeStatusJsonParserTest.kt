package team.bjtuss.bjtuselfservice.shared.data.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HomeStatusJsonParserTest {
    @Test fun parsesObservedStringShape() {
        val result = assertIs<HomeStatusParseResult.Success>(
            parseHomeStatusJson("""{"net_fee":"12.50","ecard_yuer":"88.00","newmail_count":"2"}"""),
        )
        assertEquals("2", result.status.newMailCount)
        assertEquals("88.00", result.status.campusCardBalance)
        assertEquals("12.50", result.status.networkBalance)
    }

    @Test fun acceptsJsonNumbersWithoutChangingDisplayText() {
        val result = assertIs<HomeStatusParseResult.Success>(
            parseHomeStatusJson("""{"net_fee":0,"ecard_yuer":19.5,"newmail_count":3}"""),
        )
        assertEquals("19.5", result.status.campusCardBalance)
    }

    @Test fun rejectsMissingAndMalformedFields() {
        assertIs<HomeStatusParseResult.Failure>(parseHomeStatusJson("{}"))
        assertIs<HomeStatusParseResult.Failure>(parseHomeStatusJson("not-json"))
    }

    /**
     * 单个字段缺失时不能把另外两个一起丢掉：旧行为会让用户同时看不到校园卡和
     * 校园网余额，即使服务器只少了 `net_fee`。
     */
    @Test fun keepsRemainingFieldsWhenOneIsMissing() {
        val result = assertIs<HomeStatusParseResult.Success>(
            parseHomeStatusJson("""{"ecard_yuer":"88.00","newmail_count":"2"}"""),
        )
        assertEquals("2", result.status.newMailCount)
        assertEquals("88.00", result.status.campusCardBalance)
        assertEquals("", result.status.networkBalance)
    }

    @Test fun treatsBlankFieldAsUnknownWithoutFailingTheWholeSnapshot() {
        val result = assertIs<HomeStatusParseResult.Success>(
            parseHomeStatusJson("""{"net_fee":"","ecard_yuer":"88.00","newmail_count":0}"""),
        )
        assertEquals("", result.status.networkBalance)
        assertEquals("88.00", result.status.campusCardBalance)
    }

    /** 三个字段全缺说明拿到的不是首页状态，仍判失败，避免把别的页面当成功。 */
    @Test fun rejectsResponseWithNoStatusFields() {
        assertIs<HomeStatusParseResult.Failure>(parseHomeStatusJson("""{"STATUS":"1"}"""))
    }

    @Test fun toleratesUtf8ByteOrderMark() {
        val result = assertIs<HomeStatusParseResult.Success>(
            parseHomeStatusJson(
                "\uFEFF" + """{"net_fee":"12.50","ecard_yuer":"88.00","newmail_count":"2"}""",
            ),
        )
        assertEquals("12.50", result.status.networkBalance)
    }

    /** MIS 老接口可能在 JSON 之后追加内容；对照的 JSONObject 实现能容忍。 */
    @Test fun toleratesTrailingContentAfterJsonObject() {
        val result = assertIs<HomeStatusParseResult.Success>(
            parseHomeStatusJson(
                """{"net_fee":"9","ecard_yuer":"30","newmail_count":"1"}""" + "\n<!-- ok -->",
            ),
        )
        assertEquals("30", result.status.campusCardBalance)
    }
}
