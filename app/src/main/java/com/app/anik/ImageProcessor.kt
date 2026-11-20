package com.app.anik

import android.graphics.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class BgMode { SIMPLE, BRAND }
enum class ShadowStyle { CONTACT, FLOATING, REFLECTION }

class Subject(val bmp: Bitmap, val bottoms: IntArray, val maxBottom: Int, val ox: Int, val oy: Int)
/** نتیجه جداسازی محصول؛ sq و alpha برای اصلاح لمسی و رندر دوباره نگه داشته می‌شوند */
class Prepared(val subject: Subject, val engine: String, val note: String?, val sq: Bitmap, val alpha: IntArray)
class ProcessedResult(val bitmap: Bitmap, val brandColor: String?, val mode: BgMode, val engine: String, val note: String?, val subjectRect: Rect)

/** فقط بزرگ‌ترین قطعه متصل (و قطعات بزرگ) را نگه می‌دارد؛ bg=true یعنی پس‌زمینه */
internal fun dropSmallComponents(bg: BooleanArray, w: Int, h: Int) {
    val p = w * h
    val label = IntArray(p)
    val sizes = ArrayList<Int>(); sizes.add(0)
    var lab = 0
    val cq = IntArray(p)
    for (s in 0 until p) {
        if (bg[s] || label[s] != 0) continue
        lab++
        var head = 0; var tail = 0
        cq[tail++] = s; label[s] = lab
        while (head < tail) {
            val e = cq[head++]
            val x = e % w; val y = e / w
            if (x > 0 && !bg[e - 1] && label[e - 1] == 0) { label[e - 1] = lab; cq[tail++] = e - 1 }
            if (x < w - 1 && !bg[e + 1] && label[e + 1] == 0) { label[e + 1] = lab; cq[tail++] = e + 1 }
            if (y > 0 && !bg[e - w] && label[e - w] == 0) { label[e - w] = lab; cq[tail++] = e - w }
            if (y < h - 1 && !bg[e + w] && label[e + w] == 0) { label[e + w] = lab; cq[tail++] = e + w }
        }
        sizes.add(tail)
    }
    var bestLab = 0; var bestSize = 0
    for (l in 1..lab) if (sizes[l] > bestSize) { bestSize = sizes[l]; bestLab = l }
    val minSize = max(300f * p / 640000f, bestSize * 0.08f)
    for (i in 0 until p) {
        val l = label[i]
        if (l > 0 && l != bestLab && sizes[l] < minSize) bg[i] = true
    }
}

object ImageProcessor {

    const val WORK = 800

    /** مربع کاری: به‌جای برش وسط عکس، کل عکس را با حفظ نسبت در مربع جا می‌دهد.
     *  (در v3.0 برش وسط بود و عکس‌های کشیده/عمودی — که بیشتر عکس‌های گوشی‌اند —
     *  از دو طرف بریده می‌شدند؛ مثلاً پاشنه و نوک کفش از کادر می‌افتاد.)
     *  فضای خالی با میانگین رنگ چهار گوشه عکس پر می‌شود تا موتور کلاسیک
     *  (که رنگ پس‌زمینه را از گوشه‌ها می‌خواند) به‌هم نریزد. */
    fun fitSquare(src: Bitmap, size: Int): Bitmap {
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val corners = intArrayOf(
            src.getPixel(0, 0), src.getPixel(src.width - 1, 0),
            src.getPixel(0, src.height - 1), src.getPixel(src.width - 1, src.height - 1)
        )
        var r = 0; var g = 0; var b = 0
        for (c in corners) { r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
        val pad = Color.rgb(r / 4, g / 4, b / 4)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(pad)
        c.drawBitmap(scaled, (size - w) / 2f, (size - h) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        scaled.recycle()
        return out
    }

    // ───────── تبدیل sRGB به Lab (فاصله رنگی ادراکی؛ خیلی دقیق‌تر از RGB) ─────────
    private fun rgbToLab(r: Int, g: Int, b: Int, out: FloatArray, i: Int) {
        fun lin(c: Float): Float {
            val v = c / 255f
            return if (v > 0.04045f) ((v + 0.055f) / 1.055f).pow(2.4f) else v / 12.92f
        }
        val rl = lin(r.toFloat()); val gl = lin(g.toFloat()); val bl = lin(b.toFloat())
        val x = (rl * 0.4124f + gl * 0.3576f + bl * 0.1805f) / 0.95047f
        val y = (rl * 0.2126f + gl * 0.7152f + bl * 0.0722f) / 1.0f
        val z = (rl * 0.0193f + gl * 0.1192f + bl * 0.9505f) / 1.08883f
        fun f(t: Float) = if (t > 0.008856f) t.pow(1f / 3f) else 7.787f * t + 16f / 116f
        val fx = f(x); val fy = f(y); val fz = f(z)
        out[i] = 116f * fy - 16f
        out[i + 1] = 500f * (fx - fy)
        out[i + 2] = 200f * (fy - fz)
    }

    // ───────── آستانه اوتسو روی هیستوگرام (خودکار، بدون عدد ثابت) ─────────
    private fun otsu(values: FloatArray, maxV: Float): Float {
        val bins = 256
        val hist = IntArray(bins)
        for (v in values) {
            val b = ((v / maxV) * (bins - 1)).toInt().coerceIn(0, bins - 1)
            hist[b]++
        }
        var sum = 0.0
        for (i in hist.indices) sum += i.toDouble() * hist[i]
        var sumB = 0.0; var wB = 0; var maxVar = -1.0; var thr = 0
        val total = values.size
        for (i in hist.indices) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > maxVar) { maxVar = v; thr = i }
        }
        return thr.toFloat() / (bins - 1) * maxV
    }

