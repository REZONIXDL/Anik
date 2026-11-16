package com.app.anik

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * موتور تم سایت (v3.2) — از پالت واقعی سایت یک تم کامل می‌سازد: اصلی، فرعی،
 * تاکید، گرادیان پس‌زمینه استودیو و رنگ متن روی هرکدام (با کنتراست WCAG).
 *
 * روش (با الهام از dembrandt در گیت‌هاب، سازگارشده با موبایل — بدون مرورگر):
 * به‌جای یک رنگ تکی، نقش‌های معنایی رنگ از CSS خوانده می‌شوند: گرادیان‌های برند،
 * پس‌زمینه بدنه (روشن/تیره بودن تم)، رنگ دکمه/لینک (تاکید) و فراوانی رنگ‌ها.
 * همه‌چیز روی گوشی و آفلاین محاسبه می‌شود؛ بدون سرور، بدون کلید API.
 *
 * صادقانه: برای استخراج تم سایت، مدل عصبی استانداردی روی گوشی وجود ندارد —
 * این کار ذاتاً تحلیل پالت است، نه تشخیص سوژه؛ پس موتور تم یک تحلیل‌گر
 * پالت هوشمند است (خوشه‌بندی + هارمونی رنگ + کنتراست)، نه یک مدل ML.
 * (مدل‌های جدیدتر جداسازی مثل BiRefNet/ormbg هم بررسی شدند: ۱۷۶ تا ۲۲۴ مگابایت‌اند
 * و در APK جا نمی‌شوند؛ U2NetP با ۴٫۵۶ مگابایت همچنان انتخاب درست برای باندل است.)
 */
object ThemeEngine {

    data class SiteTheme(
        val primary: String,
        val secondary: String,
        val accent: String,
        val gradient: Pair<String, String>,
        val onPrimary: String,
        val onAccent: String,
        val isDark: Boolean,
        val swatches: List<String>,
        val note: String
    )

    private fun parseHex(h: String): Int = runCatching { Color.parseColor(h) }.getOrElse { Color.parseColor("#7C3AED") }
    private fun toHex(rgb: Int): String = String.format("#%06X", rgb and 0xFFFFFF)

    private fun hsv(rgb: Int): FloatArray {
        val a = FloatArray(3)
        Color.RGBToHSV(Color.red(rgb), Color.green(rgb), Color.blue(rgb), a)
        return a
    }

    private fun hueDist(a: Int, b: Int): Float {
        val d = abs(hsv(a)[0] - hsv(b)[0])
        return min(d, 360f - d)
    }

    /** روشن/تیره کردن افزایشی — همان قرارداد shade در ImageProcessor */
    fun shade(hex: String, pct: Int): String {
        val c = parseHex(hex)
        val d = (pct / 100f * 255).roundToInt()
        return toHex(Color.rgb((Color.red(c) + d).coerceIn(0, 255), (Color.green(c) + d).coerceIn(0, 255), (Color.blue(c) + d).coerceIn(0, 255)))
    }

    private fun luminance(rgb: Int): Double {
        fun lin(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(Color.red(rgb)) + 0.7152 * lin(Color.green(rgb)) + 0.0722 * lin(Color.blue(rgb))
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = max(luminance(a), luminance(b)); val l2 = min(luminance(a), luminance(b))
        return (l1 + 0.05) / (l2 + 0.05)
    }

    /** رنگ متن روی یک رنگ: سفید یا مشکی — هرکدام کنتراست بهتری بدهد (هدف ۴٫۵) */
    fun onColor(hex: String): String {
        val c = parseHex(hex)
        val white = contrast(c, Color.WHITE); val black = contrast(c, Color.BLACK)
        return if (white >= black) "#FFFFFF" else "#111111"
    }

    fun build(d: ColorDetector.Detection): SiteTheme {
        val primary = d.palette.firstOrNull()?.let { toHex(it.rgb) } ?: d.color
        val pRgb = parseHex(primary)
        // فرعی: متفاوت‌ترین رنگ پالت از نظر ته‌رنگ (با امتیاز بالا)؛ اگر نبود، سایه تیره‌تر اصلی
        val secondary = d.palette.drop(1)
            .filter { hueDist(it.rgb, pRgb) >= 25f }
            .maxByOrNull { hueDist(it.rgb, pRgb) * 0.5 + it.score * 0.02 }
            ?.let { toHex(it.rgb) } ?: shade(primary, -25)
        val sRgb = parseHex(secondary)
        // تاکید: اشباع‌ترین رنگِ متفاوت از هر دو؛ اگر نبود، رنگ مکمل اصلی
        val accent = d.palette
            .filter { hueDist(it.rgb, pRgb) >= 40f && hueDist(it.rgb, sRgb) >= 40f }
            .maxByOrNull { hsv(it.rgb)[1] * 0.6 + it.score * 0.02 }
            ?.let { toHex(it.rgb) } ?: run {
                val h = hsv(pRgb)
                toHex(Color.HSVToColor(floatArrayOf((h[0] + 180f) % 360f, max(h[1], 0.75f), max(h[2], 0.85f))))
            }
        val gradient = d.gradient ?: (shade(primary, 14) to shade(secondary, -18))
        val swatches = d.palette.map { toHex(it.rgb) }.distinct().take(5)
        val note = buildString {
            append(if (d.isDark) "تم تیره" else "تم روشن")
            if (d.gradient != null) append(" · گرادیان واقعی سایت")
            if (d.bodyBg != null) append(" · پس‌زمینه سایت ${d.bodyBg}")
        }
        return SiteTheme(primary, secondary, accent, gradient, onColor(primary), onColor(accent), d.isDark, swatches, note)
    }
}
