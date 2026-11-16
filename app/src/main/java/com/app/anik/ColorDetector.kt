package com.app.anik

import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * تشخیص رنگ برند سایت به روش امتیازدهی چندمنبعی:
 * theme-color، manifest، آیکون/لوگوی سایت (رنگ غالب)، متغیرهای CSS برند، و فراوانی رنگ‌های اشباع‌شده در HTML و CSSهای لینک‌شده.
 * رنگ واقعی سایت برگردانده می‌شود (بدون تبدیل به رنگ‌های از پیش تعریف‌شده).
 */
object ColorDetector {
    val DEFAULT_COLOR = "#7C3AED"

    private val KNOWN = linkedMapOf(
        "rezonix" to "#7C3AED", "digikala" to "#EF394E", "technolife" to "#2563EB",
        "shafa" to "#059669", "mobit" to "#2563EB", "divar" to "#A62626",
        "snapp" to "#00D170", "torob" to "#E5253B", "basalam" to "#FF6A00"
    )
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    class Swatch(val rgb: Int, val score: Double, val source: String)

    class Detection(
        val color: String, val found: Boolean, val source: String, val all: List<String>,
        val palette: List<Swatch> = emptyList(),
        val gradient: Pair<String, String>? = null,
        val bodyBg: String? = null,
        val isDark: Boolean = false
    )

    private class Cand(var rgb: Int) { var bonus = 0.0; var freq = 0; var src = "" }

    private class Pool {
        val items = mutableListOf<Cand>()
        var bodyBg: Int? = null
        val gradientCounts = linkedMapOf<Pair<Int, Int>, Int>()
        val vars = mutableMapOf<String, Int>()
        fun gradient(a: Int, b: Int) {
            if (!brandish(a) || !brandish(b)) return
            val key = a to b
            gradientCounts[key] = (gradientCounts[key] ?: 0) + 1
        }
        fun slot(rgb: Int): Cand? {
            if (!brandish(rgb)) return null
            return items.firstOrNull { dist(it.rgb, rgb) < 26 } ?: Cand(rgb).also { items.add(it) }
        }
        fun bonus(rgb: Int, w: Double, src: String) {
            val c = slot(rgb) ?: return
            if (w > c.bonus) { c.bonus = w; c.src = src; c.rgb = rgb }
        }
        fun count(rgb: Int) {
            val c = slot(rgb) ?: return
            c.freq++
            if (c.src.isEmpty()) c.src = "رنگ‌های CSS سایت"
        }
        fun score(c: Cand): Double {
            val hsv = FloatArray(3); Color.RGBToHSV(Color.red(c.rgb), Color.green(c.rgb), Color.blue(c.rgb), hsv)
            return (c.bonus + min(c.freq, 60) * 0.8) * (0.6 + 0.4 * hsv[1])
        }
    }

    private fun brandish(rgb: Int): Boolean {
        val hsv = FloatArray(3); Color.RGBToHSV(Color.red(rgb), Color.green(rgb), Color.blue(rgb), hsv)
        return hsv[1] >= 0.35f && hsv[2] >= 0.25f
    }