    // ───────── مورفولوژی باینری ۳×۳ روی ماسک پیش‌زمینه ─────────
    private fun erode(fg: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(fg.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            out[i] = fg[i] && fg[i - 1] && fg[i + 1] && fg[i - w] && fg[i + w] &&
                fg[i - w - 1] && fg[i - w + 1] && fg[i + w - 1] && fg[i + w + 1]
        }
        return out
    }

    private fun dilate(fg: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(fg.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            out[i] = fg[i] || fg[i - 1] || fg[i + 1] || fg[i - w] || fg[i + w] ||
                fg[i - w - 1] || fg[i - w + 1] || fg[i + w - 1] || fg[i + w + 1]
        }
        return out
    }

    private fun morphOpen(fg: BooleanArray, w: Int, h: Int) = dilate(erode(fg, w, h), w, h)
    private fun morphClose(fg: BooleanArray, w: Int, h: Int) = erode(dilate(fg, w, h), w, h)

    // ───────── تاری جعبه‌ای جداشدنی روی آلفا (لبه نرم) ─────────
    private fun blurAlpha(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(a.size); val out = IntArray(a.size)
        val n = 2 * r + 1
        for (y in 0 until h) {
            var acc = 0
            for (x in -r..r) acc += a[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc / n
                acc -= a[y * w + (x - r).coerceIn(0, w - 1)]
                acc += a[y * w + (x + r + 1).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var acc = 0
            for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / n
                acc -= tmp[(y - r).coerceIn(0, h - 1) * w + x]
                acc += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    // ───────── موتور هوشمند: پالایش تکراری شبیه GrabCut (ICM) با مدل رنگ Lab ─────────
    // هیستوگرام رنگ پیش‌زمینه/پس‌زمینه + هزینه همواری که روی لبه‌های واقعی کم می‌شود.
    // فقط نوار مرزی را اصلاح می‌کند؛ خالص Kotlin، بدون هیچ کتابخانه native.
    private fun smartRefine(
        lab: FloatArray, alpha: IntArray, edge: FloatArray, w: Int, h: Int
    ): Pair<IntArray, Int> {
        val p = w * h
        val bins = 12
        fun binIdx(l: Float, a: Float, b: Float): Int {
            val bl = ((l / 100f) * bins).toInt().coerceIn(0, bins - 1)
            val ba = (((a + 128f) / 256f) * bins).toInt().coerceIn(0, bins - 1)
            val bb = (((b + 128f) / 256f) * bins).toInt().coerceIn(0, bins - 1)
            return (bl * bins + ba) * bins + bb
        }
        val fgH = FloatArray(bins * bins * bins)
        val bgH = FloatArray(bins * bins * bins)
        var fgN = 0; var bgN = 0
        for (i in 0 until p) {
            val b = binIdx(lab[i * 3], lab[i * 3 + 1], lab[i * 3 + 2])
            when {
                alpha[i] > 220 -> { fgH[b] += 1f; fgN++ }
                alpha[i] < 35 -> { bgH[b] += 1f; bgN++ }
            }
        }
        if (fgN < 1000 || bgN < 1000) return alpha to 0
        val nb = bins * bins * bins
        val lnFg = FloatArray(nb) { kotlin.math.ln((fgH[it] + 0.5f) / (fgN + 0.5f * nb)) }
        val lnBg = FloatArray(nb) { kotlin.math.ln((bgH[it] + 0.5f) / (bgN + 0.5f * nb)) }

        // نوار مرزی: پیکسل‌هایی که برچسب‌شان با همسایه فرق می‌کند + ۲ پیکسل اطراف
        val label = BooleanArray(p) { alpha[it] > 127 }
        val band = BooleanArray(p)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (label[i] != label[i - 1] || label[i] != label[i + 1] ||
                label[i] != label[i - w] || label[i] != label[i + w]
            ) {
                for (dy in -2..2) for (dx in -2..2) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) band[yy * w + xx] = true
                }
            }
        }
        val lambda = 14f
        var changed = 0
        repeat(3) {
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (!band[i]) continue
                val b = binIdx(lab[i * 3], lab[i * 3 + 1], lab[i * 3 + 2])
                val costFg = -lnFg[b]
                val costBg = -lnBg[b]
                var smoothFg = 0f; var smoothBg = 0f
                // هزینه همواری برای هر همسایه با برچسب مخالف؛ روی لبه قوی تقریباً صفر
                val e = min(1f, edge[i] / 25f)
                val wgt = lambda * (1f - e)
                if (!label[i - 1]) smoothFg += wgt else smoothBg += wgt
                if (!label[i + 1]) smoothFg += wgt else smoothBg += wgt
                if (!label[i - w]) smoothFg += wgt else smoothBg += wgt
                if (!label[i + w]) smoothFg += wgt else smoothBg += wgt
                val wantFg = costFg + smoothFg < costBg + smoothBg
                if (wantFg != label[i]) { label[i] = wantFg; changed++ }
            }
        }
        if (changed == 0) return alpha to 0
        val bin2 = IntArray(p) { if (label[it]) 255 else 0 }
        return blurAlpha(bin2, w, h, 2) to changed
    }

