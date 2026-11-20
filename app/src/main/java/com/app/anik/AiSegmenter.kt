package com.app.anik

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * موتور هوش مصنوعی آفلاین (v3.4) — سه سطح، همه آفلاین بعد از نصب:
 *
 *  ۱. ORMBG (اولترا، دانلودی ~۱۷۶ مگ): بهترین کیفیت — کلاس IS-Net، ورودی ۱۰۲۴.
 *  ۲. Silueta (پیش‌فرض، دانلودی ~۴۳ مگ): نزدیک U²-Net کامل، ورودی ۳۲۰، MIT.
 *  ۳. U2NetP (داخل APK، ۴٫۵۶ مگ): همیشه در دسترس؛ اگر دو مدل بالا نصب
 *     نباشند، همین استفاده می‌شود.
 *
 * اگر هیچ‌کدام در دسترس نباشد، segment() مقدار null می‌دهد و ImageProcessor
 * به‌طور خودکار به موتور کلاسیک برمی‌گردد — اپ هیچ‌وقت به‌خاطر موتور AI
 * کرش نمی‌کند.
 *
 * قرارداد U2NetP (تاییدشده با اجرای واقعی + کارت رسمی litert-community/U-2-Net):
 *  - ورودی: float32 ‏[1,320,320,3] ‏NHWC ‏RGB؛ اول به ۳۲۰×۳۲۰ تغییر اندازه،
 *    بعد هر کانال بر بزرگ‌ترین مقدار کانال در کل عکس تقسیم، سپس
 *    ‏(x - mean) / std با میانگین/انحراف ImageNet.
 *  - خروجی: float32 ‏[1,320,320,1] ‏sigmoid؛ مستقیم به آلفا تبدیل می‌شود
 *    (در v3.4 کشش min-max حذف شد — خروجی sigmoid کالیبره است و min-max یک
 *    پیکسل نویزی را به کل ماسک سرایت می‌داد).
 *
 * مدل U2NetP داخل خود APK است (assets/models/u2netp.tflite، ‏۴٫۵۶ مگابایت)
 * و در اولین استفاده به حافظه داخلی کپی و با SHA-256 تایید می‌شود.
 */
object AiSegmenter {

    private const val MODEL_ASSET = "models/u2netp.tflite"
    private const val MODEL_SHA256 = "1bd35cc6a71451df7a2c6d98ccda17f6b8c397624192436d50e5b0fc62b5b746"
    private const val SIZE = 320
    private const val NUM_THREADS = 4

    // میانگین/انحراف معیار ImageNet — همان مقادیری که مدل با آن‌ها آموزش دیده است.
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var failed = false

    private val lock = Any()

    /** آخرین مدلی که ماسک را ساخت — ImageProcessor برای برچسب موتور می‌خواند. */
    @Volatile
    var lastModel: String = "U2NetP"
        private set

    /** فقط Context برنامه را نگه می‌دارد؛ هیچ ورودی/خروجی اینجا انجام نمی‌شود. */
    fun init(context: Context) {
        appContext = context.applicationContext
        SiluetaSegmenter.init(context)
        OrmbgSegmenter.init(context)
    }

    /** آیا موتور AI آماده است؟ اولین فراخوانی مدل را لود می‌کند. */
    fun available(): Boolean = ensureLoaded() != null

    /** خطای قبلی هر سه موتور را پاک کن تا لود دوباره امتحان شود. */
    fun reset() {
        synchronized(lock) {
            failed = false
            try { interpreter?.close() } catch (_: Throwable) { }
            interpreter = null
        }
        SiluetaSegmenter.reset()
        OrmbgSegmenter.reset()
    }

    private fun ensureLoaded(): Interpreter? {
        interpreter?.let { return it }
        if (failed) return null
        synchronized(lock) {
            interpreter?.let { return it }
            if (failed) return null
            val ctx = appContext ?: return null
            return try {
                val modelFile = prepareModelFile(ctx) ?: run { failed = true; return null }
                val options = Interpreter.Options().setNumThreads(NUM_THREADS).setUseXNNPACK(true)
                val it = Interpreter(modelFile, options)
                // خودآزمایی: ورودی/خروجی باید دقیقاً قرارداد U2NetP باشد؛
                // اگر فایل مدل اشتباهی بسته شده باشد، موتور غیرفعال می‌شود نه اینکه
                // ماسک بی‌معنی تولید کند.
                val inShape = it.getInputTensor(0).shape()
                val outShape = it.getOutputTensor(0).shape()
                val inOk = inShape.contentEquals(intArrayOf(1, SIZE, SIZE, 3)) &&
                    it.getInputTensor(0).dataType() == DataType.FLOAT32
                val outOk = outShape.contentEquals(intArrayOf(1, SIZE, SIZE, 1)) &&
                    it.getOutputTensor(0).dataType() == DataType.FLOAT32
                if (!inOk || !outOk) {
                    it.close(); failed = true; null
                } else {
                    interpreter = it; it
                }
            } catch (_: Throwable) {
                failed = true; null
            }
        }
    }

    /** کپی مدل از assets به حافظه داخلی (یک بار) + تایید SHA-256. */
    private fun prepareModelFile(ctx: Context): File? {
        val dir = File(ctx.filesDir, "models").apply { if (!exists()) mkdirs() }
        val out = File(dir, "u2netp.tflite")
        if (out.exists() && sha256(out) == MODEL_SHA256) return out
        return try {
            ctx.assets.open(MODEL_ASSET).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (sha256(out) == MODEL_SHA256) out else {
                out.delete(); null
            }
        } catch (_: Throwable) {
            out.delete(); null
        }
    }

