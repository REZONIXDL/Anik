package com.app.anik

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * دانلود قابل‌ادامه (resume) با تایید SHA-256 — برای مدل‌های هوش مصنوعی.
 *
 * چرا این فایل جدا است:
 *  - هر دو مدل دانلودی (Silueta و ORMBG) همین منطق را می‌خواهند؛ تکرار کد در
 *    هر سگمنتر یعنی دو جای مختلف برای باگ.
 *  - ادامه دانلود: اگر اینترنت وسط ۱۷۶ مگابایت قطع شود، دفعه بعد از همان‌جا
 *    ادامه می‌دهد (هدر Range)؛ فایل نیمه‌کاره (.part) نگه داشته می‌شود.
 *  - لغو: حلقه خواندن هر ۶۴KB چک می‌کند؛ با لغو، فایل .part می‌ماند تا بعداً
 *    ادامه پیدا کند.
 *  - امنیت: قبل از استفاده، اندازه و SHA-256 کامل فایل چک می‌شود؛ فایل
 *    خراب یا ناقص هرگز به‌عنوان مدل قبول نمی‌شود.
 *  - چند URL: اول گیت‌هاب (ریلیز REZONIXDL/Anik — در ایران در دسترس است)،
 *    بعد میرور جایگزین. هیچ‌کدام جواب نداد → false.
 *
 * روی رشته IO صدا بزن؛ هیچ‌وقت روی رشته اصلی صدا نزن.
 */
object ModelDownloader {

    /**
     * @param urls به‌ترتیب اولویت؛ هرکدام که دانلود + تایید را کامل کرد برنده است.
     * @param target فایل نهایی (مثلاً filesDir/models/silueta.onnx).
     * @param expectedSize اندازه دقیق بایت؛ اگر سرور اندازه دیگری بدهد رد می‌شود.
     * @param expectedSha256 هش SHA-256 کامل فایل (حروف کوچک hex).
     * @param onProgress مقدار ۰..۱.
     * @param isCancelled هر بار چک می‌شود؛ true یعنی کاربر لغو کرده است.
     * @return true فقط وقتی فایل نهایی با اندازه و هش درست سر جایش باشد.
     */
    fun download(
        urls: List<String>,
        target: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        if (target.exists() && target.length() == expectedSize && sha256(target) == expectedSha256) {
            onProgress(1f); return true
        }
        for (url in urls) {
            if (isCancelled()) return false
            if (tryOne(url, target, expectedSize, expectedSha256, onProgress, isCancelled)) return true
            if (isCancelled()) return false
        }
        return false
    }

    private fun tryOne(
        url: String,
        target: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        return try {
            var done = if (part.exists()) part.length() else 0L
            if (done >= expectedSize) {
                // فایل .part کامل است؛ فقط هش را چک کن.
                if (sha256(part) == expectedSha256) {
                    target.delete()
                    if (part.renameTo(target)) return true
                }
                part.delete(); done = 0L
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Anik/3.4.0 (Android)")
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                // سرور Range را قبول نکرد؛ از اول شروع کن.
                part.delete(); done = 0L
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect(); return false
            }
            val digest = MessageDigest.getInstance("SHA-256")
            if (done > 0) {
                // هشِ بایت‌های قبلی را حساب کن تا هش نهایی درست باشد.
                FileInputStream(part).use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (ins.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
                }
            }
            conn.inputStream.use { input ->
                FileOutputStream(part, done > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        if (isCancelled()) return false
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        onProgress((done.toFloat() / expectedSize).coerceIn(0f, 1f))
                    }
                }
            }
            conn.disconnect()
            if (isCancelled()) return false
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != expectedSize || sha != expectedSha256) {
                // فایل خراب/ناقص — نگهش نمی‌داریم؛ دفعه بعد از اول دانلود می‌شود.
                part.delete(); return false
            }
            target.delete()
            if (!part.renameTo(target)) return false
            onProgress(1f)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun sha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buf = ByteArray(8192)
                var n: Int
                while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            null
        }
    }
}