    // ساخت سوژه (برش به کادر محصول) از مربع کاری و آلفا
    private fun buildSubject(sq: Bitmap, alpha: IntArray): Pair<Subject, String?> {
        val w = sq.width; val h = sq.height; val p = w * h
        val px = IntArray(p); sq.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = 0; var maxY = 0; var count = 0
        for (i in 0 until p) if (alpha[i] > 8) {
            val x = i % w; val y = i / w
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            count++
        }
        var note: String? = null
        if (count < p * 0.02 || minX >= maxX || minY >= maxY) {
            minX = (w * 0.12).roundToInt(); maxX = (w * 0.88).roundToInt()
            minY = (h * 0.12).roundToInt(); maxY = (h * 0.88).roundToInt()
            for (i in 0 until p) {
                val x = i % w; val y = i / w
                alpha[i] = if (x < minX || x > maxX || y < minY || y > maxY) 0 else 255
            }
            note = "پس‌زمینه این عکس به‌سختی قابل جداسازی بود؛ برای نتیجه بهتر از عکسی با پس‌زمینه ساده‌تر استفاده کنید."
        }
        val out = IntArray(p) { (alpha[it] shl 24) or (px[it] and 0x00FFFFFF) }
        val masked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        masked.setPixels(out, 0, w, 0, 0, w, h)
        val D = max(maxX - minX + 1, 10).coerceAtMost(w - minX)
        val H = max(maxY - minY + 1, 10).coerceAtMost(h - minY)
        val cropped = Bitmap.createBitmap(masked, minX, minY, D, H)
        val bottoms = IntArray(D); var maxBottom = 0
        for (t in 0 until D) {
            var bt = -1
            for (r in H - 1 downTo 0) {
                if (alpha[(minY + r) * w + minX + t] > 60) { bt = r; break }
            }
            bottoms[t] = bt
            if (bt > maxBottom) maxBottom = bt
        }
        return Subject(cropped, bottoms, maxBottom, minX, minY) to note
    }

