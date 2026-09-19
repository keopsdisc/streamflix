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
        val queryParams = session.parameters
        val targetUrl = queryParams["url"]?.firstOrNull()
        
        if (targetUrl == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        try {
            val url = URL(targetUrl)
            val connection = url.openConnection() as HttpURLConnection
            
            // Pass the original headers
            for ((key, value) in headers) {
                connection.setRequestProperty(key, value)
            }
            
            // Pass through some client headers if needed (like Range)
            val range = session.headers["range"]
            if (range != null) {
                connection.setRequestProperty("Range", range)
            }
            
            connection.connect()
            
            val responseCode = connection.responseCode
            val isPartial = responseCode == 206
            val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            
            val contentType = connection.contentType ?: "application/octet-stream"
            val contentLengthStr = connection.getHeaderField("Content-Length")
            val contentLength = contentLengthStr?.toLongOrNull() ?: -1L
            
            val inputStream = if (responseCode >= 400) connection.errorStream else connection.inputStream
            
            if (targetUrl.contains(".m3u8")) {
                // We need to rewrite the M3U8
                val content = inputStream.bufferedReader().use { it.readText() }
                val localIp = getLocalIpAddress() ?: "127.0.0.1"
                val rewritten = rewriteM3U8(content, targetUrl, localIp)
                val res = newFixedLengthResponse(status, contentType, rewritten)
                res.addHeader("Access-Control-Allow-Origin", "*")
                return res
            } else {
                // Stream the response
                val res = if (contentLength >= 0) {
                    newFixedLengthResponse(status, contentType, inputStream, contentLength)
                } else {
                    newChunkedResponse(status, contentType, inputStream)
                }
                
                val contentRange = connection.getHeaderField("Content-Range")
                if (contentRange != null) {
                    res.addHeader("Content-Range", contentRange)
                }
                if (contentLength > 0) {
                    res.addHeader("Content-Length", contentLength.toString())
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
                if (trimmed.startsWith("#EXT-X-KEY:") && trimmed.contains("URI=\"")) {
                    // Extract and rewrite URI in EXT-X-KEY
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
