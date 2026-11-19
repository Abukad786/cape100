package com.cape100.coach.garmin

import com.cape100.coach.data.GarminTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class GarminException(message: String) : Exception(message)

/**
 * Logs in to Garmin Connect the same way the Garmin Connect mobile app does
 * (SSO ticket -> OAuth1 token -> OAuth2 bearer token). This is an unofficial
 * flow (it mirrors the open-source `garth` library) and may break if Garmin changes it.
 * Your password is only sent to sso.garmin.com and is never stored.
 */
class GarminAuth(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        const val SSO = "https://sso.garmin.com/sso"
        const val SSO_EMBED = "$SSO/embed"
        const val API = "https://connectapi.garmin.com"
        const val UA_API = "GCM-iOS-5.7.2.1"
        const val UA_OAUTH = "com.garmin.android.apps.connectmobile"
        private const val CONSUMER_URL = "https://thegarth.s3.amazonaws.com/oauth_consumer.json"

        private var consumer: Pair<String, String>? = null
    }

    private val json = Json { ignoreUnknownKeys = true }

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableMapOf<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { this.cookies["${it.domain}|${it.path}|${it.name}"] = it }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.filter { it.matches(url) }
    }

    suspend fun login(email: String, password: String, askMfaCode: suspend () -> String): GarminTokens =
        withContext(Dispatchers.IO) {
            val sso = http.newBuilder().cookieJar(MemoryCookieJar()).followRedirects(true).build()
            val embedParams = mapOf("id" to "gauth-widget", "embedWidget" to "true", "gauthHost" to SSO)
            val signinParams = embedParams + mapOf(
                "gauthHost" to SSO_EMBED,
                "service" to SSO_EMBED,
                "source" to SSO_EMBED,
                "redirectAfterAccountLoginUrl" to SSO_EMBED,
                "redirectAfterAccountCreationUrl" to SSO_EMBED,
            )

            var (_, lastUrl) = get(sso, url(SSO_EMBED, embedParams), null)
            val signinUrl = url("$SSO/signin", signinParams)
            var (text, url2) = get(sso, signinUrl, lastUrl)
            lastUrl = url2
            val csrf = csrf(text) ?: throw GarminException("Could not reach Garmin sign-in page.")

            post(sso, signinUrl, mapOf("username" to email, "password" to password, "embed" to "true", "_csrf" to csrf), lastUrl)
                .also { text = it.first; lastUrl = it.second }
            var title = title(text)

            if (title.contains("MFA", ignoreCase = true)) {
                val csrf2 = csrf(text) ?: csrf
                val code = askMfaCode().trim()
                post(
                    sso, url("$SSO/verifyMFA/loginEnterMfaCode", signinParams),
                    mapOf("mfa-code" to code, "embed" to "true", "_csrf" to csrf2, "fromPage" to "setupEnterMfaCode"),
                    lastUrl,
                ).also { text = it.first; lastUrl = it.second }
                title = title(text)
            }
            if (title != "Success") {
                throw GarminException("Garmin login failed ($title). Check your email and password.")
            }
            val ticket = Regex("""embed\?ticket=([^"]+)"""").find(text)?.groupValues?.get(1)
                ?: throw GarminException("Garmin login succeeded but no ticket was returned.")

            exchange(preauthorize(ticket))
        }

    /** Returns tokens with a valid OAuth2 bearer token, re-exchanging with the long-lived OAuth1 token if needed. */
    suspend fun ensureFresh(tokens: GarminTokens): GarminTokens =
        if (tokens.accessToken.isNotBlank() && System.currentTimeMillis() < tokens.accessExpiresAt) tokens
        else withContext(Dispatchers.IO) { exchange(tokens) }

    private fun consumer(): Pair<String, String> {
        consumer?.let { return it }
        val body = http.newCall(Request.Builder().url(CONSUMER_URL).build()).execute().use { it.body?.string() }
            ?: throw GarminException("Could not load Garmin OAuth consumer.")
        val o = json.parseToJsonElement(body).jsonObject
        return (o["consumer_key"]!!.jsonPrimitive.content to o["consumer_secret"]!!.jsonPrimitive.content)
            .also { consumer = it }
    }

    private fun preauthorize(ticket: String): GarminTokens {
        val (key, secret) = consumer()
        val u = "$API/oauth-service/oauth/preauthorized".toHttpUrl().newBuilder()
            .addQueryParameter("ticket", ticket)
            .addQueryParameter("login-url", SSO_EMBED)
            .addQueryParameter("accepts-mfa-tokens", "true")
            .build()
        val req = Request.Builder().url(u)
            .header("User-Agent", UA_OAUTH)
            .header("Authorization", OAuth1.header("GET", u, emptyMap(), key, secret))
            .build()
        val body = http.newCall(req).execute().use {
            if (!it.isSuccessful) throw GarminException("Garmin OAuth1 failed (${it.code}).")
            it.body?.string().orEmpty()
        }
        val q = body.split("&").mapNotNull { p ->
            p.split("=", limit = 2).takeIf { it.size == 2 }?.let { java.net.URLDecoder.decode(it[0], "UTF-8") to java.net.URLDecoder.decode(it[1], "UTF-8") }
        }.toMap()
        return GarminTokens(
            oauthToken = q["oauth_token"] ?: throw GarminException("No OAuth1 token from Garmin."),
            oauthTokenSecret = q["oauth_token_secret"].orEmpty(),
            mfaToken = q["mfa_token"],
        )
    }

    private fun exchange(t: GarminTokens): GarminTokens {
        val (key, secret) = consumer()
        val u = "$API/oauth-service/oauth/exchange/user/2.0".toHttpUrl()
        val form = t.mfaToken?.let { mapOf("mfa_token" to it) } ?: emptyMap()
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(u).post(body)
            .header("User-Agent", UA_OAUTH)
            .header("Authorization", OAuth1.header("POST", u, form, key, secret, t.oauthToken, t.oauthTokenSecret))
            .build()
        val text = http.newCall(req).execute().use {
            if (it.code == 401) throw GarminException("Garmin session expired. Please log in again.")
            if (!it.isSuccessful) throw GarminException("Garmin token exchange failed (${it.code}).")
            it.body?.string().orEmpty()
        }
        val o = json.parseToJsonElement(text).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.content ?: throw GarminException("No access token from Garmin.")
        val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        return t.copy(accessToken = access, accessExpiresAt = System.currentTimeMillis() + (expiresIn - 120) * 1000)
    }

    private fun url(base: String, params: Map<String, String>): HttpUrl =
        base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private fun get(c: OkHttpClient, u: HttpUrl, referer: String?): Pair<String, String> {
        val req = Request.Builder().url(u).header("User-Agent", UA_API)
            .apply { referer?.let { header("Referer", it) } }.build()
        return c.newCall(req).execute().use { (it.body?.string().orEmpty()) to it.request.url.toString() }
    }

    private fun post(c: OkHttpClient, u: HttpUrl, form: Map<String, String>, referer: String?): Pair<String, String> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(u).post(body).header("User-Agent", UA_API)
            .apply { referer?.let { header("Referer", it) } }.build()
        return c.newCall(req).execute().use { (it.body?.string().orEmpty()) to it.request.url.toString() }
    }

    private fun csrf(html: String) = Regex("""name="_csrf"\s+value="(.+?)"""").find(html)?.groupValues?.get(1)
    private fun title(html: String) = Regex("""<title>(.+?)</title>""").find(html)?.groupValues?.get(1)?.trim() ?: "no title"
}
