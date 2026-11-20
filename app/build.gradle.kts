plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.app.anik"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.app.anik"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "3.4.0"
        // فقط arm64-v8a: همه گوشی‌های جدید این معماری را دارند؛ حذف armeabi-v7a حجم APK را کم می‌کند.
        // تنها کتابخانه native پروژه LiteRT است که فایل .so آن با تراز 16KB ساخته شده
        // (با readelf تایید شده)؛ پس خطای 16KB گوگل‌پلی برنمی‌گردد.
        ndk { abiFilters += listOf("arm64-v8a") }
        // فقط منابع انگلیسی (fallback) و فارسی بسته‌بندی می‌شوند
        resourceConfigurations += listOf("en", "fa")
    }
    // مدل U2NetP فشرده نمی‌شود تا در صورت نیاز مستقیم از assets قابل خواندن باشد
    // (کد AiSegmenter به‌هرحال آن را به حافظه داخلی کپی می‌کند؛ این فقط کمربند و شلوار است).
    androidResources { noCompress += "tflite" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            // کوچک‌سازی APK: R8 + حذف منابع استفاده‌نشده (فقط در Release اعمال می‌شود)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // v3.4: ViewModel — وضعیت (عکس/نتیجه/بچ) با چرخش گوشی پاک نمی‌شود
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    // موتور هوش مصنوعی آفلاین (v3.1): LiteRT 1.4.0 — جانشین رسمی TFLite.
    // فقط از مخزن گوگل (google()) قابل دریافت است؛ اگر میرور Aliyun آن را نداشت،
    // گریدل سراغ google() می‌رود (در settings.gradle.kts هست).
    // فایل .so آن (arm64) با تراز 16KB ساخته شده (با readelf تایید شده)؛
    // نسخه قدیمی TFLite 2.16.1 تراز 4KB داشت و خطای 16KB می‌داد.
    // litert-api هم لازم است (بدون آن خطای InterpreterFactoryImpl در اجرا).
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-api:1.4.0")
    // v3.4: ONNX Runtime برای Silueta (نسخه 1.22+ با تراز 16KB؛ بعد از بیلد با readelf چک کنید)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // OpenCV حذف شد: کتابخانه‌های native آن (libopencv_java4.so و libc++_shared.so)
    // با صفحه‌های 16KB سازگار نبودند (خطای Google Play برای Android 15+)
    // و به‌تنهایی حدود ۵۰ مگابایت به APK اضافه می‌کردند.
    // جداسازی محصول حالا اول با موتور AI (AiSegmenter) انجام می‌شود و
    // موتور کلاسیک (ImageProcessor) فقط جایگزینِ هنگام خطاست.
}
