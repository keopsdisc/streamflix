package com.streamflixreborn.streamflix.cast

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import android.net.Uri

class CastProxyServer(
    port: Int,
    private val headers: Map<String, String>
) : NanoHTTPD(port) {

    companion object {
        fun getLocalIpAddress(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address.hostAddress.indexOf(':') < 0) {
                            return address.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
    }

    override fun serve(session: IHTTPSession): Response {
        android.util.Log.e("CAST_DEBUG", "Proxy received request: ${session.method} ${session.uri} ? ${session.queryParameterString}")
        
        if (session.method == fi.iki.elonen.NanoHTTPD.Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            res.addHeader("Access-Control-Allow-Origin", "*")
            res.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            res.addHeader("Access-Control-Allow-Headers", "*")
            return res
        }

        val queryParams = session.parameters
        val targetUrl = queryParams["url"]?.firstOrNull()
        
        if (targetUrl == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        try {
            val url = URL(targetUrl)
            val connection = url.openConnection()
            
            var responseCode = 200
            var isPartial = false
            var contentType = "application/octet-stream"
            var contentLength = -1L
            var contentRange: String? = null
            val rawInputStream: java.io.InputStream
            
            if (connection is HttpURLConnection) {
                connection.requestMethod = session.method.name
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                
                for ((key, value) in headers) {
                    connection.setRequestProperty(key, value)
                }
                
                val range = session.headers["range"]
                if (range != null) {
                    connection.setRequestProperty("Range", range)
                }
                
                connection.connect()
                responseCode = connection.responseCode
                isPartial = responseCode == 206
                contentType = connection.contentType ?: "application/octet-stream"
                contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                contentRange = connection.getHeaderField("Content-Range")
                rawInputStream = if (responseCode >= 400) connection.errorStream else connection.inputStream
            } else {
                connection.connect()
                contentType = connection.contentType ?: "application/octet-stream"
                contentLength = connection.contentLength.toLong()
                rawInputStream = connection.inputStream
            }
            
            val status = fi.iki.elonen.NanoHTTPD.Response.Status.lookup(responseCode) ?: (if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK)
            
            if (targetUrl.contains(".m3u8")) {
                contentType = "application/x-mpegURL"
            } else if (targetUrl.contains(".image") || targetUrl.contains(".ts") || targetUrl.contains("video")) {
                contentType = "video/mp2t"
            } else if (targetUrl.endsWith(".srt") || targetUrl.endsWith(".vtt")) {
                contentType = "text/vtt"
            }
            
            if (targetUrl.contains(".m3u8")) {
                val content = rawInputStream.bufferedReader().use { it.readText() }
                val localIp = getLocalIpAddress() ?: "127.0.0.1"
                val rewritten = rewriteM3U8(content, targetUrl, localIp)
                val res = newFixedLengthResponse(status, contentType, rewritten)
                res.addHeader("Access-Control-Allow-Origin", "*")
                return res
            } else {
                val bufferedStream = java.io.BufferedInputStream(rawInputStream, 131072) // 128KB buffer for smoother playback
                val res = if (contentLength >= 0) {
                    newFixedLengthResponse(status, contentType, bufferedStream, contentLength)
                } else {
                    newChunkedResponse(status, contentType, bufferedStream)
                }
                
                if (contentRange != null) {
                    res.addHeader("Content-Range", contentRange)
                }
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Access-Control-Allow-Origin", "*")
                return res
            }
        } catch (e: Exception) {
            Log.e("CastProxyServer", "Error proxying request", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun rewriteM3U8(content: String, baseUrl: String, localIp: String): String {
        val lines = content.split("\n")
        val result = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }
            if (trimmed.startsWith("#")) {
                if (trimmed.contains("URI=\"")) {
                    // Extract and rewrite URI in EXT-X-KEY, EXT-X-MEDIA, etc.
                    val uriRegex = Regex("URI=\"([^\"]+)\"")
                    val rewritten = uriRegex.replace(trimmed) { matchResult ->
                        val uri = matchResult.groupValues[1]
                        val absoluteUri = resolveUri(baseUrl, uri)
                        "URI=\"http://$localIp:$listeningPort/proxy?url=${Uri.encode(absoluteUri)}\""
                    }
                    result.append(rewritten).append("\n")
                } else {
                    result.append(trimmed).append("\n")
                }
            } else {
                // It's a URI
                val absoluteUri = resolveUri(baseUrl, trimmed)
                val proxyUrl = "http://$localIp:$listeningPort/proxy?url=${Uri.encode(absoluteUri)}"
                result.append(proxyUrl).append("\n")
            }
        }
        return result.toString()
    }

    private fun resolveUri(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            return uri
        }
        return URL(URL(base), uri).toString()
    }
}