    /** مرحله ۱: جداسازی محصول از پس‌زمینه — اول هوش مصنوعی (U2NetP)، اگر نبود کلاسیک قوی + پالایش هوشمند */
    fun prepare(src: Bitmap): Prepared {
        val sq = fitSquare(src, WORK)
        val w = sq.width; val h = sq.height; val p = w * h
        val px = IntArray(p); sq.getPixels(px, 0, w, 0, 0, w, h)
        val lab = FloatArray(p * 3)
        for (i in 0 until p) rgbToLab((px[i] shr 16) and 255, (px[i] shr 8) and 255, px[i] and 255, lab, i * 3)

        // لبه سوبل روی کانال L + کروما (اشباع) برای سد لبه — برای پالایش هوشمند هر دو مسیر لازم است
        val edge = FloatArray(p)
        for (y in 1 until h - 1) {
            val t = y * w; val n = (y - 1) * w; val r = (y + 1) * w
            for (x in 1 until w - 1) {
                val i = t + x
                val gx = -lab[(n + x - 1) * 3] + lab[(n + x + 1) * 3] - 2 * lab[(t + x - 1) * 3] + 2 * lab[(t + x + 1) * 3] - lab[(r + x - 1) * 3] + lab[(r + x + 1) * 3]
                val gy = -lab[(n + x - 1) * 3] - 2 * lab[(n + x) * 3] - lab[(n + x + 1) * 3] + lab[(r + x - 1) * 3] + 2 * lab[(r + x) * 3] + lab[(r + x + 1) * 3]
                val s = sqrt(gx * gx + gy * gy)
                val chroma = sqrt(lab[i * 3 + 1] * lab[i * 3 + 1] + lab[i * 3 + 2] * lab[i * 3 + 2])
                edge[i] = if (s > 16f || (s > 9f && chroma > 25f)) s else 0f
            }
        }

        // ───── موتور هوش مصنوعی (v3.1): اول U2NetP امتحان می‌شود ─────
        // segment() وقتی null می‌دهد که مدل لود نشده باشد یا ماسک منحط باشد؛
        // در آن صورت بدون هیچ خطایی به مسیر کلاسیک می‌رویم.
        val aiAlpha = AiSegmenter.segment(sq)
        val alpha: IntArray
        val engine: String
        if (aiAlpha != null) {
            val bgAi = BooleanArray(p) { aiAlpha[it] <= 127 }
            dropSmallComponents(bgAi, w, h)
            val cleaned = IntArray(p) { if (bgAi[it]) 0 else aiAlpha[it] }
            val (refinedAi, _) = smartRefine(lab, cleaned, edge, w, h)
            alpha = refinedAi
            engine = if (AiSegmenter.lastModel == "ORMBG") "هوش مصنوعی پرو" else "هوش مصنوعی"
        } else {
            // رنگ پس‌زمینه = میانگین Lab گوشه‌ها
            var mL = 0f; var mA = 0f; var mB = 0f; var cnt = 0
            for (e in 0 until 4) for (t in 0 until 4) {
                for (i in intArrayOf(e * w + t, e * w + (w - 1 - t), (h - 1 - e) * w + t, (h - 1 - e) * w + (w - 1 - t))) {
                    mL += lab[i * 3]; mA += lab[i * 3 + 1]; mB += lab[i * 3 + 2]; cnt++
                }
            }
            mL /= cnt; mA /= cnt; mB /= cnt

            // نقشه فاصله ΔE از رنگ پس‌زمینه + آستانه اوتسو
            val dist = FloatArray(p)
            var maxD = 1f
            for (i in 0 until p) {
                val dL = lab[i * 3] - mL; val dA = lab[i * 3 + 1] - mA; val dB = lab[i * 3 + 2] - mB
                val d = sqrt(dL * dL + dA * dA + dB * dB)
                dist[i] = d
                if (d > maxD) maxD = d
            }
            val thr = otsu(dist, maxD).coerceIn(12f, 45f)

            // سیل از لبه‌های عکس در فضای Lab (با تحمل گرادیان پس‌زمینه)؛
            // فقط پیکسل‌های لبه که واقعاً شبیه پس‌زمینه‌اند بذر سیل‌اند، تا محصولِ چسبیده به لبه
            // اشتباهاً پس‌زمینه حساب نشود (اگر بیش از ۳۵٪ لبه محصول باشد، پایین‌تر به اوتسو برمی‌گردیم)
            val bg = BooleanArray(p)
            val queue = IntArray(p + 2 * (w + h) + 8)
            var qs = 0; var qe = 0
            fun seed(i: Int) {
                if (!bg[i] && edge[i] <= 0f && dist[i] < thr) { bg[i] = true; queue[qe++] = i }
            }
            for (e in 0 until w) { seed(e); seed((h - 1) * w + e) }
            for (e in 0 until h) { seed(e * w); seed(e * w + (w - 1)) }
            val nb = IntArray(4)
            while (qs < qe) {
                val e = queue[qs++]
                val x = e % w; val y = e / w
                var nc = 0
                if (x > 0) nb[nc++] = e - 1
                if (x < w - 1) nb[nc++] = e + 1
                if (y > 0) nb[nc++] = e - w
                if (y < h - 1) nb[nc++] = e + w
                for (k in 0 until nc) {
                    val q = nb[k]
                    if (bg[q] || edge[q] > 0f) continue
                    val dBorder = dist[q]
                    val dLocal = sqrt(
                        (lab[q * 3] - lab[e * 3]).pow(2) +
                            (lab[q * 3 + 1] - lab[e * 3 + 1]).pow(2) +
                            (lab[q * 3 + 2] - lab[e * 3 + 2]).pow(2)
                    )
                    if (dBorder < thr || (dBorder < thr * 1.6f && dLocal < 10f)) {
                        bg[q] = true; queue[qe++] = q
                    }
                }
            }

            // اگر محصول به لبه عکس چسبیده باشد، فرض «پس‌زمینه از لبه شروع می‌شود» غلط است:
            // در آن صورت مستقیم با آستانه اوتسو جدا می‌کنیم.
            var borderFg = 0; var borderTotal = 0
            for (e in 0 until w) { borderTotal += 2; if (!bg[e]) borderFg++; if (!bg[(h - 1) * w + e]) borderFg++ }
            for (e in 0 until h) { borderTotal += 2; if (!bg[e * w]) borderFg++; if (!bg[e * w + (w - 1)]) borderFg++ }
            if (borderFg.toFloat() / borderTotal > 0.35f) {
                for (i in 0 until p) bg[i] = dist[i] < thr
            }

            // تمیزکاری مورفولوژیک + حذف جزیره‌های کوچک
            var fg = BooleanArray(p) { !bg[it] }
            fg = morphClose(morphOpen(fg, w, h), w, h)
            for (i in 0 until p) bg[i] = !fg[i]
            dropSmallComponents(bg, w, h)

            // آلفا با لبه نرم (تاری جعبه‌ای)
            val binary = IntArray(p) { if (bg[it]) 0 else 255 }
            var alphaClassic = blurAlpha(binary, w, h, 2)

            // موتور هوشمند: پالایش تکراری نوار مرزی
            val (refined, changed) = smartRefine(lab, alphaClassic, edge, w, h)
            alphaClassic = refined
            alpha = alphaClassic
            engine = if (changed > p * 0.003) "هوشمند" else "کلاسیک"
        }

        val (subject, note) = buildSubject(sq, alpha)
        return Prepared(subject, engine, note, sq, alpha)
    }

