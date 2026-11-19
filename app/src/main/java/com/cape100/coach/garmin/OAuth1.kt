package com.cape100.coach.garmin

import okhttp3.HttpUrl
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimal OAuth 1.0a (HMAC-SHA1) request signer, as used by Garmin's token endpoints. */
object OAuth1 {
    fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    fun header(
        method: String,
        url: HttpUrl,
        formParams: Map<String, String>,
        consumerKey: String,
        consumerSecret: String,
        token: String? = null,
        tokenSecret: String? = null,
    ): String {
        val oauth = sortedMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to UUID.randomUUID().toString().replace("-", ""),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_version" to "1.0",
        )
        if (token != null) oauth["oauth_token"] = token

        val params = mutableListOf<Pair<String, String>>()
        oauth.forEach { (k, v) -> params += enc(k) to enc(v) }
        for (i in 0 until url.querySize) params += enc(url.queryParameterName(i)) to enc(url.queryParameterValue(i) ?: "")
        formParams.forEach { (k, v) -> params += enc(k) to enc(v) }
        val paramString = params.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }

        val baseUrl = url.newBuilder().query(null).fragment(null).build().toString()
        val base = "${method.uppercase()}&${enc(baseUrl)}&${enc(paramString)}"
        val key = "${enc(consumerSecret)}&${enc(tokenSecret ?: "")}"
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA1")) }
        oauth["oauth_signature"] = Base64.getEncoder().encodeToString(mac.doFinal(base.toByteArray()))

        return "OAuth " + oauth.entries.joinToString(", ") { "${enc(it.key)}=\"${enc(it.value)}\"" }
    }
}