    private fun sha256(file: File): String? = ModelDownloader.sha256(file)

    /**
     * اجرای مدل روی مربع کاری؛ خروجی آلفای نرم ۰..۲۵۵ در همان اندازه مربع،
     * یا null اگر موتور در دسترس نباشد / خروجی منحط باشد (در این صورت موتور
     * کلاسیک جایگزین می‌شود).
     *
     * این تابع روی رشته صدا زننده اجرا می‌شود؛ ImageProcessor آن را روی
     * Dispatchers.Default صدا می‌زند. Interpreter بین رشته‌ها مشترک نیست،
     * پس استنتاج با synchronized سریال می‌شود (پردازش دسته‌ای هم ترتیبی است).
     */
    fun segment(sq: Bitmap): IntArray? {
        // v3.4: اول ORMBG (اگر کاربر دانلود کرده)، بعد Silueta (پیش‌فرض دانلودی)،
        // وگرنه U2NetP داخل APK با میانگین‌گیری آینه‌ای (TTA).
        OrmbgSegmenter.segment(sq)?.let { lastModel = "ORMBG"; return it }
        SiluetaSegmenter.segment(sq)?.let { lastModel = "Silueta"; return it }
        val a = segmentOnce(sq) ?: return null
        lastModel = "U2NetP"
        val w = sq.width; val h = sq.height
        val m = android.graphics.Matrix().apply { preScale(-1f, 1f) }
        val flipped = Bitmap.createBitmap(sq, 0, 0, w, h, m, true)
        val b = try { segmentOnce(flipped) } finally { if (flipped !== sq) flipped.recycle() } ?: return a
        // b در حالت آینه‌ای است؛ برگردان و با a میانگین بگیر (لبه‌ها پایدارتر می‌شوند)
        return IntArray(w * h) { i ->
            val y = i / w; val x = i % w
            (a[i] + b[y * w + (w - 1 - x)]) / 2
        }
    }

    private fun segmentOnce(sq: Bitmap): IntArray? {
        val it = ensureLoaded() ?: return null
        val w = sq.width; val h = sq.height
        if (w <= 0 || h <= 0) return null
        return try {
            synchronized(lock) {
                val input = preprocess(sq) ?: return null
                val outBuf = ByteBuffer.allocateDirect(SIZE * SIZE * 4)
                    .order(ByteOrder.nativeOrder())
                it.run(input, outBuf)
                outBuf.rewind()
                postprocess(outBuf, w, h)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** تغییر اندازه به ۳۲۰×۳۲۰ + نرمال‌سازی ImageNet (قرارداد تاییدشده مدل). */
    private fun preprocess(sq: Bitmap): ByteBuffer? {
        val small = Bitmap.createScaledBitmap(sq, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (!small.isRecycled && small !== sq) small.recycle()
        var maxC = 1f
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r > maxC) maxC = r.toFloat()
            if (g > maxC) maxC = g.toFloat()
            if (b > maxC) maxC = b.toFloat()
        }
        val buf = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
        for (p in px) {
            val r = ((p shr 16) and 0xFF) / maxC
            val g = ((p shr 8) and 0xFF) / maxC
            val b = (p and 0xFF) / maxC
            buf.putFloat((r - MEAN[0]) / STD[0])
            buf.putFloat((g - MEAN[1]) / STD[1])
            buf.putFloat((b - MEAN[2]) / STD[2])
        }
        buf.rewind()
        return buf
    }

    /**
     * خروجی sigmoid مستقیم به آلفا + برگرداندن ماسک به اندازه مربع کاری با
     * درون‌یابی دوخطی (لبه نرم حفظ می‌شود)؛ خروجی منحط null می‌دهد.
     */
    private fun postprocess(outBuf: ByteBuffer, w: Int, h: Int): IntArray? {
        val floats = FloatArray(SIZE * SIZE)
        outBuf.asFloatBuffer().get(floats)
        val gray = IntArray(SIZE * SIZE)
        for (i in floats.indices) {
            val v = (floats[i] * 255f).toInt().coerceIn(0, 255)
            gray[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val maskBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        maskBmp.setPixels(gray, 0, SIZE, 0, 0, SIZE, SIZE)
        val big = Bitmap.createScaledBitmap(maskBmp, w, h, true)
        maskBmp.recycle()
        val px = IntArray(w * h)
        big.getPixels(px, 0, w, 0, 0, w, h)
        if (big !== maskBmp) big.recycle()
        val alpha = IntArray(w * h) { (px[it] shr 16) and 0xFF }
        // نگه‌بان: اگر ماسک عملاً همه‌چیز یا هیچ‌چیز را سوژه بداند،
        // به‌جای ماسک بی‌معنی، موتور کلاسیک را صدا می‌زنیم.
        var fg = 0
        for (a in alpha) if (a > 127) fg++
        val frac = fg.toFloat() / alpha.size
        if (frac < 0.01f || frac > 0.98f) return null
        return alpha
    }

    fun close() {
        synchronized(lock) {
            try { interpreter?.close() } catch (_: Throwable) { }
            interpreter = null
        }
        SiluetaSegmenter.close()
        OrmbgSegmenter.close()
    }
}
