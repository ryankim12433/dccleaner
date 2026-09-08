package com.dccleaner.app.network

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionDelegateProtocol
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.create
import platform.Foundation.setHTTPBody
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS에서 DCInside와 통신하기 위한 최소 네트워크 클라이언트.
 *
 * 현재 단계에서는:
 * - HTTP GET
 * - HTTP POST
 * - Cookie 저장/재사용
 * - 응답 상태 코드 확인
 *
 * 까지만 담당한다.
 *
 * 실제 DCInside 로그인 로직은 다음 단계에서 이 클라이언트를 이용해 연결한다.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDcinsideClient {

    private val session: NSURLSession =
        NSURLSession.sessionWithConfiguration(
            platform.Foundation.NSURLSessionConfiguration.defaultSessionConfiguration()
        )

    private val cookies = mutableMapOf<String, String>()

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): IosHttpResponse {
        val request = NSMutableURLRequest.requestWithURL(
            NSURL(string = url)
        )

        request.HTTPMethod = "GET"

        applyHeaders(request, headers)
        applyCookies(request)

        return execute(request)
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): IosHttpResponse {
        val request = NSMutableURLRequest.requestWithURL(
            NSURL(string = url)
        )

        request.HTTPMethod = "POST"

        val formBody = form.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }

        request.setHTTPBody(
            formBody.encodeToByteArray().toNSData()
        )

        request.setValue(
            "application/x-www-form-urlencoded; charset=UTF-8",
            forHTTPHeaderField = "Content-Type"
        )

        applyHeaders(request, headers)
        applyCookies(request)

        return execute(request)
    }

    private fun applyHeaders(
        request: NSMutableURLRequest,
        headers: Map<String, String>
    ) {
        headers.forEach { (key, value) ->
            request.setValue(
                value,
                forHTTPHeaderField = key
            )
        }
    }

    private fun applyCookies(
        request: NSMutableURLRequest
    ) {
        if (cookies.isEmpty()) {
            return
        }

        val cookieHeader = cookies.entries.joinToString("; ") {
            "${it.key}=${it.value}"
        }

        request.setValue(
            cookieHeader,
            forHTTPHeaderField = "Cookie"
        )
    }

    private suspend fun execute(
        request: NSURLRequest
    ): IosHttpResponse =
        suspendCancellableCoroutine { continuation ->

            val task = session.dataTaskWithRequest(
                request
            ) { data, response, error ->

                if (!continuation.isActive) {
                    return@dataTaskWithRequest
                }

                if (error != null) {
                    continuation.resumeWithException(
                        IllegalStateException(
                            error.localizedDescription
                        )
                    )
                    return@dataTaskWithRequest
                }

                val httpResponse =
                    response as? NSHTTPURLResponse

                if (httpResponse == null) {
                    continuation.resumeWithException(
                        IllegalStateException(
                            "DCInside 서버의 HTTP 응답을 확인할 수 없습니다."
                        )
                    )
                    return@dataTaskWithRequest
                }

                val body =
                    data?.toUtf8String().orEmpty()

                captureCookies(httpResponse)

                continuation.resume(
                    IosHttpResponse(
                        statusCode = httpResponse.statusCode.toInt(),
                        body = body
                    )
                )
            }

            continuation.invokeOnCancellation {
                task.cancel()
            }

            task.resume()
        }

    private fun captureCookies(
        response: NSHTTPURLResponse
    ) {
        val headers = response.allHeaderFields

        headers.forEach { key, value ->
            if (key.toString().equals("Set-Cookie", ignoreCase = true)) {
                value.toString()
                    .split(";")
                    .firstOrNull()
                    ?.let { cookie ->
                        val separator = cookie.indexOf('=')

                        if (separator > 0) {
                            val name =
                                cookie.substring(0, separator).trim()

                            val cookieValue =
                                cookie.substring(separator + 1).trim()

                            cookies[name] = cookieValue
                        }
                    }
            }
        }
    }

    fun clearCookies() {
        cookies.clear()
    }

    fun hasCookies(): Boolean =
        cookies.isNotEmpty()

    private fun urlEncode(
        value: String
    ): String {
        return value
            .replace("%", "%25")
            .replace(" ", "%20")
            .replace("!", "%21")
            .replace("\"", "%22")
            .replace("#", "%23")
            .replace("$", "%24")
            .replace("&", "%26")
            .replace("'", "%27")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("*", "%2A")
            .replace("+", "%2B")
            .replace(",", "%2C")
            .replace("/", "%2F")
            .replace(":", "%3A")
            .replace(";", "%3B")
            .replace("<", "%3C")
            .replace("=", "%3D")
            .replace(">", "%3E")
            .replace("?", "%3F")
            .replace("@", "%40")
            .replace("[", "%5B")
            .replace("\\", "%5C")
            .replace("]", "%5D")
            .replace("^", "%5E")
            .replace("`", "%60")
            .replace("{", "%7B")
            .replace("|", "%7C")
            .replace("}", "%7D")
    }
}

data class IosHttpResponse(
    val statusCode: Int,
    val body: String
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    return NSData.create(
        bytes = this.refTo(0),
        length = size.toULong()
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toUtf8String(): String {
    return toByteArray()
        .decodeToString()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    if (length == 0uL) {
        return ByteArray(0)
    }

    val result = ByteArray(length.toInt())

    result.usePinned { pinned ->
        memcpy(
            pinned.addressOf(0),
            bytes,
            length
        )
    }

    return result
}
