package dev.wetube.app.di

import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException

/**
 * Debug-only network trace. OkHttp's own logging is deliberately not shipped;
 * this listener is installed in debug builds only and records how long each
 * phase of a call takes, so a stall can be attributed to DNS, connect, TLS or
 * the server instead of showing up as a generic "timed out".
 */
object HttpLog {

    private const val TAG = "WeTubeHttp"

    val factory = EventListener.Factory { call: Call ->
        object : EventListener() {
            private val start = System.currentTimeMillis()
            private fun ms() = System.currentTimeMillis() - start

            private fun line(phase: String, detail: String = "") {
                Log.d(TAG, "+${ms()}ms ${call.request().method} ${call.request().url.host} $phase $detail".trim())
            }

            override fun callStart(call: Call) = line("start")
            override fun dnsStart(call: Call, domainName: String) = line("dns", domainName)
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) =
                line("dns-ok", inetAddressList.joinToString(",") { it.hostAddress.orEmpty() })

            override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) =
                line("connect", inetSocketAddress.toString())

            override fun connectEnd(
                call: Call,
                inetSocketAddress: java.net.InetSocketAddress,
                proxy: java.net.Proxy,
                protocol: okhttp3.Protocol?,
            ) = line("connect-ok", protocol?.toString().orEmpty())

            override fun connectFailed(
                call: Call,
                inetSocketAddress: java.net.InetSocketAddress,
                proxy: java.net.Proxy,
                protocol: okhttp3.Protocol?,
                ioe: IOException,
            ) = line("connect-failed", "${ioe.javaClass.simpleName}: ${ioe.message}")

            override fun secureConnectStart(call: Call) = line("tls")
            override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) = line("tls-ok")

            override fun requestHeadersEnd(call: Call, request: okhttp3.Request) = line("sent")
            override fun responseHeadersStart(call: Call) = line("response-start")
            override fun responseHeadersEnd(call: Call, response: okhttp3.Response) =
                line("response", "${response.code}")

            override fun callEnd(call: Call) = line("end")
            override fun callFailed(call: Call, ioe: IOException) =
                line("FAILED", "${ioe.javaClass.simpleName}: ${ioe.message}")
        }
    }

    fun install(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder =
        builder.eventListenerFactory(factory)
}
