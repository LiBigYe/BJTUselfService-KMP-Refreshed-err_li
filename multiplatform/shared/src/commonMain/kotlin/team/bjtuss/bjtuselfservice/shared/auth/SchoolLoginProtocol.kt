package team.bjtuss.bjtuselfservice.shared.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

private const val MIS_SSO_URL = "https://mis.bjtu.edu.cn/auth/sso/?next=/"
private const val MIS_HOME_URL = "https://mis.bjtu.edu.cn/home/"
private const val CAS_LOGIN_PREFIX = "https://cas.bjtu.edu.cn/auth/login/?next="
private const val CAS_ORIGIN = "https://cas.bjtu.edu.cn"
private const val CAS_REFRESH_LOGIN_URL =
    "$CAS_ORIGIN/auth/login/?next=%2Fauth%2Fsso%2F%3Fnext%3D%2F"
private const val AA_MODULE_URL = "https://mis.bjtu.edu.cn/module/module/10/"
private const val AA_HOME_URL = "https://aa.bjtu.edu.cn/notice/item/"

sealed interface SessionProbeResult {
    data object Active : SessionProbeResult
    data object Missing : SessionProbeResult
}

sealed interface ChallengeResult {
    data class SessionActive(val profile: StudentProfile) : ChallengeResult
    data class Ready(val challenge: CaptchaChallenge) : ChallengeResult
    data class Failed(val reason: LoginFailure) : ChallengeResult
}

sealed interface AuthenticationResult {
    data class Success(val profile: StudentProfile) : AuthenticationResult
    data class Failed(val reason: LoginFailure) : AuthenticationResult
}

/**
 * 学校登录（CAS/MIS/教务握手）协议。
 *
 * 每条流程都由多步、且彼此依赖 cookie 的请求组成（取登录页 → 取验证码图 → 提交凭据 →
 * 跟随落地），因此同一时刻只能有一条登录流程在跑。这层互斥原先是由 transport 的
 * 全局请求锁顺带提供的；transport 恢复并发后必须在这里显式补回，否则两条 CAS 流程
 * （例如用户在登录页重试的同时物理在线触发会话恢复）会互相覆盖 CSRF 与验证码会话。
 *
 * 注意：这里只互斥登录流程之间，不与普通数据请求互斥——数据请求并发是这次性能修复的目的。
 */
