package com.app.anik

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.CookieManager
import kotlin.math.roundToInt

// ───────── پالت رنگ (همان tailwind slate نسخه وب؛ تم تیره پیش‌فرض) ─────────
// پالت رنگ — فقط تم تیره (لایت مود در v2.1 حذف شد)
class Pal {
    val page = Color(0xFF0F172A)
    val headerBorder = Color(0xFF1E293B)
    val text = Color(0xFFF1F5F9)
    val dim = Color(0xFF94A3B8)
    val dimmer = Color(0xFF64748B)
    val label = Color(0xFFCBD5E1)
    val card = Color(0x801E293B)
    val cardBorder = Color(0xFF1E293B)
    val input = Color(0xCC1E293B)
    val inputBorder = Color(0xFF334155)
    val selRow = Color(0xFF1E293B)
    val segBg = Color(0x990F172A)
    val segOff = Color(0xFF94A3B8)
    val dropBg = Color(0x661E293B)
    val dropBorder = Color(0xFF334155)
    val resultBg = Color(0xFF111C30)
    val chipBg = Color(0xFF020617)
}
private val LocalPal = compositionLocalOf { Pal() }
private val Blue = Color(0xFF7C3AED)
private val Emerald = Color(0xFF34D399)
private val EXTENDED_PALETTE = listOf("#7C3AED", "#2563EB", "#DC2626", "#059669", "#D97706", "#DB2777", "#0891B2", "#1E293B")

private fun hexColor(h: String): Color = try { Color(android.graphics.Color.parseColor(h)) } catch (_: Exception) { Color.Gray }

// ───────── فونت: اگر فایل Vazirmatn در assets/fonts باشد استفاده می‌شود (بدون نیاز به اینترنت/گوگل) ─────────
fun loadFontFamily(ctx: Context): FontFamily {
    val files = runCatching { ctx.assets.list("fonts")?.filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) } }
        .getOrNull().orEmpty()
    if (files.isEmpty()) return FontFamily.Default
    fun weightOf(n: String) = when {
        n.contains("extrabold", true) -> FontWeight.ExtraBold
        n.contains("semibold", true) -> FontWeight.SemiBold
        n.contains("bold", true) -> FontWeight.Bold
        n.contains("medium", true) -> FontWeight.Medium
        n.contains("light", true) -> FontWeight.Light
        else -> FontWeight.Normal
    }
    val fonts = if (files.size == 1)
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
            .map { Font("fonts/${files[0]}", ctx.assets, it) }
    else files.map { Font("fonts/$it", ctx.assets, weightOf(it)) }
    return FontFamily(fonts)
}

private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f)
)

class Sample(val label: String, val file: String, val website: String)

// ───────── پیش‌تنظیم‌های اندازه خروجی (عرض × ارتفاع) ─────────
private val SIZE_PRESETS = listOf(1000 to 1000, 1080 to 1350, 1080 to 1920)
private val SIZE_LABELS = listOf("مربع ۱۰۰۰", "عمودی ۱۰۸۰×۱۳۵۰", "استوری ۱۰۸۰×۱۹۲۰")