    /** اصلاح لمسی: لمس روی پیش‌نمایش، ماسک را همان‌جا اصلاح می‌کند و سوژه را دوباره می‌سازد */
    fun refineTouch(prep: Prepared, x: Float, y: Float, radius: Float, foreground: Boolean): Prepared {
        val w = WORK; val h = WORK
        val alpha = prep.alpha.copyOf()
        val cx = x.toInt().coerceIn(0, w - 1)
        val cy = y.toInt().coerceIn(0, h - 1)
        val r = radius.toInt().coerceAtLeast(4)
        for (dy in -r..r) for (dx in -r..r) {
            val d2 = dx * dx + dy * dy
            if (d2 > r * r) continue
            val px = cx + dx; val py = cy + dy
            if (px !in 0 until w || py !in 0 until h) continue
            val t = 1f - sqrt(d2.toFloat()) / r
            val k = t * t * (3 - 2 * t)
            val target = if (foreground) 255 else 0
            val idx = py * w + px
            alpha[idx] = (alpha[idx] + (target - alpha[idx]) * k).roundToInt().coerceIn(0, 255)
        }
        val softened = blurAlpha(alpha, w, h, 1)
        val (subject, note) = buildSubject(prep.sq, softened)
        return Prepared(subject, "لمسی", note, prep.sq, softened)
    }

