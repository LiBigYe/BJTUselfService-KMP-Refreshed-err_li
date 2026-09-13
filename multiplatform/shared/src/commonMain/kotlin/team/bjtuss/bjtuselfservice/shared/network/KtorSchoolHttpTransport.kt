package team.bjtuss.bjtuselfservice.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Headers as KtorHeaders
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

expect fun schoolHttpEngineFactory(): HttpClientEngineFactory<*>

fun createSchoolHttpTransport(): SchoolHttpTransport = KtorSchoolHttpTransport(
    engineFactory = schoolHttpEngineFactory(),
)

/**
 * 会话请求的最大并发数。
 *
 * 旧实现用一把全局 `Mutex` 把**整个应用**的会话请求串成一条队列，理由写的是
 * “Ktor `AcceptAllCookiesStorage` 非线程安全”。这个前提在 Ktor 3.x 上不成立：
 * `AcceptAllCookiesStorage` 内部已经用 `Mutex` + atomicfu 保护了自己的容器
 *（见 ktor-client-core 3.5.1 的 `AcceptAllCookiesStorage.kt`），共享 cookie jar 本身
 * 可以并发读写。
 *
 * 保留一个有上限的信号量，是因为学校服务器对同一会话的突发并发本身敏感：既不能
 * 串行（首页一次刷新会扇出几十个请求，串行等于把总时延相加），也不能无上限并发。
 */
private const val SESSION_MAX_CONCURRENCY = 4

class KtorSchoolHttpTransport(
    private val engineFactory: HttpClientEngineFactory<*>,
) : SchoolHttpTransport {
    /**
     * 一个登录态的全部客户端。整体替换而不是逐字段改，配合 [Volatile] 让并发请求
     * 要么看到旧的完整会话，要么看到新的完整会话，不会读到半更新的组合。
     */
    private class Session(
        val storage: AcceptAllCookiesStorage,
        val client: HttpClient,
        val rawClient: HttpClient,
    )

    @Volatile
    private var session = newSession()

    /**
     * 公开页旁路：无 Cookie、不进会话并发闸门、更短超时。
     * 仅用于 bksy 校历等不依赖登录的页面；切勿用它拉 aa/CAS。
     */
    private val publicClient = newClient(AcceptAllCookiesStorage(), sessionScoped = false)

    private val sessionPermits = Semaphore(SESSION_MAX_CONCURRENCY)

    companion object {
        /**
         * 与原 Android App 登录请求一致的浏览器标识。学校 CAS 对缺少
         * 浏览器 User-Agent 的验证码提交会判定“认证码错误”，必须保留。
         */
        const val SCHOOL_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36 Edg/142.0.0.0"
    }

    override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse =
        sessionPermits.withPermit {
            executeOn(session.client, request)
        }

    override suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse =
        sessionPermits.withPermit {
            executeOn(session.rawClient, request)
        }

    override suspend fun executePublic(request: SchoolHttpRequest): SchoolHttpResponse =
        // 故意不占会话并发额度：公开页挂起不得堵住 aa 会话查询。
        executeOn(publicClient, request)

    override fun clearSession() {
        val previous = session
        // 先换引用再关旧客户端：新请求立刻使用干净 jar，旧客户端上仍在途的请求被取消，
        // 这正是退出登录想要的结果。
        session = newSession()
        runCatching { previous.client.close() }
        runCatching { previous.rawClient.close() }
    }

    private suspend fun executeOn(
        httpClient: HttpClient,
        request: SchoolHttpRequest,
    ): SchoolHttpResponse {
        try {
            val response = httpClient.request(request.url) {
                method = when (request.method) {
                    SchoolHttpMethod.GET -> HttpMethod.Get
                    SchoolHttpMethod.POST -> HttpMethod.Post
                }
                headers {
                    request.headers.forEach { (name, value) ->
                        // ByteArrayContent 会根据 rawBodyContentType 写入 Content-Type；
                        // 跳过调用方重复提供的同名头，避免 Ktor 合并出两个 Content-Type。
                        if (request.rawBody == null || !name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                            append(name, value)
                        }
                    }
                }
                if (request.rawBody != null) {
                    setBody(
                        ByteArrayContent(
                            bytes = request.rawBody,
                            contentType = ContentType.parse(request.rawBodyContentType),
                        ),
                    )
                } else if (request.multipartFiles.isNotEmpty()) {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                request.formFields.forEach { (name, value) ->
                                    append(name, value)
                                }
                                request.multipartFiles.forEach { file ->
                                    append(
                                        file.fieldName,
                                        file.bytes,
                                        KtorHeaders.build {
                                            append(
                                                HttpHeaders.ContentDisposition,
                                                "filename=\"${file.fileName.safeMultipartFileName()}\"",
                                            )
                                            append(HttpHeaders.ContentType, file.contentType)
                                        },
                                    )
                                }
                            },
                        ),
                    )
                } else if (request.formFields.isNotEmpty()) {
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                request.formFields.forEach { (name, value) -> append(name, value) }
                            },
                        ),
                    )
                }
            }
            return SchoolHttpResponse(
                statusCode = response.status.value,
                finalUrl = response.call.request.url.toString(),
                headers = response.headers.entries().associate { it.key to it.value },
                body = response.bodyAsBytes(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw SchoolNetworkException("School request failed", error)
        }
    }

    override suspend fun sessionCookiesFor(url: String): List<SchoolSessionCookie> =
        // 纯内存读，不占会话并发额度，也不该排在网络请求后面。
        session.storage.get(Url(url)).map { cookie ->
            SchoolSessionCookie(
                name = cookie.name,
                value = cookie.value,
                path = cookie.path ?: "/",
                secure = cookie.secure,
            )
        }

    private fun newSession(): Session {
        val storage = AcceptAllCookiesStorage()
        return Session(
            storage = storage,
            // 两个客户端共享同一 cookie jar：手写跳转校验与自动跟随必须同一会话。
            client = newClient(storage, sessionScoped = true),
            rawClient = newClient(storage, sessionScoped = true, followRedirects = false),
        )
    }

    private fun newClient(
        storage: AcceptAllCookiesStorage,
        sessionScoped: Boolean,
        followRedirects: Boolean = true,
    ): HttpClient = HttpClient(engineFactory) {
        this.followRedirects = followRedirects
        install(HttpCookies) {
            this.storage = storage
        }
        install(UserAgent) {
            agent = SCHOOL_USER_AGENT
        }
        install(HttpTimeout) {
            if (sessionScoped) {
                // 旧实现给会话请求 30s/15s/30s。单次请求的超时就是整个刷新最坏情况的
                // 上界，30s 让一次挂起看起来像“应用卡死”。并发恢复后按真实观测收紧到
                // 15s：学校接口正常都在 1s 内返回，慢的课件资源树也在 10s 内。
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 15_000
            } else {
                // 公开页（如 bksy 校历）：代理下挂起时尽快失败，别拖业务 UI。
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
        }
    }
}

private fun String.safeMultipartFileName(): String =
    substringAfterLast('/').substringAfterLast('\\').replace('"', '_').ifBlank { "upload.bin" }
