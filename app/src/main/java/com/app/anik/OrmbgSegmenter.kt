package com.app.anik

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * موتور اولترا (v3.4): ORMBG — نسخه باز IS-Net برای برش واقعی سوژه.
 *
 * چرا ORMBG:
 *  - بهترین کیفیت آزاد روی گوشی: کلاس IS-Net (IoU حدود ۰٫۹۹ در بنچمارک‌ها)،
 *    لبه‌های مو و شیشه را خیلی بهتر از U²-Net جدا می‌کند.
 *  - فایل LiteRT آماده و رسمی (litert-community) با ۲۴۶ میلی‌ثانیه روی GPU
 *    پیکسل 8a؛ اینجا با XNNPACK روی CPU اجرا می‌شود تا روی همه گوشی‌ها کار
 *    کند (GPU delegate نسخه ۲٫x می‌خواهد؛ ارتقای بعدی).
 *  - لایسنس Apache-2.0 صریح برای مدل و وزن‌ها — برخلاف IS-Net خام که لایسنس
 *    وزنش «استفاده عمومی» مبهم بود و برخلاف RMBG بریا که تجاری نیست.
 *  - ۱۷۶ مگابایت است، پس فقط دانلودی است؛ هرگز داخل APK نمی‌آید.
 *
 * قرارداد مدل (از راهنمای رسمی john-rocky/LiteRT-Models، تاییدشده با SHA):
 *  - ورودی: float32 ‏[1,3,1024,1024] ‏NCHW ‏RGB؛ فقط ‏/255، بدون mean/std.
 *  - خروجی: float32 ‏[1,1,1024,1024] ‏sigmoid؛ بعد min-max روی کل تصویر
 *    (قرارداد upstream) و برگرداندن به اندازه مربع کاری.
 *  - دانلود اول از ریلیز گیت‌هاب خود پروژه است (در ایران در دسترس)، بعد
 *    Hugging Face به‌عنوان جایگزین.
 */
object OrmbgSegmenter {

    const val MODEL_FILE = "ormbg.tflite"
    const val MODEL_SIZE = 176240148L
    const val MODEL_SHA256 = "f6e359cf35c55e7bf216eb3141f6d76828667a5e8e67a929f53e9f7f38fae030"
    val MODEL_URLS = listOf(
        "https://github.com/REZONIXDL/Anik/releases/download/models-v3.4.0/ormbg.tflite",
        "https://huggingface.co/litert-community/ormbg-LiteRT/resolve/main/ormbg.tflite"
    )
    private const val SIZE = 1024
    private const val NUM_THREADS = 4

    @Volatile private var appContext: Context? = null
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var failed = false
    private val lock = Any()

    fun init(context: Context) { appContext = context.applicationContext }

    fun available(): Boolean = ensureLoaded() != null

    /** خطای قبلی را پاک کن تا لود دوباره امتحان شود. */
    fun reset() {
        synchronized(lock) {
            failed = false
            try { interpreter?.close() } catch (_: Throwable) { }
            interpreter = null
        }
    }

    private fun ensureLoaded(): Interpreter? {
        interpreter?.let { return it }
        if (failed) return null
        synchronized(lock) {
            interpreter?.let { return it }
            if (failed) return null
            val ctx = appContext ?: return null
            return try {
                val file = prepareModelFile(ctx) ?: run { failed = true; return null }
                val options = Interpreter.Options().setNumThreads(NUM_THREADS).setUseXNNPACK(true)
                val it = Interpreter(file, options)
                val inShape = it.getInputTensor(0).shape()
                val outShape = it.getOutputTensor(0).shape()
                val inOk = inShape.contentEquals(intArrayOf(1, 3, SIZE, SIZE)) &&
                    it.getInputTensor(0).dataType() == DataType.FLOAT32
                val outOk = outShape.contentEquals(intArrayOf(1, 1, SIZE, SIZE)) &&
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

    private fun modelFile(ctx: Context) =
        File(File(ctx.filesDir, "models").apply { if (!exists()) mkdirs() }, MODEL_FILE)

    /** فایل فقط وقتی قبول است که اندازه و SHA-256 دقیقاً درست باشد. */
    private fun prepareModelFile(ctx: Context): File? =
        modelFile(ctx).takeIf { it.exists() && it.length() == MODEL_SIZE && ModelDownloader.sha256(it) == MODEL_SHA256 }

    fun isInstalled(ctx: Context): Boolean = prepareModelFile(ctx.applicationContext) != null

    /** دانلود + تایید SHA-256 (با ادامه دانلود و لغو). روی رشته IO صدا بزن. */
    fun download(ctx: Context, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): Boolean {
        val c = ctx.applicationContext
        val ok = ModelDownloader.download(MODEL_URLS, modelFile(c), MODEL_SIZE, MODEL_SHA256, onProgress, isCancelled)
        if (ok) reset()
        return ok
    }

    fun remove(ctx: Context) {
        close(); modelFile(ctx.applicationContext).delete(); File(ctx.filesDir, "models/$MODEL_FILE.part").delete(); failed = false
    }

    /** خروجی: آلفای ۰..۲۵۵ هم‌اندازه مربع کاری، یا null. */
    fun segment(sq: Bitmap): IntArray? {
        val it = ensureLoaded() ?: return null
        val w = sq.width; val h = sq.height
        if (w <= 0 || h <= 0) return null
        return try {
            synchronized(lock) {
                val input = preprocess(sq) ?: return null
                val outBuf = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
                it.run(input, outBuf)
                outBuf.rewind()
                postprocess(outBuf, w, h)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** تغییر اندازه به ۱۰۲۴×۱۰۲۴ + فقط /255 (قرارداد ORMBG؛ بدون mean/std). */
    private fun preprocess(sq: Bitmap): ByteBuffer? {
        val small = Bitmap.createScaledBitmap(sq, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== sq) small.recycle()
        val buf = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        val plane = FloatArray(SIZE * SIZE)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in px.indices) plane[i] = ((px[i] shr shift) and 0xFF) / 255f
            fb.put(plane)
        }
        buf.rewind()
        return buf
    }

    /** min-max روی خروجی sigmoid (قرارداد upstream) + برگرداندن به اندازه مربع کاری. */
    private fun postprocess(outBuf: ByteBuffer, w: Int, h: Int): IntArray? {
        val floats = FloatArray(SIZE * SIZE)
        outBuf.asFloatBuffer().get(floats)
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (v in floats) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val range = maxV - minV
        if (range < 0.1f) return null
        val gray = IntArray(SIZE * SIZE)
        for (i in floats.indices) {
            val v = ((floats[i] - minV) / range * 255f).toInt().coerceIn(0, 255)
            gray[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val maskBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        maskBmp.setPixels(gray, 0, SIZE, 0, 0, SIZE, SIZE)
        val big = Bitmap.createScaledBitmap(maskBmp, w, h, true)
        maskBmp.recycle()
        val px = IntArray(w * h)
        big.getPixels(px, 0, w, 0, 0, w, h)
        big.recycle()
        val alpha = IntArray(w * h) { (px[it] shr 16) and 0xFF }
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
    }
}