    private fun shade(hex: String, pct: Int): Int {
        val c = Color.parseColor(hex)
        val d = (pct / 100f * 255).roundToInt()
        return Color.rgb((Color.red(c) + d).coerceIn(0, 255), (Color.green(c) + d).coerceIn(0, 255), (Color.blue(c) + d).coerceIn(0, 255))
    }

    private fun blackA(a: Float) = Color.argb((a * 255).roundToInt().coerceIn(0, 255), 0, 0, 0)

    private fun radialPaint(cx: Float, cy: Float, r: Float, stops: List<Pair<Float, Int>>) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, max(r, 1f), stops.map { it.second }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
        }

    /** جایگذاری سوژه روی بوم خروجی — همان محاسبه render()؛ برای هم‌ترازی پیش‌نمایش «قبل». */
    data class Placement(val scale: Float, val dx: Int, val dy: Int, val wpx: Int, val hpx: Int, val ground: Int)

    private fun computePlacement(prep: Prepared, shadow: ShadowStyle, width: Int, height: Int): Placement {
        val subject = prep.subject
        val D = subject.bmp.width; val H = subject.bmp.height
        val s = min(width, height).toFloat()
        val k = s / 1000f
        val r = s * 0.8f
        var scale = min(r / max(D, 1), r / max(H, 1))
        if (shadow == ShadowStyle.REFLECTION) {
            // انعکاس ۴۲٪ ارتفاع سوژه است و باید کامل در بوم جا شود؛
            // در غیر این صورت انعکاس محصول‌های بلند از پایین بوم بریده می‌شد.
            val maxHpx = (height / 2f + 55f * k + 2f) / 0.92f
            scale = min(scale, maxHpx / max(H, 1))
        }
        val wpx = (D * scale).roundToInt(); val hpx = (H * scale).roundToInt()
        val dx = ((width - wpx) / 2f).roundToInt()
        var dy = ((height - hpx) / 2f - 15f * k).roundToInt()
        if (shadow == ShadowStyle.FLOATING) dy = max((40f * k).roundToInt(), dy - (32f * k).roundToInt())
        else if (shadow == ShadowStyle.REFLECTION) dy = max((35f * k).roundToInt(), dy - (40f * k).roundToInt())
        val ground = dy + (subject.maxBottom * scale).roundToInt()
        return Placement(scale, dx, dy, wpx, hpx, ground)
    }

    /** پیش‌نمایش «قبل» هم‌تراز با خروجی: مربع کاری با همان جایگذاری رندر روی بوم خروجی. */
    fun buildBeforePreview(prep: Prepared, width: Int, height: Int, shadow: ShadowStyle): Bitmap {
        val pl = computePlacement(prep, shadow, width, height)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val m = android.graphics.Matrix().apply {
            postScale(pl.scale, pl.scale)
            postTranslate(pl.dx - prep.subject.ox * pl.scale, pl.dy - prep.subject.oy * pl.scale)
        }
        c.drawBitmap(prep.sq, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** مرحله ۲: ساخت بوم با پس‌زمینه و سایه — اندازه دلخواه + حالت شفاف */
    fun render(
        prep: Prepared, mode: BgMode, shadow: ShadowStyle, brandHex: String?,
        width: Int = 1000, height: Int = 1000, transparent: Boolean = false,
        brandGradient: Pair<String, String>? = null
    ): ProcessedResult {
        val subject = prep.subject
        val D = subject.bmp.width; val H = subject.bmp.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = min(width, height).toFloat()
        val k = s / 1000f
        val simple = mode == BgMode.SIMPLE
        val brand = if (!simple && !transparent) (brandHex ?: ColorDetector.DEFAULT_COLOR) else null

        if (brand != null) {
            // گرادیان واقعی سایت (اگر پیدا شده) وگرنه گرادیان ساخته‌شده از تم
            val stops = if (brandGradient != null) {
                listOf(0f to shade(brandGradient.first, 18), 0.55f to Color.parseColor(brandGradient.first), 1f to shade(brandGradient.second, -12))
            } else {
                listOf(0f to shade(brand, 18), 0.55f to Color.parseColor(brand), 1f to shade(brand, -12))
            }
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(),
                radialPaint(width / 2f, height * 0.44f, s * 0.72f, stops))
        } else if (!transparent) c.drawColor(Color.WHITE)

        val (scale, dx, dy, wpx, hpx, ground) = computePlacement(prep, shadow, width, height)
        val cxs = dx + wpx / 2f
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        when (shadow) {
            ShadowStyle.REFLECTION -> {
                val refH = max(1, (hpx * 0.42f).roundToInt())
                val rb = Bitmap.createBitmap(max(wpx, 1), refH, Bitmap.Config.ARGB_8888)
                val rc = Canvas(rb)
                rc.save(); rc.translate(0f, hpx.toFloat()); rc.scale(1f, -1f)
                rc.drawBitmap(subject.bmp, null, Rect(0, 0, wpx, hpx), filter)
                rc.restore()
                val gp = Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    shader = LinearGradient(0f, 0f, 0f, refH.toFloat(),
                        intArrayOf(blackA(if (simple) 0.28f else 0.36f), blackA(0.08f), blackA(0f)),
                        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                }
                rc.drawRect(0f, 0f, wpx.toFloat(), refH.toFloat(), gp)
                c.drawBitmap(rb, dx.toFloat(), (ground - 2).toFloat(), null)
                rb.recycle()
                val a = wpx * 0.38f
                val p = radialPaint(cxs, ground.toFloat(), a, listOf(0f to blackA(if (simple) 0.16f else 0.32f), 1f to blackA(0f)))
                c.drawOval(RectF(cxs - a, ground - 4f * k, cxs + a, ground + 4f * k), p)
            }
            ShadowStyle.FLOATING -> {
                val t = (ground + 46 * k).toFloat()
                val rx = wpx * 0.36f; val ry = max(16f * k, hpx * 0.045f)
                val stops = if (simple) listOf(0f to blackA(0.22f), 0.55f to blackA(0.08f), 1f to blackA(0f))
                else listOf(0f to blackA(0.42f), 0.6f to blackA(0.14f), 1f to blackA(0f))
                c.drawOval(RectF(cxs - rx, t - ry, cxs + rx, t + ry), radialPaint(cxs, t, rx, stops))
            }
            ShadowStyle.CONTACT -> {
                val rx = wpx * 0.45f; val ry = max(16f * k, hpx * 0.045f)
                val cy = (ground + 3).toFloat()
                val stops = if (simple) listOf(0f to blackA(0.10f), 0.5f to blackA(0.04f), 1f to blackA(0f))
                else listOf(0f to blackA(0.26f), 0.55f to blackA(0.10f), 1f to blackA(0f))
                c.drawOval(RectF(cxs - rx, cy - ry, cxs + rx, cy + ry), radialPaint(cxs, cy, rx, stops))
                val band = max(8, (H * 0.03f).roundToInt())
                val st = if (simple) listOf(0f to blackA(0.24f), 0.5f to blackA(0.08f), 1f to blackA(0f))
                else listOf(0f to blackA(0.42f), 0.55f to blackA(0.14f), 1f to blackA(0f))
                var col = 0
                while (col < D) {
                    val t = subject.bottoms[col]
                    if (t > 0 && subject.maxBottom - t <= band) {
                        val x = (dx + (col * scale).roundToInt()).toFloat()
                        val y = (dy + (t * scale).roundToInt()).toFloat()
                        val a = max(4f, scale * 3.5f)
                        c.drawOval(RectF(x - a * 1.8f, y + 1 - 4f * k, x + a * 1.8f, y + 1 + 4f * k), radialPaint(x, y, a * 2.2f, st))
                    }
                    col += 2
                }
            }
        }
        c.drawBitmap(subject.bmp, null, Rect(dx, dy, dx + wpx, dy + hpx), filter)
        return ProcessedResult(out, brand, mode, prep.engine, prep.note, Rect(dx, dy, dx + wpx, dy + hpx))
    }
}
