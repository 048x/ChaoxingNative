package com.chaoxing.nativeapp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 对应原 api/base.py 的 SessionManager + api/cookies.py：
 * 会话 Cookie 持久化到 SharedPreferences，所有请求自动带上/回收 Cookie。
 */
object Net {
    private const val PREFS = "cx_cookies"
    private const val KEY = "data"

    private lateinit var prefs: SharedPreferences
    private val cookies = LinkedHashMap<String, String>()
    private val lock = Any()
    private var initialized = false

    lateinit var client: OkHttpClient
        private set

    fun init(ctx: Context) {
        if (initialized) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            cookies.clear()
            val raw = prefs.getString(KEY, null)
            if (!raw.isNullOrEmpty()) {
                runCatching {
                    val obj = JSONObject(raw)
                    obj.keys().forEach { k -> cookies[k] = obj.optString(k) }
                }
            }
        }

        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cookieJar(object : CookieJar {
                // 保存所有响应（含 302 跳转）中的 Set-Cookie
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    if (cookies.isEmpty()) return
                    val now = System.currentTimeMillis()
                    synchronized(lock) {
                        cookies.forEach { cookie ->
                            if (cookie.expiresAt <= now) {
                                Net.cookies.remove(cookie.name)
                            } else {
                                Net.cookies[cookie.name] = cookie.value
                            }
                        }
                    }
                    persist()
                }

                // 超星全部接口都在 chaoxing.com 域下，统一回传已保存的 Cookie
                override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
                    cookies.mapNotNull { (name, value) ->
                        runCatching {
                            Cookie.Builder()
                                .name(name)
                                .value(value)
                                .domain(url.host)
                                .path("/")
                                .build()
                        }.getOrNull()
                    }
                }
            })
            .build()

        initialized = true
    }

    private fun persist() {
        val json = JSONObject()
        synchronized(lock) { cookies.forEach { (k, v) -> json.put(k, v) } }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    fun cookie(name: String): String? = synchronized(lock) { cookies[name] }

    fun clearSession() {
        synchronized(lock) { cookies.clear() }
        prefs.edit().remove(KEY).apply()
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------
    suspend fun get(
        url: String,
        params: Map<String, Any?>? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response = withContext(Dispatchers.IO) {
        val builder = url.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("bad url: $url")
        params?.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v.toString()) }

        val rb = Request.Builder().url(builder.build())
        Cfg.HEADERS.forEach { (k, v) -> rb.header(k, v) }
        headers.forEach { (k, v) -> rb.header(k, v) }
        client.newCall(rb.build()).execute()
    }

    suspend fun post(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Response = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()

        val rb = Request.Builder().url(url).post(body)
        Cfg.HEADERS.forEach { (k, v) -> rb.header(k, v) }
        headers.forEach { (k, v) -> rb.header(k, v) }
        client.newCall(rb.build()).execute()
    }
}

/**
 * 对应原 RateLimiter：串行限速，可选随机抖动。
 */
class RateLimiter(private val intervalMs: Long) {
    private val mutex = Mutex()
    private var lastCall = System.currentTimeMillis()

    suspend fun limit(random: Boolean = false, randomMaxMs: Long = 0) {
        mutex.withLock {
            if (random && randomMaxMs > 0) delay(Random.nextLong(0, randomMaxMs))
            val now = System.currentTimeMillis()
            val elapsed = now - lastCall
            if (elapsed <= intervalMs) {
                delay(intervalMs - elapsed)
                lastCall = System.currentTimeMillis()
            } else {
                lastCall = now
            }
        }
    }
}