data class BatchItem(
    val name: String,
    val result: ProcessedResult? = null,
    val link: String? = null,
    val host: String = "",
    val busy: Boolean = false,
    val error: String? = null
)
private val SAMPLE_DEFS = listOf(
    Triple("sneakers", "کفش ورزشی", "https://rezonixshop.mynameusrnr.workers.dev/#shop"),
    Triple("headphones", "هدفون بی‌سیم", "https://technolife.ir/headphone"),
    Triple("perfume", "شیشه ادکلن لوکس", "https://shafapharmacy.com/perfume"),
    Triple("smartwatch", "ساعت هوشمند", "https://mobit.ir/smartwatch")
)
private fun loadSamples(ctx: Context): List<Sample> {
    val files = runCatching { ctx.assets.list("samples")?.toList() }.getOrNull().orEmpty()
    return SAMPLE_DEFS.mapNotNull { (prefix, label, site) ->
        files.firstOrNull { it.startsWith(prefix, true) }?.let { Sample(label, "samples/$it", site) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
        // موتور AI آفلاین: فقط Context برنامه را نگه می‌دارد؛
        // لود مدل در اولین پردازش عکس و روی رشته پس‌زمینه انجام می‌شود.
        AiSegmenter.init(applicationContext)
        val family = loadFontFamily(this)
        setContent {
            val pal = Pal()
            val scheme = darkColorScheme(primary = Blue, background = pal.page, surface = pal.page)
            val view = LocalView.current
            SideEffect {
                val w = (view.context as Activity).window
                w.statusBarColor = pal.page.toArgb(); w.navigationBarColor = pal.page.toArgb()
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalPal provides pal) {
                MaterialTheme(colorScheme = scheme, typography = Typography().withFont(family)) {
                    Surface(Modifier.fillMaxSize(), color = pal.page) {
                        AnikScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun AnikScreen() {
    val P = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val samples = remember { loadSamples(ctx) }

    var srcBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var prepared by remember { mutableStateOf<Prepared?>(null) }
    var imageName by remember { mutableStateOf("") }
    var websiteUrl by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(BgMode.SIMPLE) }
    var shadow by remember { mutableStateOf(ShadowStyle.CONTACT) }
    var brandColor by remember { mutableStateOf("#7C3AED") }
    var hexText by remember { mutableStateOf("#7C3AED") }
    var siteColors by remember { mutableStateOf(listOf<String>()) }
    var siteTheme by remember { mutableStateOf<ThemeEngine.SiteTheme?>(null) }
    var detecting by remember { mutableStateOf(false) }
    var detectNote by remember { mutableStateOf("") }
    var processing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProcessedResult?>(null) }
    var linkUrl by remember { mutableStateOf<String?>(null) }
    var linkHost by remember { mutableStateOf("") }
    var linkBusy by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    // v3: اندازه خروجی، شفافیت، مقایسه، اصلاح لمسی، بچ و خروجی‌های اخیر
    var transparent by remember { mutableStateOf(false) }
    var sizePreset by remember { mutableStateOf(0) }
    var compare by remember { mutableStateOf(false) }
    var comparePos by remember { mutableStateOf(0.5f) }
    var refineMode by remember { mutableStateOf(false) }
    var refineFg by remember { mutableStateOf(true) }
    var refining by remember { mutableStateOf(false) }
    var recentView by remember { mutableStateOf<Bitmap?>(null) }
    var recents by remember { mutableStateOf(listOf<Bitmap>()) }
    var batchItems by remember { mutableStateOf(listOf<BatchItem>()) }
    var batchBusy by remember { mutableStateOf(false) }

    fun pushRecent(bmp: Bitmap) {
        recents = (listOf(bmp) + recents.filter { it !== bmp }).take(4)
    }

    suspend fun renderCurrent(pr: Prepared): ProcessedResult? = withContext(Dispatchers.Default) {
        val (w, h) = SIZE_PRESETS[sizePreset]
        runCatching {
            ImageProcessor.render(pr, mode, shadow, if (mode == BgMode.BRAND && !transparent) brandColor else null, w, h, transparent,
                brandGradient = if (mode == BgMode.BRAND && !transparent) siteTheme?.gradient else null)
        }.getOrNull()
    }

    // نسخه مربعی عکس ورودی در اندازه خروجی — برای اسلایدر مقایسه قبل/بعد
    // همان مربع کاری prepare() (جاگذاری کامل عکس، نه برش وسط) تا مقایسه با خروجی هم‌تراز باشد
    val srcPreview = remember(srcBitmap, sizePreset) {
        val s = srcBitmap ?: return@remember null
        val (w, h) = SIZE_PRESETS[sizePreset]
        val sq = ImageProcessor.fitSquare(s, ImageProcessor.WORK)
        Bitmap.createScaledBitmap(sq, w, h, true)
    }

    fun toast(m: String) { toastMsg = m }
    fun setBrand(c: String) {
        val v = c.trim().uppercase().let { if (it.startsWith("#")) it else "#$it" }
        brandColor = v; hexText = v
    }
    fun resetResult() { result = null; prepared = null; linkUrl = null; linkError = null; recentView = null; compare = false; refineMode = false }
    fun applyImage(bmp: Bitmap?, name: String) {
        if (bmp == null) { toast("خواندن عکس ناموفق بود"); return }
        srcBitmap = bmp; imageName = name; resetResult()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { BitmapUtils.decode(ctx, uri) }.getOrNull() }
            applyImage(bmp, uri.lastPathSegment?.substringAfterLast('/') ?: "تصویر محصول")
        }
    }

    // پردازش دسته‌ای: تا ۶ عکس، هرکدام با همان تنظیمات فعلی
    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isEmpty() || batchBusy) return@rememberLauncherForActivityResult
        scope.launch {
            batchBusy = true
            batchItems = uris.mapIndexed { idx, _ -> BatchItem("عکس ${idx + 1}") }
            uris.forEachIndexed { idx, uri ->
                try {
                    val bmp = withContext(Dispatchers.IO) { runCatching { BitmapUtils.decode(ctx, uri) }.getOrNull() }
                    if (bmp == null) {
                        batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(error = "خواندن عکس ناموفق بود") else it }
                    } else {
                        val pr = withContext(Dispatchers.Default) { ImageProcessor.prepare(bmp) }
                        val r = renderCurrent(pr)
                        if (r != null) {
                            batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(result = r) else it }
                            pushRecent(r.bitmap)
                        } else {
                            batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(error = "پردازش ناموفق بود") else it }
                        }
                    }
                } catch (e: Throwable) {
                    batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(error = "خطا: ${e.message ?: "ناشناخته"}") else it }
                }
            }
            batchBusy = false
            toast("پردازش دسته‌ای تمام شد")
        }
    }

    LaunchedEffect(toastMsg) { if (toastMsg != null) { delay(3500); toastMsg = null } }

    LaunchedEffect(websiteUrl) {
        if (websiteUrl.isBlank()) { detectNote = ""; siteColors = emptyList(); siteTheme = null; return@LaunchedEffect }
        delay(600)
        detecting = true; detectNote = "در حال استخراج پالت و تم سایت..."
        val d = runCatching { ColorDetector.detect(websiteUrl) }.getOrNull()
        if (d != null) {
            if (d.all.isNotEmpty()) siteColors = d.all
            if (d.found) {
                setBrand(d.color)
                siteTheme = ThemeEngine.build(d)
                detectNote = "تم سایت شناسایی شد (${d.color}) · ${siteTheme?.note} · ${d.source}"
            } else { siteTheme = null; detectNote = "رنگ برند سایت شناسایی نشد؛ لطفاً رنگ را دستی انتخاب کنید" }
        } else { siteTheme = null; detectNote = "اتصال به سایت ممکن نشد؛ رنگ را دستی انتخاب کنید" }
        detecting = false
    }

    // رندر مجدد زنده با تغییر سایه/حالت/رنگ/اندازه/شفافیت/تم (جداسازی دوباره انجام نمی‌شود)
    LaunchedEffect(shadow, mode, brandColor, transparent, sizePreset, siteTheme) {
        val pr = prepared
        if (result == null || pr == null || processing) return@LaunchedEffect
        val r = renderCurrent(pr)
        if (r != null) { result = r; linkUrl = null; linkError = null; recentView = null }
    }

    fun build() {
        val s = srcBitmap ?: return
        if (processing) return
        scope.launch {
            processing = true
            try {
                val pr = withContext(Dispatchers.Default) { prepared ?: ImageProcessor.prepare(s) }
                prepared = pr
                val r = renderCurrent(pr)
                if (r != null) {
                    result = r; linkUrl = null; linkError = null; recentView = null
                    pushRecent(r.bitmap)
                }
            } catch (e: Throwable) {
                toast("خطایی در پردازش عکس رخ داد. لطفاً دوباره امتحان کنید.")
            } finally { processing = false }
        }
    }

    // لمس روی پیش‌نمایش در حالت اصلاح: مختصات لمس را به مربع کاری ۸۰۰×۸۰۰ می‌برد
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
        scope.launch {
            refining = true
            try {
                val np = withContext(Dispatchers.Default) { ImageProcessor.refineTouch(pr, wx, wy, radius, refineFg) }
                prepared = np
                val r = renderCurrent(np)
                if (r != null) result = r
            } catch (e: Throwable) {
                toast("اصلاح لمسی ناموفق بود")
            } finally { refining = false }
        }
    }

    fun getLink() {
        val r = result ?: return
        if (linkBusy) return
        linkUrl?.let { Share.copy(ctx, it); toast("لینک مستقیم تصویر کپی شد!"); return }
        scope.launch {
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
            } catch (e: Throwable) {
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
        scope.launch {
            batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = true, error = null) else it }
            try {
                val bytes = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }
                val up = withContext(Dispatchers.IO) { ImageHosts.upload(bytes, "anik_batch_${System.currentTimeMillis()}.png") }
                batchItems = batchItems.mapIndexed { i, it -> if (i == idx) it.copy(busy = false, link = up.url, host = up.host) else it }
                Share.copy(ctx, up.url); toast("لینک کپی شد!")
            } catch (e: Throwable) {
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ───── هدر ─────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LogoBox()
                    Column {
                        Text("آنیک", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = P.text)
                        Text("استودیو ساخت عکس محصول", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = P.dim)
                    }
                }
            }
            HorizontalDivider(color = P.headerBorder)

            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {

                // ───── ۱. انتخاب عکس ─────
                Column {
                    val sb = srcBitmap
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (sb != null) (Color(0x33022C22)) else P.dropBg)
                            .border(2.dp, if (sb != null) Color(0x9910B981) else P.dropBorder, RoundedCornerShape(16.dp))
                            .clickable { picker.launch("image/*") }.padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (sb != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Image(sb.asImageBitmap(), null,
                                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, P.dropBorder, RoundedCornerShape(12.dp)).background(Color.White),
                                    contentScale = ContentScale.Crop)
                                Column(Modifier.weight(1f)) {
                                    Text("✅ عکس انتخاب شد", color = Emerald, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(imageName.ifBlank { "تصویر محصول" }, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text("برای تغییر عکس کلیک کنید", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x99172554)), contentAlignment = Alignment.Center) { Text("⬆", color = Color(0xFF60A5FA), fontSize = 18.sp) }
                                Text("انتخاب عکس محصول", color = P.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("از گالری گوشی انتخاب کنید", color = P.dimmer, fontSize = 12.sp)
                            }
                        }
                    }
                    if (samples.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("نمونه‌های تست سریع (بدون پس‌زمینه اضافه):", color = P.dim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            samples.forEach { s ->
                                val thumb by produceState<Bitmap?>(null, s.file) { value = withContext(Dispatchers.IO) { BitmapUtils.decodeAsset(ctx, s.file, 200) } }
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, P.cardBorder, RoundedCornerShape(12.dp))
                                        .clickable {
                                            scope.launch {
                                                val bmp = withContext(Dispatchers.IO) { BitmapUtils.decodeAsset(ctx, s.file) }
                                                applyImage(bmp, s.label)
                                                if (websiteUrl.isBlank()) websiteUrl = s.website
                                            }
                                        }.padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
                                        thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit) }
                                    }
                                    Text(s.label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = P.label, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                // ───── ۲. لینک سایت ─────
                Column {
                    Text("لینک سایت (اختیاری - برای تشخیص خودکار رنگ و تم برند)", color = P.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = websiteUrl, onValueChange = { websiteUrl = it },
                            placeholder = { Text("https://rezonixshop.mynameusrnr.workers.dev/#shop", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = P.dimmer, maxLines = 1) },
                            textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = P.text, textAlign = TextAlign.Left),
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = P.input, unfocusedContainerColor = P.input,
                                focusedBorderColor = Blue, unfocusedBorderColor = P.inputBorder
                            )
                        )
                    }
                    if (websiteUrl.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (detecting) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFF60A5FA))
                            Text(detectNote.ifBlank { "رنگ برند:" }, color = if (detecting) Color(0xFF60A5FA) else P.label, fontSize = 11.sp, modifier = Modifier.weight(1f, false))
                            if (!detecting) Box(Modifier.size(14.dp).clip(CircleShape).background(hexColor(brandColor)).border(1.dp, Color(0x33FFFFFF), CircleShape))
                        }
                        // تم سایت: سه نقش اصلی/فرعی/تاکید — همان ردیف چیپ‌ها، بدون بخش جدید
                        siteTheme?.let { th ->
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("اصلی" to th.primary, "فرعی" to th.secondary, "تاکید" to th.accent).forEach { (label, sc) ->
                                    Row(
                                        Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F172A))
                                            .border(1.dp, P.inputBorder, RoundedCornerShape(6.dp)).clickable { setBrand(sc) }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(sc)))
                                        Text("$label $sc", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = P.label)
                                    }
                                }
                            }
                        }
                    }
                }

                // ───── ۳. نوع پس‌زمینه ─────
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.card)
                        .border(1.dp, P.cardBorder, RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("نوع خروجی پس‌زمینه:", color = P.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
                    ModeRow(mode == BgMode.SIMPLE, "حالت ساده (سفید)", "پس‌زمینه سفید خالص استاندارد با سایه استودیویی", Color.White) { mode = BgMode.SIMPLE }
                    ModeRow(mode == BgMode.BRAND, "حالت برند (هماهنگ با تم سایت)", "پس‌زمینه نوری استودیویی با کد رنگ دلخواه یا تم سایت", hexColor(brandColor)) { mode = BgMode.BRAND }

                    if (mode == BgMode.BRAND) {
                        HorizontalDivider(color = Color(0x99334155), modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("کد رنگ دلخواه (HEX):", color = P.label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = hexText,
                                    onValueChange = {
                                        if (it.length <= 7) {
                                            hexText = it.uppercase()
                                            if (Regex("^#?[0-9A-Fa-f]{6}$").matches(it.trim()))
                                                brandColor = it.trim().uppercase().let { v -> if (v.startsWith("#")) v else "#$v" }
                                        }
                                    },
                                    singleLine = true, modifier = Modifier.width(118.dp),
                                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = P.text, textAlign = TextAlign.Center),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color(0xFF0F172A),
                                        unfocusedContainerColor = Color(0xFF0F172A),
                                        focusedBorderColor = Blue, unfocusedBorderColor = P.inputBorder
                                    )
                                )
                            }
                        }
                        Box(Modifier.padding(horizontal = 6.dp)) { SwatchRow(EXTENDED_PALETTE, brandColor, 24) { setBrand(it) } }
                        if (siteColors.isNotEmpty()) {
                            Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("رنگ‌های استخراج‌شده از سایت شما:", color = P.dim, fontSize = 10.sp)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    siteColors.forEach { sc ->
                                        Row(
                                            Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F172A))
                                                .border(1.dp, P.inputBorder, RoundedCornerShape(6.dp)).clickable { setBrand(sc) }
                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(sc)))
                                            Text(sc, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = P.label)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ───── ۴. استایل سایه ─────
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x661E293B))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp)).padding(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("استایل سایه استودیویی:", color = P.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (shadow) {
                                ShadowStyle.CONTACT -> "چسبیده به سطح زمین"
                                ShadowStyle.FLOATING -> "شناور با فاصله از سطح"
                                ShadowStyle.REFLECTION -> "انعکاس شیشه‌ای زیر کالا"
                            }, color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.segBg)
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp)).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SegButton("تماسی طبیعی", shadow == ShadowStyle.CONTACT, Modifier.weight(1f)) { shadow = ShadowStyle.CONTACT }
                        SegButton("معلق سه‌بعدی", shadow == ShadowStyle.FLOATING, Modifier.weight(1f)) { shadow = ShadowStyle.FLOATING }
                        SegButton("انعکاس آینه‌ای", shadow == ShadowStyle.REFLECTION, Modifier.weight(1f)) { shadow = ShadowStyle.REFLECTION }
                    }
                }

                // ───── ۵. اندازه خروجی و شفافیت ─────
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.card)
                        .border(1.dp, P.cardBorder, RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("اندازه خروجی:", color = P.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SIZE_LABELS.forEachIndexed { idx, label ->
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if (sizePreset == idx) Blue else P.segBg)
                                    .border(1.dp, if (sizePreset == idx) Blue else P.inputBorder, RoundedCornerShape(10.dp))
                                    .clickable { sizePreset = idx }.padding(vertical = 8.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(label, color = if (sizePreset == idx) Color.White else P.segOff, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                        }
                    }
                    HorizontalDivider(color = Color(0x99334155))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("پس‌زمینه شفاف (PNG)", color = P.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("بدون پس‌زمینه؛ برای چیدن روی هر طرحی", color = P.dim, fontSize = 10.sp)
                        }
                        Switch(
                            checked = transparent, onCheckedChange = { transparent = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Blue, uncheckedThumbColor = P.dim, uncheckedTrackColor = P.inputBorder)
                        )
                    }
                }

                // ───── ۶. دکمه ساخت ─────
                val canBuild = srcBitmap != null && !processing
                Button(
                    onClick = { build() }, enabled = canBuild,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Blue, contentColor = Color.White,
                        disabledContainerColor = Color(0xFF1E293B),
                        disabledContentColor = Color(0xFF64748B))
                ) {
                    if (processing) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF94A3B8), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("در حال پردازش و ساخت سایه استودیویی...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else Text("✨ ساخت عکس محصول", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                // ───── ۷. نتیجه ─────
                result?.let { res ->
                    val (pw, ph) = SIZE_PRESETS[sizePreset]
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(P.resultBg)
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(24.dp)).padding(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("🖼 خروجی آماده ($pw × $ph px)", color = P.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (compare) Blue else Color(0xFF1E293B))
                                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                                        .clickable { compare = !compare; if (compare) refineMode = false }.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) { Text("👁 مقایسه", fontSize = 11.sp, color = Color.White) }
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (refineMode) Blue else Color(0xFF1E293B))
                                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                                        .clickable { refineMode = !refineMode; if (refineMode) compare = false }.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) { Text("✏️ اصلاح", fontSize = 11.sp, color = Color.White) }
                            }
                        }
                        if (refineMode) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("روی قسمتی که اشتباه جدا شده بزن:", color = P.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (refineFg) Emerald else Color(0xFF1E293B))
                                        .clickable { refineFg = true }.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) { Text("محصول", fontSize = 11.sp, color = if (refineFg) Color(0xFF022C22) else P.label, fontWeight = FontWeight.Bold) }
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (!refineFg) Color(0xFFDC2626) else Color(0xFF1E293B))
                                        .clickable { refineFg = false }.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) { Text("پس‌زمینه", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                            }
                        }
                        if (recentView != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("در حال نمایش یکی از خروجی‌های اخیر", color = Color(0xFFFBBF24), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text("بازگشت به خروجی فعلی", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { recentView = null })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        BoxWithConstraints(
                            Modifier.fillMaxWidth().aspectRatio(pw.toFloat() / ph)
                                .clip(RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                                .background(Color(0xFF0F172A))
                        ) {
                            val previewWpx = with(LocalDensity.current) { maxWidth.toPx() }
                            val previewWidthDp = maxWidth.value
                            val handleHalfPx = with(LocalDensity.current) { 14.dp.toPx() }.roundToInt()
                            val shown = recentView ?: res.bitmap
                            Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            if (recentView == null && compare && srcPreview != null) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(comparePos).clip(RectangleShape)) {
                                    Image(srcPreview.asImageBitmap(), null, Modifier.fillMaxHeight().width(maxWidth), contentScale = ContentScale.FillBounds)
                                }
                                Box(Modifier.fillMaxHeight().width(2.dp).offset { IntOffset((comparePos * previewWpx).roundToInt(), 0) }.background(Color.White))
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                    Box(
                                        Modifier.offset { IntOffset((comparePos * previewWpx).roundToInt() - handleHalfPx, 0) }
                                            .size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.92f)),
                                        contentAlignment = Alignment.Center
                                    ) { Text("↔", color = Color(0xFF0F172A), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                                }
                                Box(Modifier.fillMaxSize().pointerInput(compare) {
                                    detectHorizontalDragGestures { _, dragAmount ->
                                        comparePos = (comparePos + dragAmount / previewWpx).coerceIn(0.05f, 0.95f)
                                    }
                                })
                            }
                            if (recentView == null && refineMode) {
                                Box(Modifier.fillMaxSize().pointerInput(prepared, result, refineFg) {
                                    detectTapGestures { off -> refineAt(off, previewWpx, previewWidthDp) }
                                })
                                if (refining) {
                                    Box(Modifier.fillMaxSize().background(Color(0x660F172A)), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                                    }
                                }
                            }
                            Text(
                                "$pw × $ph · PNG · ${res.engine}" + if (transparent) " · شفاف" else "",
                                color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xBF000000)).padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                            if (res.mode == BgMode.BRAND && res.brandColor != null && recentView == null && !compare) {
                                Row(
                                    Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xE60F172A)).padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(hexColor(res.brandColor)))
                                    Text(res.brandColor, color = Color(0xFFE2E8F0), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        res.note?.let {
                            Spacer(Modifier.height(8.dp))
                            Text("⚠️ $it", color = Color(0xFFFBBF24), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    runCatching { Share.saveToGallery(ctx, res.bitmap) }
                                        .onSuccess { toast("عکس با موفقیت ذخیره شد: $it") }
                                        .onFailure { toast("ذخیره عکس ناموفق بود") }
                                },
                                modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)
                            ) { Text("💾 دانلود", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                            OutlinedButton(
                                onClick = { getLink() }, enabled = !linkBusy,
                                modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E293B), contentColor = Emerald)
                            ) {
                                if (linkBusy) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Emerald)
                                    Spacer(Modifier.width(6.dp)); Text("در حال آپلود...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                } else Text("🔗 دریافت لینک", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        linkUrl?.let { url ->
                            Spacer(Modifier.height(12.dp))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xE60F172A)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("لینک مستقیم تصویر ($linkHost) — برای تگ <img> سایت:", color = P.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(url, color = Emerald, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(P.chipBg).padding(horizontal = 10.dp, vertical = 8.dp))
                                    }
                                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E293B)).clickable { Share.copy(ctx, url); toast("لینک کپی شد!") }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Text("کپی آدرس", fontSize = 10.sp, color = P.label, fontWeight = FontWeight.Medium)
                                    }
                                }
                                Text(
                                    "</> کپی تگ آماده <img loading=\"lazy\"> برای سرعت سایت", color = Color(0xFF60A5FA), fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable {
                                        Share.copy(ctx, "<img src=\"$url\" alt=\"${imageName.ifBlank { "Product" }}\" width=\"1000\" height=\"1000\" loading=\"lazy\" decoding=\"async\" />")
                                        toast("کد تگ <img> بهینه‌شده برای سرعت سایت کپی شد!")
                                    }
                                )
                            }
                        }
                        linkError?.let { err ->
                            Spacer(Modifier.height(12.dp))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x33DC2626)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("آپلود انجام نشد. جزئیات خطا:", color = Color(0xFFFCA5A5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                SelectionContainer { Text(err, color = Color(0xFFFECACA), fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                                Text("عکس همچنان با «دانلود» ذخیره می‌شود.", color = P.dim, fontSize = 10.sp)
                            }
                        }
                    }
                }

                // ───── ۸. پردازش دسته‌ای ─────
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.card)
                        .border(1.dp, P.cardBorder, RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("پردازش دسته‌ای", color = P.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("تا ۶ عکس با همان تنظیمات فعلی", color = P.dim, fontSize = 10.sp)
                        }
                        Button(
                            onClick = { batchPicker.launch("image/*") }, enabled = !batchBusy,
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.height(38.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)
                        ) { Text(if (batchBusy) "در حال پردازش..." else "انتخاب چند عکس", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                    batchItems.forEachIndexed { idx, item ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F172A)).border(1.dp, P.inputBorder, RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                                    item.result?.let { Image(it.bitmap.asImageBitmap(), null, Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit) }
                                        ?: Text(if (item.error != null) "⚠️" else "⏳", fontSize = 16.sp)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, color = P.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        item.error ?: if (item.result != null) "آماده · ${item.result.engine}" else "در حال پردازش...",
                                        color = if (item.error != null) Color(0xFFFCA5A5) else P.dim, fontSize = 11.sp, maxLines = 2
                                    )
                                }
                                if (item.result != null) {
                                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E293B)).clickable { batchSave(idx) }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Text("💾", fontSize = 14.sp)
                                    }
                                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E293B)).clickable { batchLink(idx) }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        if (item.busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Emerald)
                                        else Text("🔗", fontSize = 14.sp)
                                    }
                                }
                            }
                            item.link?.let { url ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(url, color = Emerald, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(P.chipBg).padding(horizontal = 10.dp, vertical = 6.dp))
                                    }
                                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E293B)).clickable { Share.copy(ctx, url); toast("لینک کپی شد!") }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text("کپی", fontSize = 10.sp, color = P.label, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }

                // ───── خروجی‌های اخیر ─────
                if (recents.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("خروجی‌های اخیر:", color = P.dim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            recents.forEach { bmp ->
                                Image(
                                    bmp.asImageBitmap(), null,
                                    Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, P.cardBorder, RoundedCornerShape(10.dp))
                                        .background(Color.White)
                                        .clickable { recentView = bmp },
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        // ───── Toast ─────
        toastMsg?.let { m ->
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xF20F172A)).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0x3310B981)), contentAlignment = Alignment.Center) { Text("✓", color = Emerald, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Text(m, color = Color(0xFFF1F5F9), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, false))
            }
        }
    }
}

@Composable
private fun LogoBox() {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFDC2626))))
            .border(1.dp, Color(0x4D7C3AED), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, sub: String, dot: Color, onClick: () -> Unit) {
    val P = LocalPal.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) P.selRow else Color.Transparent)
            .border(1.dp, if (selected) Blue else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) P.text else P.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = P.dim, fontSize = 11.sp)
        }
        Box(Modifier.size(20.dp).clip(CircleShape).background(dot).border(1.dp, Color(0x4D94A3B8), CircleShape))
    }
}

@Composable
private fun SwatchRow(colors: List<String>, selected: String, size: Int, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            val isSel = selected.equals(c, true)
            Box(
                Modifier.size(size.dp).clip(CircleShape).background(hexColor(c))
                    .border(if (isSel) 2.dp else 1.dp, if (isSel) Color.White else Color(0x26FFFFFF), CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun SegButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val P = LocalPal.current
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Blue else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (selected) Color.White else P.segOff, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}
