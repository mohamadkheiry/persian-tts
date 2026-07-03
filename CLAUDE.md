# CLAUDE.md — راهنمای توسعه‌ی «تبدیل متن فارسی به گفتار» (Persian Piper TTS)

> این فایل برای هر نشست هوش مصنوعی/توسعه‌دهنده‌ای نوشته شده که قرار است روی این پروژه کار کند.
> پروژه‌ای کاملاً مستقل است؛ هیچ ربطی به پروژه‌ی «حافظان صادق» ندارد.

## ۱. این پروژه چیست

اپلیکیشن اندروید بومی (Kotlin، بدون Compose) که متن فارسی وارد‌شده توسط کاربر را به‌صورت
**کاملاً آفلاین** به گفتار فارسی تبدیل می‌کند. موتور TTS، [Piper](https://github.com/rhasspy/piper)
است (مدل‌های VITS مبتنی بر ONNX) که از طریق کتابخانه‌ی [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
روی دستگاه اجرا می‌شود — نه فراخوانی هیچ API ابری‌ای.

## ۲. قواعد طلایی

1. **کاملاً آفلاین** — بعد از نصب هیچ تماس شبکه‌ای برای تولید صدا لازم نیست. صدای «گنجی» و «گایرو»
   و داده‌ی `espeak-ng-data` همگی داخل `assets` بسته‌بندی شده‌اند.
2. **صدای پیش‌فرض همیشه «گنجی» (ganji)** — ایندکس ۰ در لیست `voices` در `MainActivity.kt`.
3. **بدون Jetpack Compose** — UI با XML layout سنتی (`activity_main.xml`) + `findViewById` نوشته شده؛
   به همین سبک ادامه بده مگر دلیل قوی برای migrate باشد.
4. **همه‌ی رشته‌های UI فارسی‌اند** (`res/values/strings.xml`)؛ پیام خطا باید فارسیِ روشن باشد.
5. **minSdk 24** — از API جدیدتر بدون گارد نسخه استفاده نکن (نمونه: مسیر ذخیره‌سازی فایل در
   `onSaveClicked()` بین API < 29 و >= 29 شاخه‌بندی شده — همین الگو را برای APIهای دیگر هم رعایت کن).
6. **بدون هیچ وابستگی/سرویس ابری در زمان اجرا.** تنها استثنا: دانلود مدل صدای جدید توسط توسعه‌دهنده
   در زمان build (نه در زمان اجرای اپ روی گوشی کاربر).

## ۳. معماری

- **تک‌اکتیویتی** (`MainActivity.kt`, ~۴۵۰ خط) — همه‌ی منطق UI + بارگذاری موتور + سنتز + پخش + ذخیره +
  اشتراک‌گذاری در همین فایل است. عمداً ساده نگه داشته شده (بدون ViewModel/MVVM) چون اپ تک‌صفحه‌ای است.
- **`OfflineTts` (از sherpa-onnx)** — با `OfflineTtsConfig(model = OfflineTtsModelConfig(vits = ...))`
  ساخته می‌شود. مسیر مدل و tokens نسبت به `assets/` داده می‌شود (خود کتابخانه از `AssetManager` می‌خواند)
  اما `dataDir` (مسیر `espeak-ng-data`) باید مسیر واقعی روی دیسک باشد چون کد C زیرین با `fopen` باز
  می‌کند — به همین دلیل `ensureEspeakDataDir()` این پوشه را یک‌بار به `filesDir` کپی می‌کند (نه هر بار).
- **تعویض گوینده** = ساخت مجدد کامل `OfflineTts` (`loadTtsEngine()`) — سبک‌وزن کافی است چون مدل‌ها کوچک‌اند.
- **سنتز روی Thread جدا** (نه Kotlin coroutines) — چون `OfflineTts.generate()` بلاک‌کننده است و پروژه
  کتابخانه‌ی coroutines را dependency نکرده؛ نتیجه با `Handler(Looper.getMainLooper())` به UI برمی‌گردد.
- **خروجی WAV** در `cacheDir/tts_output.wav` نوشته می‌شود (فایل موقت)؛ فقط هنگام «ذخیره» به
  MediaStore (پوشه‌ی Music، از طریق `RELATIVE_PATH` + `IS_PENDING`) کپی می‌شود.
- **اشتراک‌گذاری** با `FileProvider` (authority: `com.example.persiantts.fileprovider`,
  پیکربندی در `res/xml/file_paths.xml`) + `Intent.ACTION_SEND`.

## ۴. مدل‌های صوتی و تبدیل آن‌ها

مدل‌های خام Piper (`.onnx` + `.onnx.json`) از `rhasspy/piper-voices` روی HuggingFace دانلود می‌شوند،
اما sherpa-onnx فرمت متادیتای متفاوتی نسبت به Piper خام انتظار دارد. اسکریپت
[`convert_piper.py`](convert_piper.py) (پایتون، وابسته به پکیج `onnx`) دو کار می‌کند:

1. از `phoneme_id_map` داخل `.onnx.json`، فایل `tokens.txt` می‌سازد.
2. متادیتای لازم (`model_type`, `language`, `voice`, `has_espeak`, `n_speakers`, `sample_rate`, ...)
   را مستقیماً داخل `metadata_props` فایل ONNX تزریق می‌کند (چون loader بومی sherpa-onnx این‌ها را از
   خود فایل مدل می‌خواند، نه از یک JSON کنار آن).

هنگام افزودن صدای جدید حتماً از همین اسکریپت عبور بده؛ فایل onnx خامِ Piper بدون این تبدیل توسط
sherpa-onnx لود نمی‌شود (پیدا نکردن `model_type` باعث fail شدن `OfflineTts(...)` می‌شود).

## ۵. کتابخانه‌ی sherpa-onnx

`app/libs/sherpa-onnx.aar` یک AAR پیش‌ساخته است (شامل جاوا/کاتلین‌بایندینگ + `.so`های JNI برای
`arm64-v8a`/`armeabi-v7a`: `libonnxruntime.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`,
`libsherpa-onnx-jni.so`). به‌عنوان `flatDir` dependency در `app/build.gradle` اضافه شده
(`implementation(name: 'sherpa-onnx', ext: 'aar')` + `flatDir { dirs("app/libs") }` در `settings.gradle`).
این فایل‌ها را strip نمی‌شود (پیام هشدار بی‌خطر در build log: "Unable to strip the following libraries").

اگر نسخه‌ی جدیدتر sherpa-onnx لازم شد، AAR را از [ریلیزهای مخزن](https://github.com/k2-fsa/sherpa-onnx/releases)
یا از سورس (پوشه‌ی `android/SherpaOnnxTts`) بازسازی کن و جایگزین کن؛ API سطح Kotlin (`OfflineTts`,
`OfflineTtsConfig`, `OfflineTtsVitsModelConfig`, `GeneratedAudio`) ممکن است بین نسخه‌ها کمی تغییر کند.

## ۶. محیط build و تله‌های شبکه‌ای

- روی این پروژه، **`dl.google.com` روی برخی شبکه‌ها/پراکسی‌ها برای همه‌ی مسیرها ۴۰۴ برمی‌گرداند**
  (حتی برای آرتیفکت‌های واقعاً موجود) — این یعنی `google()` در `pluginManagement`/`dependencyResolutionManagement`
  کار نمی‌کند. راه‌حل به‌کاررفته: به‌جایش از آینه‌ی `https://redirector.gvt1.com/edgedl/android/maven2/`
  استفاده شده (همان محتوای Maven گوگل را سرو می‌کند). اگر در محیط دیگری این مشکل نبود، می‌توانی
  `google()` استاندارد را هم اضافه/جایگزین کنی — ولی چیزی را که کار می‌کند خراب نکن بدون تست.
- Gradle wrapper نسخه‌ی `8.9`، AGP `8.5.2`، Kotlin plugin `1.9.24`، JDK هدف `17`.
- `local.properties` (شامل `sdk.dir` محلی) عمداً commit نشده (`.gitignore`) — قبل از build آن را
  خودت بساز (نمونه در README).

## ۷. ساخت خروجی نهایی (dist)

فایل APK آماده‌ی نصب در `dist/PersianTTS-debug.apk` نگه‌داری می‌شود (کپی از
`app/build/outputs/apk/debug/app-debug.apk` بعد از هر build موفق). این یک بیلد **debug** است
(بدون کلید امضای release). پوشه‌ی `app/build/` خودش commit نمی‌شود (در `.gitignore`).

فایل‌های بزرگ (`dist/*.apk`, `app/libs/*.aar`, `app/src/main/assets/**/*.onnx`) با **Git LFS**
ردیابی می‌شوند (`.gitattributes`) چون از محدودیت ۱۰۰ مگابایتی فایل معمولی گیت‌هاب عبور می‌کنند.

## ۸. تله‌ها (Gotchas)

- `espeak-ng-data` را نمی‌شود مستقیم از `AssetManager` به espeak-ng (کد C) داد؛ باید یک‌بار به مسیر
  واقعی دیسک (`filesDir`) کپی شود. کپی فقط یک‌بار انجام می‌شود (`espeak-ng-data.copied` marker file).
- مدل‌های Piper خام بدون عبور از `convert_piper.py` توسط sherpa-onnx لود نمی‌شوند.
- تغییر گوینده باعث بازسازی کامل `OfflineTts` می‌شود؛ در حین بارگذاری، اسپینر گوینده و دکمه‌ی تبدیل
  غیرفعال‌اند (`setBusy(true, ...)`) — این عمدی است تا کاربر وسط بارگذاری صدا نزند.
- این پروژه روی ماشینی بدون امولاتور/دستگاه فیزیکی اندروید توسعه داده شده؛ build با
  `gradlew assembleDebug` تأیید شده ولی پخش صوت واقعی روی دستگاه تست نشده — قبل از انتشار رسمی حتماً
  روی حداقل یک دستگاه واقعی تست کن.

## ۹. ایده‌های توسعه‌ی آینده (اختیاری)

بیلد release امضاشده، دانلود درون‌برنامه‌ای صداهای بیشتر (به‌جای bundle کردن همه در APK)، کنترل سرعت
گفتار (`speed` پارامتر `generate()` از قبل پاس داده می‌شود ولی UI برایش کنترلی ندارد)، پشتیبانی چندخط
طولانی با پیشرفت تدریجی (streaming synthesis)، تست خودکار UI (Espresso).
