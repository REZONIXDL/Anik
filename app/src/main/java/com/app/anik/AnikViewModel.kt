package com.app.anik

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * نگه‌دارنده وضعیت آنیک (v3.4).
 *
 * چرا ViewModel: در v3.3 همه وضعیت‌ها `remember` داخل Composable بودند و با
 * چرخش گوشی (یا مرگ و زندگی دوباره Activity) عکس انتخاب‌شده، نتیجه و بچ
 * کاملاً پاک می‌شد. ViewModel از تغییر پیکربندی جان سالم به در می‌برد؛ پس
 * بیت‌مپ‌ها و نتیجه‌ها اینجا می‌مانند و فقط تنظیمات ساده (رشته/عدد/بولین)
 * در Composable با rememberSaveable نگه داشته می‌شوند.
 *
 * بیت‌مپ‌ها اینجا بازیافت می‌شوند: وقتی عکس/نتیجه/خروجی قدیمی جایگزین شد،
 * بیت‌مپ قبلی recycle می‌شود تا حافظه native انباشته نشود (در v3.3 هیچ
 * بیت‌مپی recycle نمی‌شد و بچ ۶تایی می‌توانست OOM بدهد).
 */
class AnikViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication<Application>().applicationContext

    // ───── وضعیت عکس و نتیجه (بیت‌مپ‌ها — فقط اینجا) ─────
    var srcBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var prepared by mutableStateOf<Prepared?>(null)
        private set
    var result by mutableStateOf<ProcessedResult?>(null)
        private set
    var recentView by mutableStateOf<Bitmap?>(null)
    var recents by mutableStateOf(listOf<Bitmap>())
        private set
    var batchItems by mutableStateOf(listOf<BatchItem>())
        private set
    var batchBusy by mutableStateOf(false)
        private set

    // ───── تنظیمات (Composable هم می‌خواند/می‌نویسد) ─────
    var imageName by mutableStateOf("")
    var websiteUrl by mutableStateOf("")
    var mode by mutableStateOf(BgMode.SIMPLE)
    var shadow by mutableStateOf(ShadowStyle.CONTACT)
    var brandColor by mutableStateOf("#7C3AED")
    var hexText by mutableStateOf("#7C3AED")
    var siteColors by mutableStateOf(listOf<String>())
    var siteTheme by mutableStateOf<ThemeEngine.SiteTheme?>(null)
    var detecting by mutableStateOf(false)
    var detectNote by mutableStateOf("")
    var processing by mutableStateOf(false)
        private set
    var linkUrl by mutableStateOf<String?>(null)
    var linkHost by mutableStateOf("")
    var linkBusy by mutableStateOf(false)
        private set
    var linkError by mutableStateOf<String?>(null)
    var toastMsg by mutableStateOf<String?>(null)
    var transparent by mutableStateOf(false)
    var sizePreset by mutableStateOf(0)
    var compare by mutableStateOf(false)
    var comparePos by mutableStateOf(0.5f)
    var refineMode by mutableStateOf(false)
    var refineFg by mutableStateOf(true)
    var refining by mutableStateOf(false)
        private set

    // ───── مدل‌های دانلودی ─────
    var siluetaInstalled by mutableStateOf(SiluetaSegmenter.isInstalled(app.applicationContext))
        private set
    var siluetaBusy by mutableStateOf(false)
        private set
    var siluetaProgress by mutableStateOf(0f)
        private set
    var ormbgInstalled by mutableStateOf(OrmbgSegmenter.isInstalled(app.applicationContext))
        private set
    var ormbgBusy by mutableStateOf(false)
        private set
    var ormbgProgress by mutableStateOf(0f)
        private set

    private var buildJob: Job? = null
    private var batchJob: Job? = null
    private var siluetaJob: Job? = null
    private var ormbgJob: Job? = null
    @Volatile private var cancelSilueta = false
    @Volatile private var cancelOrmbg = false
    @Volatile private var cancelBatch = false

    fun toast(m: String) { toastMsg = m }

    fun setBrand(c: String) {
        val v = c.trim().uppercase().let { if (it.startsWith("#")) it else "#$it" }
        brandColor = v; hexText = v
    }

    private fun recycleQuietly(bmp: Bitmap?) {
        try { if (bmp != null && !bmp.isRecycled) bmp.recycle() } catch (_: Throwable) { }
    }

    /** نتیجه و آماده‌سازی فعلی را پاک کن (بیت‌مپ‌ها بازیافت می‌شوند). */
    fun resetResult() {
        prepared?.let { recycleQuietly(it.subject.bmp); recycleQuietly(it.sq) }
        prepared = null
        recycleQuietly(result?.bitmap)
        result = null
        linkUrl = null; linkError = null; recentView = null; compare = false; refineMode = false
    }

    fun applyImage(bmp: Bitmap?, name: String) {
        if (bmp == null) { toast("خواندن عکس ناموفق بود"); return }
        buildJob?.cancel()
        recycleQuietly(srcBitmap)
        srcBitmap = bmp; imageName = name; resetResult()
    }

    fun pushRecent(bmp: Bitmap) {
        val evicted = recents.filter { it !== bmp }.drop(3)
        evicted.forEach { recycleQuietly(it) }
        recents = (listOf(bmp) + recents.filter { it !== bmp }).take(4)
    }

    suspend fun renderCurrent(pr: Prepared): ProcessedResult? = withContext(Dispatchers.Default) {
        val (w, h) = SIZE_PRESETS[sizePreset]
        runCatching {
            ImageProcessor.render(pr, mode, shadow, if (mode == BgMode.BRAND && !transparent) brandColor else null, w, h, transparent,
                brandGradient = if (mode == BgMode.BRAND && !transparent) siteTheme?.gradient else null)
        }.getOrNull()
    }

    /** ساخت عکس محصول — اگر وسط پردازش عکس عوض شود، نتیجه عکس قدیمی نشان داده نمی‌شود. */
    fun build() {
        val s = srcBitmap ?: return
        if (processing) return
        buildJob?.cancel()
        buildJob = viewModelScope.launch {
            processing = true
            try {
                val pr = withContext(Dispatchers.Default) { prepared ?: ImageProcessor.prepare(s) }
                if (s !== srcBitmap) return@launch // عکس عوض شده؛ نتیجه قدیمی را نشان نده
                prepared = pr // اگر prepared از قبل بود، pr همان شیء است؛ چیزی برای بازیافت نیست
                val r = renderCurrent(pr)
                if (r != null && s === srcBitmap) {
                    recycleQuietly(result?.bitmap)
                    result = r; linkUrl = null; linkError = null; recentView = null
                    pushRecent(r.bitmap)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Throwable) {
                toast("خطایی در پردازش عکس رخ داد. لطفاً دوباره امتحان کنید.")
            } finally { processing = false }
        }
    }

    /** رندر مجدد زنده با تغییر سایه/حالت/رنگ/اندازه/شفافیت/تم (جداسازی دوباره انجام نمی‌شود). */
    private var renderGen = 0
    fun refreshRender() {
        val pr = prepared ?: return
        if (result == null || processing) return
        val gen = ++renderGen
        viewModelScope.launch {
            val r = renderCurrent(pr)
            if (gen != renderGen) return@launch // تنظیمات دوباره عوض شده؛ رندر تازه‌تر در راه است
            if (r != null) {
                recycleQuietly(result?.bitmap)
                result = r; linkUrl = null; linkError = null; recentView = null
            }
        }
    }

    /** تشخیص تم سایت — لغو شدن (عوض شدن URL) را قورت نمی‌دهد و اسپینر را گیر نمی‌اندازد. */
    suspend fun detectWebsite(url: String) {
        if (url.isBlank()) { detectNote = ""; siteColors = emptyList(); siteTheme = null; detecting = false; return }
        delay(600)
        detecting = true; detectNote = "در حال استخراج پالت و تم سایت..."
        try {
            val d = ColorDetector.detect(url)
            if (d.all.isNotEmpty()) siteColors = d.all
            if (d.found) {
                setBrand(d.color)
                siteTheme = ThemeEngine.build(d)
                detectNote = "تم سایت شناسایی شد (${d.color}) · ${siteTheme?.note} · ${d.source}"
            } else { siteTheme = null; detectNote = "رنگ برند سایت شناسایی نشد؛ لطفاً رنگ را دستی انتخاب کنید" }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            siteTheme = null; detectNote = "اتصال به سایت ممکن نشد؛ رنگ را دستی انتخاب کنید"
        } finally {
            detecting = false
        }
    }

    /** لمس روی پیش‌نمایش در حالت اصلاح: مختصات لمس را به مربع کاری ۸۰۰×۸۰۰ می‌برد. */
    fun refineAt(off: Offset, previewWpx: Float, previewWidthDp: Float) {
        val pr = prepared ?: return
        val res = result ?: return
        if (refining || recentView != null) return
        val (bw, bh) = SIZE_PRESETS[sizePreset]
        val previewHpx = previewWpx * bh / bw
        val bx = off.x / previewWpx * bw
        val by = off.y / previewHpx * bh
        val rect = res.subjectRect
        if (bx < rect.left || bx > rect.right || by < rect.top || by > rect.bottom) {
            toast("روی خود محصول بزنید")
            return
        }
        val localX = (bx - rect.left) / rect.width() * pr.subject.bmp.width
        val localY = (by - rect.top) / rect.height() * pr.subject.bmp.height
        val wx = pr.subject.ox + localX
        val wy = pr.subject.oy + localY
        val radius = 800f * 26f / previewWidthDp.coerceAtLeast(1f)
        viewModelScope.launch {
            refining = true
            try {
                val np = withContext(Dispatchers.Default) { ImageProcessor.refineTouch(pr, wx, wy, radius, refineFg) }
                recycleQuietly(pr.subject.bmp) // sq بین Prepared قدیم و جدید مشترک است؛ فقط سوژه قدیمی بازیافت می‌شود
                prepared = np
                val r = renderCurrent(np)
                if (r != null) {
                    recycleQuietly(result?.bitmap)
                    result = r
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Throwable) {
                toast("اصلاح لمسی ناموفق بود")
            } finally { refining = false }
        }
    }

    fun getLink() {
        val r = result ?: return
        if (linkBusy) return
        linkUrl?.let { Share.copy(ctx, it); toast("لینک مستقیم تصویر کپی شد!"); return }
        viewModelScope.launch {
            linkBusy = true; linkError = null
            try {
                val bytes = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }
                val up = withContext(Dispatchers.IO) { ImageHosts.upload(bytes, "anik_${System.currentTimeMillis()}.png") }
                if (result === r) {
                    linkUrl = up.url; linkHost = up.host
                    Share.copy(ctx, up.url); toast("لینک مستقیم تصویر کپی شد!")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                linkError = e.message ?: "خطای ناشناخته"
                toast("آپلود روی هاست تصویر ناموفق بود")
            } finally { linkBusy = false }
        }
    }

    fun batchLink(idx: Int) {
        val item = batchItems.getOrNull(idx) ?: return
        val r = item.result ?: return
        if (item.busy) return
        item.link?.let { Share.copy(ctx, it); toast("لینک کپی شد!"); return }
        viewModelScope.launch {
            batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = true, error = null) else it }
            try {
                val bytes = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }
                val up = withContext(Dispatchers.IO) { ImageHosts.upload(bytes, "anik_batch_${System.currentTimeMillis()}.png") }
                batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = false, link = up.url, host = up.host) else it }
                Share.copy(ctx, up.url); toast("لینک کپی شد!")
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = false, error = e.message ?: "خطای ناشناخته") else it }
            }
        }
    }

    fun batchSave(idx: Int) {
        val r = batchItems.getOrNull(idx)?.result ?: return
        runCatching { Share.saveToGallery(ctx, r.bitmap) }
            .onSuccess { toast("ذخیره شد: $it") }
            .onFailure { toast("ذخیره ناموفق بود") }
    }

    /** پردازش دسته‌ای: تا ۶ عکس، هرکدام با همان تنظیمات فعلی — با پیشرفت هر عکس و دکمه لغو. */
    fun startBatch(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || batchBusy) return
        cancelBatch = false
        batchJob?.cancel()
        batchJob = viewModelScope.launch {
            batchBusy = true
            batchItems = uris.mapIndexed { idx, _ -> BatchItem("عکس ${idx + 1}") }
            uris.forEachIndexed { idx, uri ->
                if (cancelBatch) return@forEachIndexed
                batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = true) else it }
                try {
                    val bmp = withContext(Dispatchers.IO) { runCatching { BitmapUtils.decode(ctx, uri) }.getOrNull() }
                    if (bmp == null) {
                        batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = false, error = "خواندن عکس ناموفق بود") else it }
                    } else {
                        val pr = withContext(Dispatchers.Default) { ImageProcessor.prepare(bmp) }
                        recycleQuietly(bmp)
                        if (cancelBatch) return@forEachIndexed
                        val r = renderCurrent(pr)
                        if (r != null) {
                            batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = false, result = r) else it }
                            pushRecent(r.bitmap)
                        } else {
                            batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = false, error = "پردازش ناموفق بود") else it }
                        }
                        recycleQuietly(pr.subject.bmp); recycleQuietly(pr.sq)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Throwable) {
                    batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = false, error = "خطا: ${e.message ?: "ناشناخته"}") else it }
                }
            }
            batchBusy = false
            toast(if (cancelBatch) "پردازش دسته‌ای لغو شد" else "پردازش دسته‌ای تمام شد")
        }
    }

    fun cancelBatch() {
        cancelBatch = true
        batchJob?.cancel()
    }

    // ───── مدل‌های دانلودی ─────

    fun downloadSilueta() {
        if (siluetaBusy) return
        cancelSilueta = false
        siluetaJob?.cancel()
        siluetaJob = viewModelScope.launch {
            siluetaBusy = true; siluetaProgress = 0f
            val ok = withContext(Dispatchers.IO) {
                SiluetaSegmenter.download(ctx, { siluetaProgress = it }, { cancelSilueta })
            }
            siluetaBusy = false; siluetaInstalled = ok || SiluetaSegmenter.isInstalled(ctx)
            toast(
                if (ok) "مدل Silueta فعال شد — حالا جداسازی دقیق‌تر است"
                else if (cancelSilueta) "دانلود لغو شد؛ می‌توانید بعداً ادامه دهید"
                else "دانلود ناموفق بود؛ اینترنت را بررسی کنید"
            )
        }
    }

    fun cancelSiluetaDownload() { cancelSilueta = true }

    fun removeSilueta() {
        cancelSilueta = true
        siluetaJob?.cancel()
        SiluetaSegmenter.remove(ctx)
        siluetaInstalled = false
        toast("مدل Silueta حذف شد")
    }

    fun downloadOrmbg() {
        if (ormbgBusy) return
        cancelOrmbg = false
        ormbgJob?.cancel()
        ormbgJob = viewModelScope.launch {
            ormbgBusy = true; ormbgProgress = 0f
            val ok = withContext(Dispatchers.IO) {
                OrmbgSegmenter.download(ctx, { ormbgProgress = it }, { cancelOrmbg })
            }
            ormbgBusy = false; ormbgInstalled = ok || OrmbgSegmenter.isInstalled(ctx)
            toast(
                if (ok) "مدل ORMBG فعال شد — بهترین کیفیت جداسازی"
                else if (cancelOrmbg) "دانلود لغو شد؛ می‌توانید بعداً ادامه دهید"
                else "دانلود ناموفق بود؛ اینترنت را بررسی کنید"
            )
        }
    }

    fun cancelOrmbgDownload() { cancelOrmbg = true }

    fun removeOrmbg() {
        cancelOrmbg = true
        ormbgJob?.cancel()
        OrmbgSegmenter.remove(ctx)
        ormbgInstalled = false
        toast("مدل ORMBG حذف شد")
    }

    override fun onCleared() {
        super.onCleared()
        recycleQuietly(srcBitmap)
        prepared?.let { recycleQuietly(it.subject.bmp); recycleQuietly(it.sq) }
        recycleQuietly(result?.bitmap)
        recents.forEach { recycleQuietly(it) }
        batchItems.forEach { recycleQuietly(it.result?.bitmap) }
        AiSegmenter.close()
    }
}
