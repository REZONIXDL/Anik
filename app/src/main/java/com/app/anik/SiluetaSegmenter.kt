package com.app.anik

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * موتور پیش‌فرض جداسازی (v3.4): Silueta — نسخه فشرده U²-Net کامل.
 *
 * چرا Silueta به‌جای U2NetP:
 *  - کیفیت نزدیک U²-Net کامل (IoU حدود ۰٫۸۷ در برابر ۰٫۷۸ برای U2NetP) با همان
 *    معماری U²-Net؛ لبه‌ها و مو و شیشه را واضح‌تر جدا می‌کند.
 *  - فایل رسمی rembg فقط ۴۳ مگابایت است (وزن‌ها از قبل int8 هستند — به همین
 *    دلیل کوانتایز دوباره هیچی کم نکرد؛ تست شده با onnxruntime روی دو عکس واقعی).
 *  - لایسنس MIT (کاتالوگ rembg) — تجاری آزاد؛ RMBG بریا (CC BY-NC) عمداً
 *    استفاده نشده است.
 *  - ورودی ۳۲۰×۳۲۰ مثل U2NetP، پس روی گوشی‌های میان‌رده هم سریع است.
 *
 * مدل داخل APK نیست (۴۳ مگابایت + ۱۸ مگابایت ONNX Runtime، APK را از ۵۰ مگ
 * رد می‌کرد)؛ کاربر یک بار از داخل اپ دانلودش می‌کند و بعد کاملاً آفلاین است.
 * دانلود اول از ریلیز گیت‌هاب خود پروژه است (در ایران در دسترس)، بعد ریلیز
 * رسمی rembg. تا وقتی دانلود نشده، U2NetP داخل APK جایگزین است.
 *
 * قرارداد مدل (تاییدشده با اجرای واقعی onnxruntime):
 *  - ورودی: float32 ‏[1,3,320,320] ‏NCHW ‏RGB؛ ‏/255 بعد (x-mean)/std با
 *    میانگین/انحراف ImageNet (قرارداد استاندارد rembg — بدون تقسیم maxC).
 *  - خروجی: ۷ خروجی جانبی؛ خروجی اول (ایندکس ۰) ماسک اصلی sigmoid است.
 *  - خروجی sigmoid مستقیم به آلفا تبدیل می‌شود (بدون min-max — خروجی کالیبره
 *    است و کشش min-max یک پیکسل نویزی را به کل ماسک سرایت می‌داد).
 */
object SiluetaSegmenter {

    const val MODEL_FILE = "silueta.onnx"
    const val MODEL_SIZE = 44173029L
    const val MODEL_SHA256 = "75da6c8d2f8096ec743d071951be73b4a8bc7b3e51d9a6625d63644f90ffeedb"
    val MODEL_URLS = listOf(
        "https://github.com/REZONIXDL/Anik/releases/download/models-v3.4.0/silueta.onnx",
        "https://github.com/danielgatis/rembg/releases/download/v0.0.0/silueta.onnx"
    )
    private const val SIZE = 320

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    @Volatile private var appContext: Context? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var inputName: String? = null
    @Volatile private var failed = false
    private val lock = Any()
    private var env: OrtEnvironment? = null

    fun init(context: Context) { appContext = context.applicationContext }

    fun available(): Boolean = ensureLoaded() != null

    /** خطای قبلی را پاک کن تا لود دوباره امتحان شود (مثلاً بعد از دانلود دوباره). */
    fun reset() {
        synchronized(lock) {
            failed = false
            try { session?.close() } catch (_: Throwable) { }
            session = null; inputName = null
        }
    }

    private fun ensureLoaded(): OrtSession? {
        session?.let { return it }
        if (failed) return null
        synchronized(lock) {
            session?.let { return it }
            if (failed) return null
            val ctx = appContext ?: return null
            return try {
                val file = prepareModelFile(ctx) ?: run { failed = true; return null }
                val e = OrtEnvironment.getEnvironment().also { env = it }
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val s = e.createSession(file.absolutePath, opts)
                val name = s.inputNames.first()
                val shape = (s.inputInfo[name]?.info as? ai.onnxruntime.TensorInfo)?.shape
                val outShape = (s.outputInfo.values.firstOrNull()?.info as? ai.onnxruntime.TensorInfo)?.shape
                val inOk = shape != null && shape.size == 4 && shape[1] == 3L && shape[2] == 320L && shape[3] == 320L
                val outOk = outShape != null && outShape.size == 4 && outShape[1] == 1L
                if (!inOk || !outOk) {
                    s.close(); failed = true; null
                } else {
                    inputName = name; session = s; s
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
        val s = ensureLoaded() ?: return null
        val w = sq.width; val h = sq.height
        if (w <= 0 || h <= 0) return null
        return try {
            synchronized(lock) {
                val e = env ?: return null
                val small = Bitmap.createScaledBitmap(sq, SIZE, SIZE, true)
                val px = IntArray(SIZE * SIZE)
                small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
                if (small !== sq) small.recycle()
                val n = SIZE * SIZE
                val bb = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())
                val fb = bb.asFloatBuffer()
                val plane = FloatArray(n)
                for (c in 0 until 3) {
                    val shift = 16 - 8 * c
                    for (i in 0 until n) {
                        val v = ((px[i] shr shift) and 0xFF) / 255f
                        plane[i] = (v - MEAN[c]) / STD[c]
                    }
                    fb.put(plane)
                }
                fb.rewind()
                OnnxTensor.createTensor(e, fb, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
                    s.run(mapOf(inputName!! to t)).use { res ->
                        val out = res[0] as OnnxTensor
                        val floats = FloatArray(n)
                        out.floatBuffer.get(floats)
                        postprocess(floats, w, h)
                    }
                }
            }
        } catch (_: Throwable) { null }
    }

    /** خروجی sigmoid مستقیم به آلفا؛ بدون min-max (خروجی کالیبره است). */
    private fun postprocess(floats: FloatArray, w: Int, h: Int): IntArray? {
        val gray = IntArray(SIZE * SIZE)
        for (i in floats.indices) {
            val v = (floats[i] * 255f).toInt().coerceIn(0, 255)
            gray[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val m = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        m.setPixels(gray, 0, SIZE, 0, 0, SIZE, SIZE)
        val big = Bitmap.createScaledBitmap(m, w, h, true)
        if (big !== m) m.recycle()
        val p = IntArray(w * h)
        big.getPixels(p, 0, w, 0, 0, w, h)
        big.recycle()
        val alpha = IntArray(w * h) { (p[it] shr 16) and 0xFF }
        var fg = 0
        for (a in alpha) if (a > 127) fg++
        val frac = fg.toFloat() / alpha.size
        if (frac < 0.01f || frac > 0.98f) return null
        return alpha
    }

    fun close() {
        synchronized(lock) { try { session?.close() } catch (_: Throwable) {}; session = null }
    }
}
