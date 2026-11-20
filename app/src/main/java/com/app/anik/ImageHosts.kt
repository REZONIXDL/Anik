package com.app.anik

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * آپلود عکس روی هاست‌های رایگان ایرانی: uploadkon.ir و imgurl.ir
 *
 * بدون API Key و بدون ورودی از کاربر — آدرس و روش آپلود در همین فایل ثابت است.
 * (روش قدیمی با auth_token و POST به /json دیگر کار نمی‌کند؛ ساختار سایت‌ها عوض شده
 * و همان دلیل «لینک نمی‌داد» بود.)
 *
 *  - uploadkon.ir: POST چندبخشی به https://uploadkon.ir/ با فیلدهای submitr=1 و ajax=1
 *    و فایل با نام file — پاسخ JSON شامل لینک مستقیم https://uploadkon.ir/uploads/...
 *  - imgurl.ir: POST چندبخشی به https://imgurl.ir/upload.php با فیلد delete_at=1
 *    و فایل با نام userfile[] — پاسخ HTML شامل لینک مستقیم https://cdn.imgurl.ir/uploads/...
 *
 * هر لینک قبل از نمایش واقعاً دانلود و بررسی می‌شود؛ لینک تقلبی یا خالی هرگز نمایش داده نمی‌شود.
 */
object ImageHosts {
    class Host(val id: String, val name: String, val base: String)
    val HOSTS = listOf(
        Host("uploadkon", "UploadKon", "https://uploadkon.ir"),
        Host("imgurl", "ImgURL", "https://imgurl.ir")
    )
    class Uploaded(val host: String, val url: String)
    class Failure(message: String) : Exception(message)

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun upload(png: ByteArray, fileName: String): Uploaded {
        val errors = mutableListOf<String>()
        for (h in HOSTS) {
            try {
                val url = uploadTo(h, png, fileName)
                verify(url)
                return Uploaded(h.name, url)
            } catch (e: Exception) {
                errors += "${h.name}: ${e.message}"
            }
        }
        throw Failure(errors.joinToString("\n"))
    }

    private fun uploadTo(h: Host, png: ByteArray, fileName: String): String {
        return when (h.id) {
            "uploadkon" -> {
                // فرم مهمان سایت: POST به صفحه اصلی با submitr و ajax
                val body = multipart(
                    url = "${h.base}/", origin = h.base,
                    fields = mapOf("submitr" to "1", "ajax" to "1"),
                    fileField = "file", file = png, fileName = fileName
                )
                extractUploadKonUrl(body)
                    ?: throw Failure(errorOf(body))
            }
            else -> {
                // فرم کلاسیک سایت: POST به upload.php با userfile[]
                val body = multipart(
                    url = "${h.base}/upload.php", origin = h.base,
                    fields = mapOf("delete_at" to "1"),
                    fileField = "userfile[]", file = png, fileName = fileName
                )
                extractImgUrlUrl(body)
                    ?: throw Failure(errorOf(body))
            }
        }
    }

    /** لینک مستقیم uploadkon: uploads/... بدون thumbs — بندانگشتی‌ها لینک اصلی نیستند. */
    private fun extractUploadKonUrl(text: String): String? {
        val clean = text.replace("\\/", "/")
        return Regex("""https://uploadkon\.ir/uploads/(?!thumbs/)[^"'\s<>\\]+?\.(?:png|jpe?g|webp)""", RegexOption.IGNORE_CASE)
            .find(clean)?.value
            ?: extractUrl(clean)
    }

    /** لینک مستقیم imgurl روی CDN است؛ لوگوی سایت را نگیرد. */
    private fun extractImgUrlUrl(text: String): String? {
        val clean = text.replace("\\/", "/")
        return Regex("""https://cdn\.imgurl\.ir/uploads/[^"'\s<>\\]+?\.(?:png|jpe?g|webp)""", RegexOption.IGNORE_CASE)
            .find(clean)?.value
            ?: extractUrl(clean)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "fa,en;q=0.8")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw Failure("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun multipart(url: String, origin: String, fields: Map<String, String>, fileField: String, file: ByteArray, fileName: String): String {
        val boundary = "----Anik" + System.currentTimeMillis()
        val bos = ByteArrayOutputStream()
        fun w(s: String) = bos.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\nContent-Type: image/png\r\n\r\n")
        bos.write(file)
        w("\r\n--$boundary--\r\n")
        val data = bos.toByteArray()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        // سرورهای این هاست‌ها گاهی کند پاسخ می‌دهند؛ مهلت خواندن بیشتر است
        c.connectTimeout = 15000; c.readTimeout = 120000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        c.setRequestProperty("Origin", origin)
        c.setRequestProperty("Referer", "$origin/")
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { it.write(data) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299 && extractUrl(text) == null) throw Failure("HTTP $code ${text.take(140)}")
        return text
    }

    private fun extractUrl(text: String): String? {
        runCatching {
            val j = JSONObject(text)
            val img = j.optJSONObject("image")
            listOfNotNull(
                img?.optString("url"), img?.optString("display_url"),
                j.optString("url"), j.optJSONObject("data")?.optString("link"), j.optJSONObject("data")?.optString("url")
            ).firstOrNull { it.startsWith("http") }?.let { return it }
        }
        return Regex("""https?:\\?/\\?/[^"'\s<>\\]+?\.(?:png|jpe?g|webp)""", RegexOption.IGNORE_CASE)
            .find(text.replace("\\/", "/"))?.value
    }

    private fun errorOf(body: String): String {
        runCatching {
            val j = JSONObject(body)
            j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.let { return it }
            j.optString("status_txt").takeIf { it.isNotBlank() }?.let { return it }
        }
        return "پاسخ نامعتبر از سرور: " + body.take(140)
    }

    /** فقط سربرگ (HEAD) + در صورت نیاز ۱ کیلوبایت اول — نه دانلود کل عکس روی دیتای موبایل. */
    private fun verify(url: String) {
        var last = "ناموفق"
        for (i in 0 until 3) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                c.setRequestProperty("User-Agent", UA)
                c.requestMethod = "HEAD"
                try {
                    val type = c.contentType.orEmpty()
                    val len = c.contentLengthLong
                    if (c.responseCode in 200..299 && type.startsWith("image/") && len > 500) return
                } finally {
                    c.disconnect()
                }
                // بعضی هاست‌ها HEAD را قبول نمی‌کنند؛ ۱ کیلوبایت اول را بگیر
                val g = URL(url).openConnection() as HttpURLConnection
                g.connectTimeout = 8000; g.readTimeout = 15000
                g.setRequestProperty("User-Agent", UA)
                g.setRequestProperty("Range", "bytes=0-1023")
                try {
                    val type = g.contentType.orEmpty()
                    val ok = g.responseCode in 200..299 || g.responseCode == 206
                    val first = if (ok) g.inputStream.use { it.read() } else -1
                    if (ok && type.startsWith("image/") && first >= 0) return
                    last = "HTTP ${g.responseCode} / $type"
                } finally {
                    g.disconnect()
                }
            } catch (e: Exception) { last = e.message ?: "خطا" }
            Thread.sleep(800)
        }
        throw Failure("لینک ساخته شد اما تصویر قابل دریافت نیست ($last)")
    }
}
