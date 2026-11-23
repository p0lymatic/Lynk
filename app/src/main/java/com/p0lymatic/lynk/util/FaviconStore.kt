package com.p0lymatic.lynk.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.p0lymatic.lynk.data.Store
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.math.roundToInt

/** Local favicon cache per service plus a direct fetch from a loopback forward. */
object FaviconStore {

    private const val MAX_SIZE = 128
    private const val MAX_ICON_BYTES = 512 * 1024
    private const val TIMEOUT_MS = 4000
    private val PATHS = listOf("/favicon.ico", "/apple-touch-icon.png")

    /** Downscales [bitmap] to at most 128x128 and stores it as PNG for [serviceId]. */
    fun save(context: Context, store: Store, serviceId: String, bitmap: Bitmap): Boolean {
        val scaled = scaleDown(bitmap)
        val file = store.iconFile(serviceId)
        val ok = runCatching {
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.getOrDefault(false)
        if (scaled !== bitmap) scaled.recycle()
        if (!ok) {
            file.delete()
            return false
        }
        store.bumpIcon(serviceId)
        return true
    }

    /** Loads a cached icon; null when absent or undecodable. */
    fun load(store: Store, serviceId: String): Bitmap? {
        val file = store.iconFile(serviceId)
        if (!file.isFile) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    /** Blocking: fetches a favicon over a loopback forward. Call from Dispatchers.IO. */
    fun fetchDirect(port: Int, scheme: String): Bitmap? {
        if (port <= 0 || port > 65535) return null
        val proto = scheme.trim().lowercase()
        if (proto != "http" && proto != "https") return null
        val base = "$proto://127.0.0.1:$port"
        for (path in PATHS) {
            val bytes = runCatching { request(base + path) }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun request(url: String): ByteArray? {
        val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            if (connection.contentLengthLong > MAX_ICON_BYTES) return null
            connection.inputStream.use { readLimited(it) }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ICON_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source
        if (width <= MAX_SIZE && height <= MAX_SIZE) return source
        val ratio = min(MAX_SIZE.toFloat() / width, MAX_SIZE.toFloat() / height)
        val targetWidth = (width * ratio).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }
}