class SchoolLoginProtocol(
    private val transport: SchoolHttpTransport,
) : LoginAutomationGateway {
    private val loginMutex = Mutex()

    suspend fun checkSession(): SessionProbeResult = loginMutex.withLock {
        val response = transport.execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = MIS_HOME_URL,
                headers = mapOf("Referer" to MIS_HOME_URL),
            ),
        )
        if (response.finalUrl.matchesEndpoint(MIS_HOME_URL)) {
            SessionProbeResult.Active
        } else {
            SessionProbeResult.Missing
        }
    }

    override suspend fun requestCaptchaChallenge(studentId: String): ChallengeResult = loginMutex.withLock {
        val sso = transport.execute(SchoolHttpRequest(SchoolHttpMethod.GET, MIS_SSO_URL))
        if (sso.finalUrl.matchesEndpoint(MIS_HOME_URL)) {
            return@withLock when (val profile = parseMisStudentProfile(sso.bodyText(), studentId)) {
                is ParseResult.Success -> ChallengeResult.SessionActive(profile.value)
                is ParseResult.Failure -> ChallengeResult.Failed(LoginFailure.MALFORMED_RESPONSE)
            }
        }
        if (!sso.finalUrl.startsWith(CAS_LOGIN_PREFIX)) {
            return@withLock ChallengeResult.Failed(LoginFailure.MALFORMED_RESPONSE)
        }
        loadCaptchaChallenge(
            loginPageUrl = sso.finalUrl,
            referer = MIS_SSO_URL,
        )
    }

    /**
     * 物理在线 Moodle 会话失效时使用的 CAS 恢复入口。
     *
     * [requestCaptchaChallenge] 为避免打断正常登录会先探测 MIS 会话；如果 MIS
     * 仍有效，它会直接返回 SessionActive。物理在线恢复需要真正重新拿到 CAS
     * 登录页，因此这里从 CAS 的 SSO 回调入口重新加载 challenge。
     */
    suspend fun requestFreshCaptchaChallenge(studentId: String): ChallengeResult = loginMutex.withLock {
        val loginPage = transport.execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = CAS_REFRESH_LOGIN_URL,
                headers = mapOf("Referer" to MIS_SSO_URL),
            ),
        )
        if (loginPage.finalUrl.matchesEndpoint(MIS_HOME_URL)) {
            return@withLock when (val profile = parseMisStudentProfile(loginPage.bodyText(), studentId)) {
                is ParseResult.Success -> ChallengeResult.SessionActive(profile.value)
                is ParseResult.Failure -> ChallengeResult.Failed(LoginFailure.MALFORMED_RESPONSE)
            }
        }
        if (!loginPage.finalUrl.startsWith(CAS_LOGIN_PREFIX)) {
            return@withLock ChallengeResult.Failed(LoginFailure.MALFORMED_RESPONSE)
        }
        loadCaptchaChallenge(
            loginPageUrl = loginPage.finalUrl,
            referer = CAS_REFRESH_LOGIN_URL,
        )
    }

    private suspend fun loadCaptchaChallenge(
        loginPageUrl: String,
        referer: String,
    ): ChallengeResult {
        // The initial SSO request may already have returned the CAS HTML. Fetching
        // the URL again follows the existing login behavior and obtains a fresh challenge.
        val loginPage = transport.execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = loginPageUrl,
                headers = mapOf("Referer" to referer),
            ),
        )
        val form = when (val parsed = parseCasLoginForm(loginPage.bodyText())) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Failure -> return ChallengeResult.Failed(LoginFailure.MALFORMED_RESPONSE)
        }
        val image = transport.execute(
            SchoolHttpRequest(SchoolHttpMethod.GET, "$CAS_ORIGIN/image/${form.captchaId}/"),
        )
        if (image.statusCode !in 200..299 || image.body.isEmpty()) {
            return ChallengeResult.Failed(LoginFailure.NETWORK)
        }
        return ChallengeResult.Ready(
            CaptchaChallenge(
                loginPageUrl = loginPageUrl,
                csrfToken = form.csrfToken,
                captchaId = form.captchaId,
                imageBytes = image.body,
            ),
        )
    }

    override suspend fun authenticateMis(
        credentials: Credentials,
        challenge: CaptchaChallenge,
        captchaAnswer: String,
    ): AuthenticationResult = loginMutex.withLock {
        if (!credentials.isValid || captchaAnswer.isBlank()) {
            return@withLock AuthenticationResult.Failed(LoginFailure.INVALID_CREDENTIALS)
        }
        if (!challenge.loginPageUrl.startsWith(CAS_LOGIN_PREFIX)) {
            return@withLock AuthenticationResult.Failed(LoginFailure.MALFORMED_RESPONSE)
        }

        val response = transport.execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = challenge.loginPageUrl,
                headers = mapOf(
                    "Referer" to challenge.loginPageUrl,
                    "Origin" to CAS_ORIGIN,
                ),
                formFields = mapOf(
                    "csrfmiddlewaretoken" to challenge.csrfToken,
                    "captcha_0" to challenge.captchaId,
                    "captcha_1" to captchaAnswer,
                    "loginname" to credentials.username,
                    "password" to credentials.password,
                ),
            ),
        )
        val authenticatedHome = if (response.finalUrl.matchesEndpoint(MIS_HOME_URL)) {
            response
        } else {
            transport.execute(
                SchoolHttpRequest(
                    method = SchoolHttpMethod.GET,
                    url = MIS_HOME_URL,
                    headers = mapOf("Referer" to challenge.loginPageUrl),
                ),
            )
        }
        if (!authenticatedHome.finalUrl.matchesEndpoint(MIS_HOME_URL)) {
            return@withLock AuthenticationResult.Failed(LoginFailure.CAPTCHA_REJECTED)
        }
        when (val profile = parseMisStudentProfile(authenticatedHome.bodyText(), credentials.username)) {
            is ParseResult.Success -> AuthenticationResult.Success(profile.value)
            is ParseResult.Failure -> AuthenticationResult.Failed(LoginFailure.MALFORMED_RESPONSE)
        }
    }

    suspend fun linkAcademicSystem(): Boolean = loginMutex.withLock {
        val module = transport.execute(SchoolHttpRequest(SchoolHttpMethod.GET, AA_MODULE_URL))
        val redirect = when (val parsed = parseAcademicRedirectUrl(module.bodyText())) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Failure -> return@withLock false
        }
        val response = transport.execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = redirect + "?",
                headers = mapOf("Referer" to AA_MODULE_URL),
            ),
        )
        response.finalUrl.matchesEndpoint(AA_HOME_URL)
    }

    fun logout() = transport.clearSession()
}

private fun String.matchesEndpoint(expected: String): Boolean =
    substringBefore('#')
        .substringBefore('?')
        .trimEnd('/') == expected.trimEnd('/')