    private fun dist(a: Int, b: Int): Double {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun hex(rgb: Int) = String.format("#%06X", rgb and 0xFFFFFF)

    private fun hslToRgb(h: Double, s: Double, l: Double): Int {
        val hh = ((h % 360) + 360) % 360 / 360.0
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun f(t0: Double): Int {
            var t = t0
            if (t < 0) t += 1.0; if (t > 1) t -= 1.0
            val v = when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
            return (v * 255).toInt().coerceIn(0, 255)
        }
        return Color.rgb(f(hh + 1.0 / 3), f(hh), f(hh - 1.0 / 3))
    }

    private fun parseCss(raw: String): Int? {
        val s = raw.trim().lowercase()
        if (s.startsWith("#")) {
            var h = s.substring(1)
            if (h.length == 3 || h.length == 4) h = h.take(3).map { "$it$it" }.joinToString("")
            if (h.length == 8) h = h.take(6)
            return if (h.length == 6) h.toIntOrNull(16) else null
        }
        Regex("""^rgba?\(\s*(\d{1,3})[\s,]+(\d{1,3})[\s,]+(\d{1,3})""").find(s)?.let {
            val (r, g, b) = it.destructured
            return Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        }
        Regex("""^(?:hsla?\(\s*)?([\d.]+)(?:deg)?[\s,]+([\d.]+)%[\s,]+([\d.]+)%""").find(s)?.let {
            val (h, sat, l) = it.destructured
            return hslToRgb(h.toDouble(), sat.toDouble() / 100, l.toDouble() / 100)
        }
        return null
    }

    private val COLOR_TOKEN = Regex("""#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}|rgba?\([^)]*\)|hsla?\([^)]*\)""")
    private val BG_DECL = Regex("""background(?:-color)?\s*:\s*([^;}\n]+)""", RegexOption.IGNORE_CASE)
    private val COLOR_DECL = Regex("""color\s*:\s*([^;}\n]+)""", RegexOption.IGNORE_CASE)
    private val RULE = Regex("""([^{}]+)\{([^}]*)\}""")

    private fun firstColor(value: String): Int? =
        COLOR_TOKEN.find(value)?.value?.let { parseCss(it) }

    private fun declColor(decl: String, regex: Regex): Int? =
        regex.find(decl)?.groupValues?.get(1)?.let { firstColor(it) }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    private fun tags(html: String, name: String): List<String> =
        Regex("<$name\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()

    private fun meta(html: String, name: String): String? =
        tags(html, "meta").firstOrNull { attr(it, "name")?.equals(name, true) == true }?.let { attr(it, "content") }

    private fun links(html: String, rel: String): List<String> =
        tags(html, "link").filter { (attr(it, "rel") ?: "").lowercase().contains(rel) }.mapNotNull { attr(it, "href") }

    private fun resolve(base: String, href: String): String? = try { URL(URL(base), href).toString() } catch (_: Exception) { null }

    private fun fetch(url: String, max: Int = 600_000, timeout: Int = 6000): ByteArray? {
        try {
            var cur = url
            for (i in 0 until 5) {
                val c = URL(cur).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = timeout; c.readTimeout = timeout
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Accept-Language", "fa,en;q=0.8")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    cur = URL(URL(cur), loc).toString(); c.disconnect(); continue
                }
                if (code !in 200..299) return null
                return c.inputStream.use { ins ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16384)
                    while (out.size() < max) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.toByteArray()
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun scan(text: String, pool: Pool) {
        // تعریف‌های متغیر CSS را جمع کن (برای حل var(--x) در استفاده‌ها)
        Regex("""--([\w-]+)\s*:\s*([^;}\n]+)""").findAll(text).forEach { m ->
            parseCss(m.groupValues[2])?.let { pool.vars[m.groupValues[1].lowercase()] = it }
        }
        // استفاده‌های var(--x) را مثل وقوع واقعی رنگ بشمار — CSS مدرن با var کار می‌کند
        // و فراوانیِ صرفِ متن، رنگ برند اصلی را کم می‌شمرد (دیجی‌کالا: قرمز ۱۳ بار با
        // var استفاده شده بود ولی فقط ۳ بار به‌صورت لفظی آمده بود).
        Regex("""var\(\s*--([\w-]+)""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            pool.vars[m.groupValues[1].lowercase()]?.let { pool.count(it) }
        }
        // گرادیان‌های برند (روش dembrandt: گرادیان واقعی سایت، نه فقط رنگ تکی)
        Regex("""(?:linear|radial)-gradient\(([^)]*)\)""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val stops = Regex("""#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}|rgba?\([^)]*\)|hsla?\([^)]*\)""")
                .findAll(m.groupValues[1]).mapNotNull { parseCss(it.value) }.toList()
            if (stops.size >= 2) pool.gradient(stops.first(), stops[1])
        }
        // قوانین CSS را یک‌بار به‌صورت (سلکتور){اعلان‌ها} می‌خوانیم — نقش‌های معنایی:
        // پس‌زمینه بدنه (روشن/تیره)، رنگ دکمه/لینک (تاکید).
        // توجه: رگکس‌های قبلی با [^{}]*? ابتدای آزاد روی CSS بزرگ بک‌ترک نمایی
        // داشتند (روی یک صفحه ۳۴ کیلوبایتی ~۷۰ ثانیه)؛ این روش خطی است.
        RULE.findAll(text).forEach { m ->
            val parts = m.groupValues[1].split(",").map { it.trim().lowercase() }
            val decl = m.groupValues[2]
            val isBody = parts.any { it == "body" || it.startsWith("body ") || it.startsWith("body.") || it.startsWith("body:") || it.startsWith("body[") }
            val isHtml = parts.any { it == "html" || it.startsWith("html ") || it.startsWith("html.") || it.startsWith("html:") || it.startsWith("html[") }
            if (isBody || isHtml) {
                declColor(decl, BG_DECL)?.let { c -> if (isBody || pool.bodyBg == null) pool.bodyBg = c }
            }
            val isButton = parts.any { it.contains("btn") || it.contains("button") || it.contains("cta") || it.contains("primary") }
            if (isButton) {
                (declColor(decl, BG_DECL) ?: declColor(decl, COLOR_DECL))?.let { pool.bonus(it, 40.0, "رنگ دکمه/لینک سایت") }
            }
            val isLink = parts.any { it == "a" || it.startsWith("a ") || it.startsWith("a.") || it.startsWith("a:") || it.startsWith("a[") || it.startsWith("a>") || it.startsWith("a+") }
            if (isLink) {
                declColor(decl, COLOR_DECL)?.let { pool.bonus(it, 35.0, "رنگ لینک سایت") }
            }
        }
        Regex("""--[\w-]*(?:primary|brand|accent|theme|main)[\w-]*\s*:\s*([^;}\n]+)""", RegexOption.IGNORE_CASE)
            // امتیاز ۵ (نه ۴۵/۲۰): تعریف یک متغیر به‌تنهایی نشانه برند اصلی نیست —
            // متغیر زیربرند (مثل --color-pindo-primary دیجی‌کالا) هم همین الگو را دارد؛
            // وزن واقعی از فراوانیِ استفاده‌ها (بالا) می‌آید.
            .findAll(text).forEach { m -> parseCss(m.groupValues[1])?.let { pool.bonus(it, 5.0, "متغیر رنگ برند در CSS") } }
        Regex("""(?<![\w&])#[0-9a-fA-F]{6}(?!\w)""").findAll(text).forEach { m -> parseCss(m.value)?.let { pool.count(it) } }
        Regex("""rgba?\([^)]*\)|hsla?\([^)]*\)""", RegexOption.IGNORE_CASE).findAll(text).forEach { m -> parseCss(m.value)?.let { pool.count(it) } }
    }

    /** رنگ غالب اشباع‌شده یک تصویر (لوگو) */
    private fun dominant(bytes: ByteArray): Int? {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        var sample = 1
        while (max(bo.outWidth, bo.outHeight) / sample > 160) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val w = DoubleArray(24); val r = DoubleArray(24); val g = DoubleArray(24); val b = DoubleArray(24)
        val hsv = FloatArray(3)
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue
            val k = (hsv[0] / 15f).toInt() % 24
            val wt = (hsv[1] * hsv[2]).toDouble()
            w[k] += wt; r[k] += Color.red(p) * wt; g[k] += Color.green(p) * wt; b[k] += Color.blue(p) * wt
        }
        var best = -1
        for (k in 0 until 24) if (best < 0 || w[k] > w[best]) best = k
        if (best < 0 || w[best] < 4.0) return null
        return Color.rgb((r[best] / w[best]).toInt(), (g[best] / w[best]).toInt(), (b[best] / w[best]).toInt())
    }

    suspend fun detect(rawUrl: String): Detection = coroutineScope {
        var target = rawUrl.trim()
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(target)) target = "https://$target"
        val pool = Pool()
        val htmlBytes = withContext(Dispatchers.IO) { fetch(target, 800_000) }
        if (htmlBytes != null) {
            val html = String(htmlBytes, Charsets.UTF_8)
            meta(html, "theme-color")?.let { c -> parseCss(c)?.let { pool.bonus(it, 100.0, "theme-color سایت") } }
            meta(html, "msapplication-TileColor")?.let { c -> parseCss(c)?.let { pool.bonus(it, 70.0, "رنگ تایل سایت") } }
            scan(html, pool)

            val cssJobs: List<Deferred<ByteArray?>> = links(html, "stylesheet").take(3).mapNotNull { resolve(target, it) }
                .map { async(Dispatchers.IO) { fetch(it, 500_000, 5000) } }
            val manifestJob = links(html, "manifest").firstOrNull()?.let { resolve(target, it) }
                ?.let { async(Dispatchers.IO) { fetch(it, 100_000, 4000) } }
            val iconUrls = mutableListOf<String>()
            links(html, "apple-touch-icon").firstOrNull()?.let { resolve(target, it) }?.let { iconUrls.add(it) }
            links(html, "icon").filter { !it.endsWith(".svg", true) }.firstOrNull()?.let { resolve(target, it) }?.let { iconUrls.add(it) }
            tags(html, "img").firstOrNull { it.contains("logo", true) }?.let { attr(it, "src") }
                ?.takeIf { !it.endsWith(".svg", true) }?.let { resolve(target, it) }?.let { iconUrls.add(it) }
            val iconJobs = iconUrls.distinct().take(3).map { async(Dispatchers.IO) { fetch(it, 1_500_000, 5000) } }

            cssJobs.awaitAll().forEach { it?.let { b -> scan(String(b, Charsets.UTF_8), pool) } }
            manifestJob?.await()?.let { b ->
                runCatching { JSONObject(String(b, Charsets.UTF_8)).optString("theme_color") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { c -> parseCss(c)?.let { pool.bonus(it, 100.0, "manifest سایت") } }
            }
            iconJobs.awaitAll().forEach { it?.let { b -> runCatching { dominant(b) }.getOrNull()?.let { c -> pool.bonus(c, 65.0, "لوگوی سایت") } } }
        }
        val ranked = pool.items.sortedByDescending { pool.score(it) }
        val all = ranked.map { hex(it.rgb) }.distinct().take(8)
        val palette = ranked.map { Swatch(it.rgb, pool.score(it), it.src) }.take(8)
        val gradEntry = pool.gradientCounts.entries.maxByOrNull { it.value }
        val gradient = gradEntry?.key?.let { hex(it.first) to hex(it.second) }
        val bodyBgHex = pool.bodyBg?.let { hex(it) }
        val isDark = pool.bodyBg?.let { rgb ->
            val r = Color.red(rgb) / 255.0; val g = Color.green(rgb) / 255.0; val b = Color.blue(rgb) / 255.0
            (0.2126 * r + 0.7152 * g + 0.0722 * b) < 0.4
        } ?: false
        val best = ranked.firstOrNull()
        when {
            best != null -> Detection(hex(best.rgb), true, best.src.ifEmpty { "رنگ‌های سایت" }, all, palette, gradient, bodyBgHex, isDark)
            else -> {
                val low = target.lowercase()
                val known = KNOWN.entries.firstOrNull { low.contains(it.key) }
                if (known != null) Detection(known.value, true, "برند شناخته‌شده", all)
                else Detection(DEFAULT_COLOR, false, "", all)
            }
        }
    }
}
