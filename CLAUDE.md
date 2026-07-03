# CLAUDE.md — راهنمای توسعه‌ی «تبدیل متن فارسی به گفتار» (Persian Piper TTS)

> این فایل برای هر نشست هوش مصنوعی/توسعه‌دهنده‌ای نوشته شده که قرار است روی این پروژه کار کند.
> پروژه‌ای کاملاً مستقل است؛ هیچ ربطی به پروژه‌ی «حافظان صادق» ندارد.

## ۱. این پروژه چیست

اپلیکیشن اندروید بومی (Kotlin، بدون Compose) که متن فارسی وارد‌شده توسط کاربر را به‌صورت
**کاملاً آفلاین** به گفتار فارسی تبدیل می‌کند. موتور TTS، [Piper](https://github.com/rhasspy/piper)
است (مدل‌های VITS مبتنی بر ONNX) که از طریق کتابخانه‌ی [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
روی دستگاه اجرا می‌شود — نه فراخوانی هیچ API ابری‌ای.

## ۲. قواعد طلایی

1. **آفلاین بعد از اولین دانلود هر مدل** — تولید گفتار خودش هیچ تماس شبکه‌ای ندارد، اما مدل‌ها
   دیگر داخل APK bundle نیستند (بخش ۱۰ را ببین). هر ۵ صدای فارسی Piper (گنجی، گایرو، امیر، گنجی
   ادبی، رضا ابراهیم) و دو مدل ONNX شبیه‌سازی صدا **هنگام نیاز از مخزن گیت‌هاب پروژه دانلود
   می‌شوند** و در `filesDir` کش می‌مانند؛ فقط `espeak-ng-data` (کوچک، ~۱۸ مگابایت، بدون آن هیچ
   صدایی کار نمی‌کند) همچنان داخل `assets` بسته‌بندی است. نتیجه: APK اکنون کوچک است (~۴۲ مگابایت
   دیباگ) اما **اولین استفاده‌ی هر مدل به اینترنت نیاز دارد**؛ بعد از آن همان مدل کاملاً آفلاین
   کار می‌کند.
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

### ۵.۱ نسخه‌ی onnxruntime باید با نسخه‌ی bundle‌شده در sherpa-onnx.aar دقیقاً یکی باشد

از وقتی `ToneColorConverter` (بخش شبیه‌سازی صدا) مستقیماً از `com.microsoft.onnxruntime:onnxruntime-android`
استفاده می‌کند، **دو کتابخانه‌ی `libonnxruntime.so`** در پروژه وجود دارد: یکی داخل `sherpa-onnx.aar`
و یکی از AAR رسمی onnxruntime. چون هر دو در یک مسیر (`lib/<abi>/libonnxruntime.so`) قرار می‌گیرند،
فقط یکی از آن‌ها واقعاً داخل APK نهایی می‌ماند (`pickFirsts` در `app/build.gradle`). ONNX Runtime
تضمین سازگاری ABI را **فقط در یک جهت** می‌دهد (کد قدیمی‌تر + so جدیدتر)، نه برعکس؛ و اندروید
جداگانه به یکسان‌بودن نسخه‌ی `libc++_shared.so` بین دو باینری نیاز دارد. پس نمی‌شود صرفاً «جدیدترین»
نسخه‌ی onnxruntime-android را گذاشت و امیدوار بود کار کند.

**راه‌حلی که در این پروژه استفاده شده: نسخه‌ی onnxruntime-android را دقیقاً برابر با نسخه‌ی
واقعیِ bundle‌شده در sherpa-onnx.aar قرار بده** (نه فقط نزدیک، بلکه دقیقاً همان — با این کار اصلاً
فرقی نمی‌کند کدام so در pickFirst برنده شود، چون محتوایشان یکی است). روش تأیید نسخه‌ی واقعی
bundle‌شده (چون در هیچ مستندی مستقیم نوشته نمی‌شود):

```bash
# AAR را باز کن و .so مربوط به یک ABI را استخراج کن
unzip app/libs/sherpa-onnx.aar -d /tmp/sherpa_aar
py -c "
data = open('/tmp/sherpa_aar/jni/arm64-v8a/libonnxruntime.so','rb').read()
import re
# رشته‌ی 'Build Info: git-branch=..., git-commit-id=XXXXXXXXXX' را پیدا کن
m = re.search(rb'git-commit-id=([0-9a-f]{10})', data)
print(m.group(1))
"
# سپس commit id را در گیت‌هاب microsoft/onnxruntime چک کن:
# https://api.github.com/repos/microsoft/onnxruntime/commits/<commit-id>
# پیام کامیت معمولاً می‌گوید دقیقاً چه نسخه‌ای ریلیز شده (مثلاً "ORT 1.24.3 release cherry pick").
```

فعلاً نسخه‌ی تأییدشده **۱.۲۴.۳** است و `app/build.gradle` دقیقاً همین را pin کرده. اگر
`sherpa-onnx.aar` را آپدیت کردی، **حتماً این تأیید را دوباره انجام بده** و نسخه‌ی onnxruntime-android
را هم‌زمان به‌روزرسانی کن؛ در غیر این صورت هم موتور اصلی Piper (که کل ۵ صدا از آن استفاده می‌کنند) و
هم موتور شبیه‌سازی صدا در معرض خطر ناسازگاری نسخه قرار می‌گیرند. تأیید نهایی درستیِ pin: بعد از build،
اندازه‌ی `lib/arm64-v8a/libonnxruntime.so` داخل APK را با اندازه‌ی همان فایل داخل `sherpa-onnx.aar`
مقایسه کن — باید کاملاً برابر باشند (یعنی همان باینری است، نه فقط شماره‌نسخه‌ی مشابه).

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

فایل‌های بزرگ (`dist/*.apk`, `app/libs/*.aar`, `models/**/*.onnx`) با **Git LFS**
ردیابی می‌شوند (`.gitattributes`) چون از محدودیت ۱۰۰ مگابایتی فایل معمولی گیت‌هاب عبور می‌کنند.
**نکته‌ی حیاتی:** فایل‌های زیر `models/` عمداً در ریپو نگه‌داری می‌شوند (نه فقط build artifact) —
چون در زمان اجرا مستقیماً از شاخه‌ی `main` همین ریپو دانلود می‌شوند (بخش ۱۰). **هرگز این پوشه را از
ریپو حذف نکن**، حتی اگر به‌نظر برسد فقط خروجی موقت است؛ حذفش تمام صداها/شبیه‌سازی صدا را برای همه‌ی
نصب‌های موجود اپ می‌شکند (لینک‌های دانلود ۴۰۴ می‌شوند).

## ۸. تله‌ها (Gotchas)

- `espeak-ng-data` را نمی‌شود مستقیم از `AssetManager` به espeak-ng (کد C) داد؛ باید یک‌بار به مسیر
  واقعی دیسک (`filesDir`) کپی شود. کپی فقط یک‌بار انجام می‌شود (`espeak-ng-data.copied` marker file).
- مدل‌های Piper خام بدون عبور از `convert_piper.py` توسط sherpa-onnx لود نمی‌شوند.
- **`convert_piper.py` باید `tokens.txt` را با `newline="\n"` بنویسد، نه حالت متنی پیش‌فرض.** روی
  ویندوز، `open(path, "w")` هر `\n` را به `\r\n` تبدیل می‌کند. پارسر C++ سمت sherpa-onnx
  (`piper-phonemize-lexicon.cc`) هر خط `tokens.txt` را با split روی فاصله می‌خواند؛ اگر `\r` باقی
  بماند، به انتهای شناسه‌ی عددی می‌چسبد و پارس عدد کرش می‌کند (کرش native، غیرقابل catch در Kotlin).
  این دقیقاً علت کرش سه صدای امیر/گنجی‌ادبی/رضا ابراهیم بود (رفع‌شده در این نشست؛ `tokens.txt` هر
  ۵ صدا اکنون با LF یکدست است). اگر صدای جدیدی اضافه می‌کنی و بلافاصله با انتخابش کرش کردی، اول
  `file tokens.txt` را چک کن (باید «UTF-8 text» باشد، نه «with CRLF line terminators»).
- `convert_piper.py` را دوبار روی یک فایل onnx اجرا نکن بدون تمیزکاری؛ نسخه‌ی فعلی این را با پاک‌کردن
  `metadata_props` قبلی قبل از نوشتن مجدد گارد می‌کند (قبلاً کلیدهای تکراری تولید می‌کرد که
  `onnx.checker.check_model` را fail می‌کرد — هرچند در عمل onnxruntime آن را بی‌خطر نادیده می‌گرفت).
- تغییر گوینده باعث بازسازی کامل `OfflineTts` می‌شود؛ در حین بارگذاری، اسپینر گوینده و دکمه‌ی تبدیل
  غیرفعال‌اند (`setBusy(true, ...)`) — این عمدی است تا کاربر وسط بارگذاری صدا نزند.
- این پروژه روی ماشینی بدون امولاتور/دستگاه فیزیکی اندروید توسعه داده شده؛ build با
  `gradlew assembleDebug` تأیید شده ولی پخش صوت واقعی روی دستگاه تست نشده — قبل از انتشار رسمی حتماً
  روی حداقل یک دستگاه واقعی تست کن.
- **`tokens.txt` با Git LFS ردیابی نمی‌شود، فقط `*.onnx` ردیابی می‌شود** (`.gitattributes`). یعنی
  URL دانلود `tokens.txt` باید `raw.githubusercontent.com` باشد، نه `media.githubusercontent.com/media`
  (که برای فایل‌های LFS است) — استفاده از URL اشتباه برای `tokens.txt` یک صفحه‌ی HTML/404 برمی‌گرداند
  نه محتوای واقعی فایل. `ModelDownloader.ModelFile.isLfs` همین تفاوت را مدل می‌کند.
- **`INTERNET`/`ACCESS_NETWORK_STATE` به `AndroidManifest.xml` اضافه شدند** (قبلاً اپ هیچ‌کدام را
  نداشت چون کاملاً آفلاین بود) — لازم برای دانلود مدل‌ها هنگام نیاز (بخش ۱۰).

## ۹. شبیه‌سازی صدا (Voice Cloning) — آزمایشی

### چیستی و معماری

صفحه‌ی دوم (`voiceclone/VoiceCloneActivity.kt`، از دکمه‌ی «شبیه‌سازی صدا» در `MainActivity` باز
می‌شود) امکان تولید گفتار فارسی با تُن/طنین صدای یک نمونه‌ی دلخواه (ضبط‌شده یا انتخاب‌شده) را می‌دهد؛
کاملاً آفلاین، بدون هیچ تماس شبکه‌ای در زمان اجرا.

sherpa-onnx (نسخه‌ی bundle‌شده در این پروژه) هیچ پشتیبانی بومی از voice cloning/voice conversion
دلخواه ندارد (فقط چند مدل zero-shot خاص مثل ZipVoice/Kokoro را با API خودشان پشتیبانی می‌کند که
جایگزین کامل Piper نیستند و برای فارسی مدل آماده ندارند). معماری انتخاب‌شده، **تبدیل تن صدا به‌عنوان
پس‌پردازش** است:

```
متن فارسی → Piper (صدای «گنجی»، ۲۲۰۵۰ هرتز) → مبدل تن صدای OpenVoice → خروجی نهایی
نمونه صدای کاربر → استخراج بردار تن صدا (۲۵۶بعدی) ──────────────────┘
```

این یعنی **محتوا/تلفظ/آهنگ کلام از Piper می‌آید (فارسی درست تضمین‌شده)**، فقط طنین صدا از نمونه‌ی
کاربر گرفته می‌شود. مدل استفاده‌شده: [OpenVoice](https://github.com/myshell-ai/OpenVoice) از
MyShell-AI/MIT — دقیقاً tone-color-converter آن (نه کل خط لوله‌ی TTSاش)، چون این بخش زبان‌مستقل است
(روی موج صدا کار می‌کند، نه فونیم) و نسبتاً کوچک است.

### منبع مدل‌ها

به‌جای تبدیل خودمان checkpoint پای‌تورچ OpenVoice به ONNX (پرریسک/زمان‌بر)، از یک **صادرات ONNX
آماده و تأییدشده‌ی جامعه** استفاده شد:

- مخزن: [`seasonstudio/openvoice_tone_clone_onnx`](https://huggingface.co/seasonstudio/openvoice_tone_clone_onnx) روی HuggingFace
- فایل‌ها (در `models/voice_clone/` (در ریپو، نه bundle در APK)):
  - `tone_color_extract_model.onnx` (~۳.۳ مگابایت) — استخراج بردار تن صدا (۲۵۶بعدی) از یک نمونه صدا
  - `tone_clone_model.onnx` (~۱۲۸ مگابایت) — اعمال یک بردار تن صدای مقصد روی یک صدای مبدأ
- کانفیگ اصلی OpenVoice (از [`myshell-ai/OpenVoiceV2`](https://huggingface.co/myshell-ai/OpenVoiceV2) `converter/config.json`)
  تأیید شد: `sampling_rate: 22050, filter_length: 1024, hop_length: 256, win_length: 1024, gin_channels: 256`
  — **نرخ نمونه‌برداری ۲۲۰۵۰ هرتز دقیقاً برابر خروجی مدل‌های Piper است**، پس صدای Piper نیازی به
  resample قبل از ورود به مبدل ندارد (فقط نمونه‌ی مرجع کاربر که می‌تواند هر نرخی داشته باشد resample می‌شود).
- این pipeline با یک اثبات مفهوم کامل در پایتون (دانلود مدل‌ها، پیاده‌سازی STFT با numpy، اجرای
  هر دو مدل با onnxruntime) در همین نشست تأیید شد: ورودی/خروجی/شکل تنسورها دقیقاً مطابق مستندات و
  بدون خطا اجرا شدند (خروجی float32 سالم، بدون NaN، طول/نرخ نمونه‌برداری صحیح).

### پیاده‌سازی Android (`app/src/main/java/com/example/persiantts/voiceclone/`)

- **`ToneColorConverter.kt`** — مستقیماً از **ONNX Runtime رسمی جاوا/کاتلین** (`ai.onnxruntime.*`)
  استفاده می‌کند، نه binding کاتلین sherpa-onnx (که فقط مدل‌های خودش را می‌شناسد، نه یک گراف ONNX
  دلخواه). شامل: پیاده‌سازی دستی STFT خطی (Cooley-Tukey FFT درجا، n_fft=۱۰۲۴, hop=۲۵۶, پنجره‌ی Hann,
  reflect padding — دقیقاً هم‌ارز با `spectrogram_torch` اصلی OpenVoice)، `extractToneColor()`,
  `convert()`, و resample خطی ساده برای نمونه‌های غیر-۲۲۰۵۰هرتزی.
- **`AudioIo.kt`** — نوشتن هدر WAV دستی (برای خروجی خام AudioRecord)، و دیکود فایل صوتی دلخواه
  (mp3/m4a/ogg/...) با `MediaExtractor`+`MediaCodec` (API استاندارد اندروید، بدون وابستگی خارجی) به
  PCM مونوی float.
- **`VoiceRecorder.kt`** — ضبط با `AudioRecord` خام (نه `MediaRecorder`) چون PCM مستقیم لازم است؛
  روی Thread جدا (بدون coroutines، هم‌سو با بقیه‌ی پروژه).
- **`VoiceCloneActivity.kt`** — یک `OfflineTts` مستقل (صدای ثابت «گنجی») + یک `ToneColorConverter`
  بارگذاری می‌کند؛ UI مشابه الگوی `MainActivity` (XML+findViewById، پخش/ذخیره/اشتراک‌گذاری با
  MediaPlayer/MediaStore/FileProvider). دسترسی میکروفون با `registerForActivityResult` runtime
  درخواست می‌شود (`androidx.activity`، از قبل transitively از appcompat در دسترس بود).

### وابستگی onnxruntime-android و تعارض native lib

`com.microsoft.onnxruntime:onnxruntime-android` به `app/build.gradle` اضافه شد (چون AAR sherpa-onnx
فقط binding کاتلین خودش — `com.k2fsa.sherpa.onnx.*` — را دارد، نه کلاس‌های `ai.onnxruntime.*`).
**هر دو AAR یک `libonnxruntime.so` در همان مسیر `jni/<abi>/` دارند** — تعارض مستقیم packaging.

نسخه‌ی اولیه‌ای که در این ویژگی اضافه شد (۱.۲۲.۰) بر این فرض غلط بود که AAR سرشپا-آنکس نسخه‌ی
onnxruntime قدیمی‌تری (بر اساس یک ایشوی گیت‌هاب که ۱.۱۷.۱ را توصیف می‌کرد) دارد، پس «جدیدتر گذاشتن»
ایمن به‌نظر می‌رسید. **این فرض هنگام بازبینی کد رد شد**: با استخراج واقعیِ `sherpa-onnx.aar` و پیدا
کردن رشته‌ی `git-commit-id` داخل خودِ `.so` (روش کامل در بخش ۵.۱)، مشخص شد نسخه‌ی واقعیِ bundle‌شده
**۱.۲۴.۳** است — یعنی **جدیدتر** از ۱.۲۲.۰، نه قدیمی‌تر. این یعنی `pickFirsts` داشت نسخه‌ی *قدیمی‌تر*
را به‌جای نسخه‌ای که موتور Piper واقعاً با آن build/تست شده جایگزین می‌کرد — دقیقاً همان جهتی که
مستندات خودِ ONNX Runtime **تضمین سازگاری نمی‌دهد** (کد قدیمی + so جدید ایمن است؛ برعکسش نه).

**راه‌حل نهایی: نسخه‌ی `onnxruntime-android` را دقیقاً برابر ۱.۲۴.۳ pin کردیم** (نه صرفاً «جدیدترین»)،
طوری که هر دو مسیر (`libsherpa-onnx-c-api.so` برای Piper و `ai.onnxruntime.*` برای `ToneColorConverter`)
از یک باینری کاملاً یکسان استفاده کنند و اصلاً مهم نباشد کدام‌یک در `pickFirsts` برنده می‌شود. تأیید
شد: `lib/arm64-v8a/libonnxruntime.so` داخل APK نهایی از نظر اندازه (۲۵٬۸۳۱٬۶۳۲ بایت) و محتوای
`git-commit-id` دقیقاً با نسخه‌ی داخل `sherpa-onnx.aar` یکی است. جزئیات کامل و روش تأیید در بخش ۵.۱.

### محدودیت‌های شناخته‌شده

- **تست نشده روی دستگاه واقعی** (این ماشین امولاتور/دستگاه ندارد)؛ تأیید فقط با build موفق +
  اثبات مفهوم پایتون + بازبینی دقیق شکل تنسورها انجام شده.
- کیفیت شبیه‌سازی best-effort است؛ tone-color-converter یک مدل سبک‌وزن (نه یک کلون‌کننده‌ی کامل
  صدا مثل مدل‌های بزرگ ابری) است و می‌تواند با نمونه‌های کوتاه/نویزی نتیجه‌ی ضعیف بدهد.
  پیام‌های خطای فارسی برای نمونه‌ی خیلی کوتاه (`error_reference_too_short`, کمتر از ۲ ثانیه) و
  نمونه‌ی ساکت (`error_reference_silent`, بر پایه‌ی آستانه‌ی RMS) در UI نمایش داده می‌شوند.
- STFT با پیاده‌سازی FFT دستی (نه کتابخانه‌ی بهینه‌شده) روی گوشی‌های ضعیف می‌تواند چند ثانیه طول بکشد؛
  اگر کند بود، اولین گزینه‌ی بهینه‌سازی، جایگزینی با یک FFT بهینه‌تر یا native است.
- حجم APK را حدود ۱۳۱ مگابایت (دو مدل ONNX) + کتابخانه‌ی onnxruntime-android افزایش داده.

### ۹.۱ رفع‌های بازبینیِ کد (code review) روی این ویژگی

بعد از پیاده‌سازی اولیه، یک بازبینی کد (۳ ایجنت مستقل + بررسی دستی) چند اشکال واقعی پیدا کرد که رفع شدند:

- **نسخه‌ی onnxruntime** — رفع شد، جزئیات کامل در بخش ۵.۱ و بالاتر در همین بخش.
- **race در `VoiceRecorder.stop()`** — قبلاً `join(500)` قبل از `audioRecord.stop()` صدا زده می‌شد؛
  اگر ترد ضبط داخل `read()` مسدود بود، `release()` هم‌زمان با یک read فعال اجرا می‌شد. رفع: ترتیب
  عوض شد (`stop()` قبل از `join`، با timeout بیشتر ۲۰۰۰ms) چون متوقف‌کردن AudioRecord معمولاً باعث
  برگشتن یک read مسدود می‌شود.
- **نشتی حافظه‌ی بومی در `VoiceCloneActivity.loadEngines()`** — اگر کاربر قبل از پایان بارگذاری مدل
  (چند ثانیه) از صفحه خارج شود، `onDestroy()` چیزی برای آزادکردن نداشت (فیلدها هنوز null بودند) و
  callback بعدی مقادیر native را روی اکتیویتیِ مرده می‌نوشت. رفع: چک `isFinishing || isDestroyed`
  داخل callback و آزادسازی فوری در آن حالت.
- **بلاک‌شدن UI thread در `onReferencePlayClicked`** — نوشتن WAV روی دیسک + `MediaPlayer.prepare()`
  مسدودکننده مستقیم داخل کلیک‌هندلر بود. رفع: نوشتن فایل روی Thread جدا + `prepareAsync()`.
- **`AudioIo.decodeToMonoFloat` نادیده‌گرفتن `INFO_OUTPUT_FORMAT_CHANGED`** — اگر دیکودر فرمت
  متفاوتی از فرمت اعلام‌شده‌ی track خروجی بدهد (بعضی فایل‌های AAC)، تعداد کانال/نرخ نمونه‌برداری
  اشتباه استفاده می‌شد. رفع: هندل صریح این کد در حلقه‌ی dequeueOutputBuffer.
- **کد تکراری برای کپی `espeak-ng-data`** — بین `MainActivity` و `VoiceCloneActivity` عیناً تکرار
  شده بود و بدون قفل، می‌توانست race ایجاد کند (یکی در حال کپی، دیگری همان مسیر را پاک/مارک می‌کرد).
  رفع: استخراج به `EspeakDataInstaller` (آبجکت مشترک، `synchronized`) و حذف کد تکراری از هر دو کلاس.
- **ناهماهنگی متن راهنما** — `hint_reference_sample` می‌گفت حداقل ۵-۲۰ ثانیه، ولی حداقل واقعیِ
  enforce‌شده و پیام خطا ۲ ثانیه بود. رفع: متن راهنما اصلاح شد تا هر دو عدد را به‌وضوح بگوید.

**رفع‌نشده (مستندشده، نه بحرانی):** هر بار که صفحه‌ی شبیه‌سازی صدا باز می‌شود، یک `OfflineTts` کاملاً
مستقل (صدای گنجی) علاوه‌بر نمونه‌ی احتمالاً از‌قبل‌بارگذاری‌شده در `MainActivity` ساخته می‌شود —
مصرف حافظه‌ی بومی را روی گوشی‌های کم‌رم تقریباً دوبرابر می‌کند. رفع درست نیازمند یک singleton
سطح Application برای موتور Piper است؛ به‌عنوان بهبود آینده مستند شده، نه رفع‌شده در این نشست.

## ۱۰. دانلود مدل‌ها هنگام نیاز (on-demand) — پیاده‌سازی‌شده

کاربر خواسته صداهای Piper و مدل‌های voice cloning به‌جای bundle در APK، هنگام نیاز از اینترنت دانلود
شوند تا حجم نصب اولیه کم بماند. **این کار پیاده‌سازی شده است.**

### منبع فایل‌ها (بدون نیاز به هاست/توکن جدید)

فایل‌های تبدیل‌شده (`tokens.txt` ساخته‌شده، متادیتای ONNX تزریق‌شده — همان‌طور که قبلاً هم بودند)
از قبل **در همین مخزن گیت‌هاب پروژه** (`mohamadkheiry/persian-tts`, شاخه‌ی `main`, عمومی) در همان
مسیرهای assets قبلی commit شده‌اند و با Git LFS ردیابی می‌شوند (`.gitattributes`: فقط `*.onnx`،
`*.aar`، `*.apk` — نه `tokens.txt` که یک فایل متنی کوچک معمولی است). این یعنی دو نوع URL لازم است:

- فایل‌های `.onnx` (LFS): `https://media.githubusercontent.com/media/mohamadkheiry/persian-tts/main/<مسیر>`
- فایل‌های `tokens.txt` (غیر-LFS): `https://raw.githubusercontent.com/mohamadkheiry/persian-tts/main/<مسیر>`

هر دو الگو با `curl -sI` در طول توسعه تأیید شدند (HTTP 200 + `Content-Length` درست، بدون نیاز به
احراز هویت). **هیچ GitHub Release/`gh` CLI/توکنی لازم نیست** — خود مخزن به‌عنوان CDN عمل می‌کند.

### `ModelDownloader.kt` (پکیج پایه، هم‌سو با سبک `EspeakDataInstaller`)

شیء Kotlin ساده، بدون کتابخانه‌ی HTTP جدید (`HttpURLConnection` خام). نکات کلیدی:
- `downloadAll(destDir, files, onProgress)`: بلاک‌کننده (باید روی Thread جدا صدا زده شود)؛ اول با
  یک درخواست `HEAD` اندازه‌ی هر فایل را می‌گیرد (برای درصد کلی درست)، بعد هر فایل را با `GET`
  می‌گیرد و به‌صورت `<file>.part` می‌نویسد؛ اگر اندازه‌ی نهایی با `Content-Length` نخواند، فایل
  `.part` پاک و خطا پرتاب می‌شود (چک صحت پایه). موفق → `rename` به نام نهایی.
- `isFullyDownloaded(destDir, files)`: چک سریع بدون شبکه (آیا فایل‌ها از قبل با اندازه‌ی معتبر
  موجودند) — قبل از تصمیم به نمایش UI دانلود صدا زده می‌شود.
- قفل per-destDir (`synchronized`) از دانلود هم‌زمان تکراری همان مدل توسط دو ترد جلوگیری می‌کند.
- خطای شبکه → `ModelDownloadException` با پیام فارسی ثابت
  «دانلود مدل با خطا مواجه شد؛ اتصال اینترنت را بررسی کنید» (`ModelDownloader.NETWORK_ERROR_MESSAGE`).
- مقصد دانلود: `filesDir/voices/<voiceAssetDir>/` برای صداهای Piper، `filesDir/voice_clone/` برای
  دو مدل شبیه‌سازی صدا.

### تغییر بارگذاری مدل: assetManager=null ⇒ مسیر فایل‌سیستم مطلق (نه assets)

`OfflineTts` از sherpa-onnx **دو native constructor مختلف** دارد: `newFromAsset(assetManager, config)`
و `newFromFile(config)`. با decompile کردن `classes.jar` داخل `sherpa-onnx.aar`
(`javap -c` روی `OfflineTts.class`) تأیید شد که constructor عمومی کاتلین بر اساس **null بودن
پارامتر `assetManager`** بین این دو انتخاب می‌کند: اگر `assetManager != null` → `newFromAsset`
(مسیرها نسبت به assets خوانده می‌شوند)، اگر `assetManager == null` → `newFromFile` (مسیرهای
`model`/`tokens`/`dataDir` در `OfflineTtsVitsModelConfig` به‌عنوان **مسیر مطلق فایل‌سیستم**
خوانده می‌شوند). امضای کاتلین با مقدار پیش‌فرض `assetManager: AssetManager? = null` این را از
بیرون هم confirm می‌کند. پس تنها تغییر لازم در `MainActivity.loadTtsEngine()` و
`VoiceCloneActivity.loadEngines()`: صدا زدن `OfflineTts(assetManager = null, config = ...)` و
دادن `File(destDir, fileName).absolutePath` به‌جای `"assetDir/fileName"`.

برای `ToneColorConverter` (که مستقیماً `ai.onnxruntime.*` استفاده می‌کند، نه binding sherpa-onnx)
تغییر ساده‌تر بود: `OrtEnvironment.createSession(String modelPath, SessionOptions)` یک overload
استاندارد و پایدار ONNX Runtime است که مستقیماً مسیر فایل می‌گیرد؛ کلاس دیگر `AssetManager`
نمی‌گیرد، بلکه دو `String` (مسیر مطلق دو فایل `.onnx` دانلودشده) می‌گیرد.

### رفتار UI (بخش‌های ۵ و ۶ زیر را هم ببین)

1. **گنجی (پیش‌فرض)** در `MainActivity.onCreate()` بلافاصله دانلود می‌شود (همان مسیر
   `loadTtsEngine()` که برای هر صدا استفاده می‌شود) — نوار پیشرفت درصدی از همان لحظه‌ی اول قابل‌دیدن
   است، کاربر مجبور نیست دنبال دکمه‌ی دانلود بگردد.
2. **بقیه‌ی صداهای اسپینر:** انتخاب یک صدای دانلودنشده همان مسیر دانلود→بارگذاری را طی می‌کند؛
   صدای از‌قبل‌دانلودشده مستقیم بارگذاری می‌شود (بدون دانلود مجدد، `isFullyDownloaded` چک می‌کند).
3. **صفحه‌ی شبیه‌سازی صدا:** `loadEngines()` هم صدای پایه (گنجی) هم دو مدل ONNX را چک/دانلود می‌کند
   (اگر هرکدام دانلود نشده باشند) قبل از ساخت `OfflineTts`/`ToneColorConverter`.
4. **دو نوار پیشرفت جدا:** `downloadProgressBar`/`cloneDownloadProgressBar` (قطعی، درصدی، برای
   دانلود) در برابر `progressSpinner`/`cloneProgressSpinner` (نامعین، برای بارگذاری موتور/تولید
   گفتار) — پیام‌های فارسی این سه حالت («در حال دانلود…XX٪» / «در حال بارگذاری موتور صدا…» /
   «در حال تولید گفتار…») دیگر با هم قاطی نمی‌شوند (رفع یک ابهام قبلی که `status_loading_voice` و
   `status_generating` تقریباً یک‌جور به‌نظر می‌رسیدند).
5. `espeak-ng-data` مطابق تصمیم قبلی همچنان bundle در APK ماند — بدون تغییر در `EspeakDataInstaller.kt`.

### تأیید انجام‌شده (بدون دستگاه واقعی)

- `./gradlew assembleDebug` تمیز موفق شد؛ APK دیباگ از ~۵۰۴ مگابایت به **~۴۲ مگابایت** رسید (تأیید
  با `unzip -l` که هیچ فایل `.onnx`/مدل صدایی داخل APK نیست، فقط `espeak-ng-data`).
  حجم assets پروژه (پوشه‌ی سورس، نه APK) از ۴۴۵ مگابایت به ۱۸ مگابایت رسید.
- حداقل ۸ URL دانلود (۵ فایل `.onnx` صدا + ۲ فایل `tokens.txt` + ۲ مدل ONNX شبیه‌سازی صدا) با
  `curl -sI` مستقیم از این ماشین تست شدند: همه HTTP 200 با `Content-Length` معتبر.
- رفتار `newFromFile` در برابر `newFromAsset` با decompile واقعی بایت‌کد تأیید شد (نه فقط حدس از
  روی مستندات)، طبق روش بالا.
- **تست‌نشده بدون دستگاه واقعی:** دانلود واقعی روی گوشی (رفتار شبکه‌ی واقعی، timeout، قطع‌شدن وسط
  دانلود)، اینکه `newFromFile` واقعاً فایل ONNX دانلودشده را می‌خواند و صدای درست تولید می‌کند،
  و رفتار UI (نوار پیشرفت، پیام‌ها) روی صفحه‌ی واقعی. این محدودیت مثل بقیه‌ی این پروژه است (بخش ۸
  گزارش کلی build).

## ۱۱. تبدیل گفتار به نوشتار (Speech-to-Text) — پیاده‌سازی‌شده

صفحه‌ی سوم (`stt/SttActivity.kt`، از دکمه‌ی «تبدیل گفتار به نوشتار» در `MainActivity` باز می‌شود)
یک نمونه صدا (ضبط‌شده یا انتخاب‌شده) را به متن فارسی تبدیل می‌کند — کاملاً آفلاین بعد از اولین دانلود
مدل، بدون هیچ تماس شبکه‌ای دیگر.

### تصمیم Vosk در برابر Whisper — یک تصحیح مهم

بررسی اولیه فرض کرده بود «Vosk مدل فارسی ندارد»؛ **این فرض غلط بود.** Vosk واقعاً مدل‌های رسمی
فارسی دارد (`vosk-model-fa-0.42`, `vosk-model-small-fa-0.42`, و نسخه‌ی جدیدتر `vosk-model-fa-0.5`/
`vosk-model-small-fa-0.5` — نگاه کنید به https://alphacephei.com/vosk/models). دلیل واقعیِ انتخاب
Whisper به‌جای Vosk چیز دیگری است: **sherpa-onnx (تنها runtime ASR/TTS این پروژه، بدون امکان افزودن
وابستگی بومی جدید) اصلاً loader ای برای فرمت مدل‌های Vosk ندارد.** Vosk بر پایه‌ی مدل‌های Kaldi با
فرمت اختصاصی خودش کار می‌کند که هیچ جایگاهی در `OfflineModelConfig` سرشپا-آنکس ندارد (که فقط از
انواع مشخصی مثل transducer/paraformer/whisper/... پشتیبانی می‌کند — تأیید با decompile کردن
`classes.jar` داخل `sherpa-onnx.aar`: کلاس‌های `OfflineRecognizer`, `OfflineRecognizerConfig`,
`OfflineModelConfig`, `OfflineWhisperModelConfig`, `OfflineStream`, `OfflineRecognizerResult`,
`FeatureConfig` همگی موجودند و دقیقاً با مستندات رسمی kotlin-api سرشپا-آنکس مطابقت دارند، اما هیچ
`OfflineVoskModelConfig` یا مشابهی وجود ندارد). پس چون این پروژه متعهد به sherpa-onnx به‌عنوان تنها
runtime است (بدون افزودن یک کتابخانه‌ی بومی کاملاً جدید فقط برای Vosk)، **Whisper-از-طریق-sherpa-onnx
تنها گزینه‌ای بود که به وابستگی بومی جدید نیاز نداشت** — نه اینکه Vosk گزینه‌ی بدتری برای فارسی باشد.

### انتخاب مدل: sherpa-onnx Whisper "large-v3-turbo" (چندزبانه، int8)

Whisper در آموزش چندزبانه‌ی خودش (۹۹ زبان طبق مقاله‌ی OpenAI) فارسی را هم پوشش می‌دهد. نسخه‌ی اولیه‌ی
این ویژگی از `base` استفاده می‌کرد (مصالحه‌ی حجم/دقت برای دانلود کوچک)، ولی کاربر با گوشیِ ۱۶ گیگ رم
درخواست کرد بهترین دقت/سرعت ممکن استفاده شود، بدون نگرانی از حجم دانلود. **`large-v3-turbo`** انتخاب
شد نه `large-v3` خام: turbo نسخه‌ی رسمی OpenAI با decoder هرس‌شده (۴ لایه به‌جای ۳۲ لایه‌ی large-v3)
است — تقریباً همان کیفیت/پوشش چندزبانه‌ی `large-v3` را حفظ می‌کند ولی چند برابر سریع‌تر روی CPU
inference می‌کند، چون بار دیکود کردن گام‌به‌گام (که کندترین بخش inference است) خیلی کمتر شده. مدل‌های
`distil-large-v3`/`distil-large-v3.5` عمداً کنار گذاشته شدند چون تقطیرشان عمدتاً روی داده‌ی انگلیسی
انجام شده و کیفیت فارسی‌شان تضمین‌شده نیست؛ `turbo` رسمی و صریحاً چندزبانه است.

منبع: [`csukuangfj/sherpa-onnx-whisper-turbo`](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo)
روی HuggingFace — همان مخزن جامعه‌ای که نسخه‌ی base هم از آن آمده بود.

فایل‌ها و اندازه‌های دقیق (تأیید‌شده با `curl -sIL` از این ماشین که واقعاً به همین بایت دانلود و
مقایسه شدند — دانلود اول با timeout کوتاه ناقص مانده بود، با `-C -` و timeout بلندتر تکرار و تأیید شد):

- `turbo-encoder.int8.onnx` → ۶۷۴٬۷۱۶٬۲۹۷ بایت (~۶۴۳ مگابایت)
- `turbo-decoder.int8.onnx` → ۳۶۱٬۰۸۰٬۷۶۴ بایت (~۳۴۴ مگابایت)
- `turbo-tokens.txt` → ۸۱۶٬۷۳۰ بایت (همان واژگان چندزبانه‌ی base، بدون تغییر)

مجموع دانلود اول این قابلیت اکنون **~۱ گیگابایت** است (در برابر ~۱۵۳ مگابایت نسخه‌ی base) — عمدی و
پذیرفته‌شده چون کاربر گوشی با رم/فضای کافی دارد و روی on-demand-download اجرا می‌شود، نه bundle در APK.

این سه فایل در `models/stt/` این مخزن با نام‌های `whisper-turbo-encoder.int8.onnx`,
`whisper-turbo-decoder.int8.onnx`, `tokens.txt` قرار گرفته‌اند (مثل بقیه‌ی پوشه‌های `models/`، بخش ۷).
`.gitattributes` از قبل `*.onnx` را با Git LFS و `models/** -text` را برای این پوشه ردیابی می‌کند —
نیازی به تغییر `.gitattributes` نبود، همان مکانیزم `models/ganji/`، `models/voice_clone/` و... کار می‌کند.

### افزودن به `ModelDownloader.kt`

هم‌سو دقیق با الگوی `voiceCloneDestDir`/`voiceCloneModelFiles`: `ModelDownloader.sttDestDir(context)`
(→ `filesDir/stt/`) و `ModelDownloader.sttModelFiles` (دو `ModelFile` با `isLfs = true` برای دو
`.onnx` + یک `ModelFile` با `isLfs = false` برای `tokens.txt`، دقیقاً مثل الگوی صداهای Piper).

### الزام resample به ۱۶۰۰۰ هرتز

بر خلاف مسیر Piper/OpenVoice این پروژه که همه‌چیز روی ۲۲۰۵۰ هرتز هماهنگ است، **Whisper همیشه دقیقاً
۱۶۰۰۰ هرتز مونو انتظار دارد** (`FeatureConfig.sampleRate = 16000, featureDim = 80` برای استخراج
مل-اسپکتروگرام). چون `VoiceRecorder` با ۲۲۰۵۰ هرتز ضبط می‌کند و `AudioIo.decodeToMonoFloat` نرخ
نمونه‌برداریِ اصلیِ فایل انتخاب‌شده را برمی‌گرداند (هر چه باشد)، `SttActivity` یک resample خطی ساده
(linear interpolation) به ۱۶۰۰۰ هرتز اعمال می‌کند. تابع مشابهی از قبل در `ToneColorConverter`
(`resampleLinear`, هدف ثابت ۲۲۰۵۰) وجود دارد اما `private` است و هدفش هاردکد شده؛ به‌جای تغییر آن
کلاس، یک نسخه‌ی معادلِ کوچک (نرخ مقصد به‌عنوان پارامتر) مستقیماً در `SttActivity.kt` نوشته شده —
هم‌سو با راهنمای این کار (بدون وابستگی جدید، بدون overengineering).

### استفاده‌ی مجدد از کلاس‌های ضبط/دیکود صدای شبیه‌سازی صدا

`voiceclone/VoiceRecorder.kt` و `voiceclone/AudioIo.kt` تغییری نکردند و کپی هم نشدند — هر دو کلاس
از قبل بدون `private`/`internal` (یعنی public پیش‌فرض کاتلین) هستند، پس `SttActivity` مستقیماً با
import کاملاً مشخص (`com.example.persiantts.voiceclone.VoiceRecorder`,
`com.example.persiantts.voiceclone.AudioIo`) از پکیج دیگر به آن‌ها دسترسی دارد — دقیقاً همان الگویی
که `VoiceCloneActivity` از قبل برای `com.example.persiantts.ModelDownloader` و
`com.example.persiantts.EspeakDataInstaller` استفاده می‌کند. هیچ جابه‌جایی فیزیکی فایل لازم نبود.

### پیاده‌سازی Android (`app/src/main/java/com/example/persiantts/stt/SttActivity.kt`)

ساختار کاملاً مطابق `VoiceCloneActivity.kt`: XML layout + `findViewById` (بدون Compose)، `Thread`
خام + `Handler(Looper.getMainLooper())` (بدون coroutines)، `try/catch` جدا برای
`ModelDownloadException` در برابر استثنای عمومی، دو نوار پیشرفت جدا (`sttDownloadProgressBar` قطعی
برای دانلود، `sttProgressSpinner` نامعین برای بارگذاری موتور/تبدیل گفتار)، و چک
`isFinishing || isDestroyed` داخل callback بارگذاری موتور (همان رفع نشتی حافظه‌ی بومی مستندشده در
بخش ۹.۱ برای `VoiceCloneActivity`، این‌جا هم برای `OfflineRecognizer.release()` اعمال شده).

جریان کار: دانلود (در صورت نیاز) سه فایل مدل → ساخت `OfflineRecognizer` با
`OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder=..., decoder=..., language="fa",
task="transcribe"), tokens=..., modelType="whisper")` و `assetManager = null` (مسیر مطلق فایل‌سیستم،
همان الگوی `newFromFile` مستندشده در بخش ۱۰) → کاربر صدا ضبط/انتخاب می‌کند → چک سکوت/کوتاهی (همان
الگوی RMS<۰.۰۰۱ در `VoiceCloneActivity.applyReferenceSamples`، اما با پیام‌های فارسیِ جداگانه
`error_stt_empty_audio`/`error_stt_silent_audio` چون زمینه‌ی UX فرق دارد) → resample به ۱۶۰۰۰ هرتز →
`createStream()` → `acceptWaveform(samples, 16000)` → `decode(stream)` → `getResult(stream).text` →
`stream.release()` → نمایش متن در یک `TextInputEditText` (راست‌به‌چپ) با دو دکمه‌ی «کپی»
(`ClipboardManager`) و «اشتراک‌گذاری» (`Intent.ACTION_SEND` با `type="text/plain"` — نه الگوی
FileProvider استفاده‌شده برای اشتراک‌گذاری صوت در جاهای دیگر اپ، چون خروجی این‌جا متن است نه فایل).

### لایوت جدید

`app/src/main/res/layout/activity_stt.xml` مستقیماً بر پایه‌ی ساختار/استایل `activity_voice_clone.xml`
(ScrollView + LinearLayout عمودی، `layoutDirection="rtl"`، همان رنگ‌ها/اندازه‌های متن).

### تأیید انجام‌شده (بدون دستگاه واقعی)

- `./gradlew assembleDebug` تمیز موفق شد (بدون خطا، فقط همان هشدار بی‌خطرِ همیشگیِ "Unable to strip
  the following libraries"). حجم APK دیباگ تقریباً بدون تغییر ماند (چند کیلوبایت افزایش برای کد/
  لایوت/رشته‌های جدید؛ خود مدل Whisper در APK نیست، فقط دانلود هنگام نیاز) — تأیید با `unzip -l` که
  هیچ فایل `.onnx` مربوط به Whisper در APK نهایی نیست.
- کلاس‌های `OfflineRecognizer`/`OfflineRecognizerConfig`/`OfflineModelConfig`/
  `OfflineWhisperModelConfig`/`OfflineStream`/`OfflineRecognizerResult`/`FeatureConfig` با استخراج
  مستقیم `classes.jar` از `sherpa-onnx.aar` تأیید شدند (نه فقط حدس از روی مستندات آنلاین).

**تست‌نشده بدون دستگاه واقعی (این ماشین امولاتور/دستگاه ندارد):** دانلود واقعی مدل روی گوشی، رفتار
واقعیِ ضبط میکروفون + resample + تشخیص گفتار Whisper روی یک دستگاه واقعی، و دقت واقعیِ متن فارسیِ
تولیدشده. تأیید فقط با build موفق + بازبینی دقیق کد/بایت‌کد انجام شده، مثل بقیه‌ی این پروژه.

**نکته‌ی حیاتی مشابه بخش ۱۰ — تأییدناپذیر تا push:** آدرس‌های دانلود نهایی
(`https://media.githubusercontent.com/media/mohamadkheiry/persian-tts/main/models/stt/whisper-turbo-*.onnx`
و `https://raw.githubusercontent.com/mohamadkheiry/persian-tts/main/models/stt/tokens.txt`) تا زمانی
که این تغییر commit و به شاخه‌ی `main` گیت‌هاب push نشود، ۴۰۴ برمی‌گردانند — چون فایل‌ها هنوز روی آن
شاخه در گیت‌هاب وجود ندارند (فقط در working tree محلی، هنوز commit نشده‌اند). این دقیقاً همان محدودیتی
است که بخش ۱۰ برای اولین‌بار دانلود مدل‌های TTS/شبیه‌سازی صدا مستند کرده بود؛ بعد از push، همان روش
تأیید (`curl -sI`) باید برای این سه URL هم تکرار شود.

### محدودیت‌های شناخته‌شده

- **تست نشده روی دستگاه واقعی** (این ماشین امولاتور/دستگاه ندارد)؛ فقط build موفق + بازبینی کد.
- دانلود اول این قابلیت اکنون ~۱ گیگابایت است (`turbo`، به‌جای ~۱۵۳ مگابایت `base`) — عمدی، طبق
  درخواست صریح کاربر برای بهترین دقت/سرعت با گوشیِ ۱۶ گیگ رم؛ روی گوشی‌های ضعیف‌تر یا با اینترنت کند
  ممکن است این دانلود اول ناخوشایند باشد.
- استنتاج (inference) با CPU روی گوشی‌های ضعیف هنوز می‌تواند برای ضبط‌های طولانی کند باشد، هرچند
  `turbo` به‌مراتب سریع‌تر از `large-v3` خام است (decoder هرس‌شده)؛ اگر کند بود، اولین گزینه‌ی
  بهینه‌سازی کاهش به `base`/`tiny` (با افت دقت) یا محدودکردن طول نهایتاً حداکثر ضبط است.
- **رفع‌شده در بازبینی کد:** پیاده‌سازی اولیه‌ی `stopRecording()` نوشتن WAV + خواندن/پارس آن را
  مستقیم روی UI thread انجام می‌داد (همان الگوی باگی که در `VoiceCloneActivity.onReferencePlayClicked`
  رفع شد — بخش ۹.۱). رفع شد: این کار حالا روی Thread جدا انجام می‌شود.
- **رفع‌نشده (کم‌خطر، مستندشده):** اگر کاربر دقیقاً وسط تشخیص گفتار (۱-۳ ثانیه) از صفحه خارج شود،
  `onDestroy()` ممکن است `recognizer.release()` را هم‌زمان با استفاده‌ی ترد پس‌زمینه‌ی در حال
  `decode()` صدا بزند (race مشابه آنچه در `loadEngines()` رفع شد، ولی این‌بار در مسیر استفاده نه
  بارگذاری). ریسک پایین است (پنجره‌ی زمانی کوتاه) و به‌عنوان بهبود آینده مستند شده، نه رفع‌شده.

## ۱۲. بیلد «کاملاً بسته‌بندی‌شده» (بدون دانلود در زمان اجرا) — محلی، commit نمی‌شود

کاربر برای تست شخصی یک APK یک‌تکه‌ی کاملاً آفلاین خواست (بدون نیاز به دانلود مدل بعد از نصب). به‌جای
حذف معماری on-demand-download (بخش ۱۰)، هر سه فایل بارگذاریِ موتور (`MainActivity.loadTtsEngine`,
`VoiceCloneActivity.loadEngines`, `SttActivity.loadEngine`) الان **هر دو حالت را پشتیبانی می‌کنند**:

1. اول با `ModelDownloader.existsInAssets(context, path)` چک می‌کنند آیا آن مدل مشخص داخل
   `app/src/main/assets/` بسته‌بندی شده یا نه.
2. اگر بله: مستقیم با همان AssetManager بارگذاری می‌شود (`newFromAsset`، صفر تماس شبکه‌ای، صفر کپی
   به filesDir) — به‌جز مدل‌های `ToneColorConverter` (شبیه‌سازی صدا) که چون از ONNX Runtime خام
   استفاده می‌کنند و فقط مسیر فایل‌سیستم واقعی می‌پذیرند (نه AssetManager)، یک‌بار از assets به همان
   مسیر مقصدی که دانلود هم استفاده می‌کرد کپی می‌شوند، سپس مثل حالت «از قبل دانلود‌شده» رفتار می‌شود.
3. اگر نه (یعنی بیلد لاغر/on-demand عادی): دقیقاً همان مسیر دانلود قبلی (بخش ۱۰/۱۱) اجرا می‌شود.

**برای ساخت این نسخه‌ی یک‌تکه محلی** (کپی از `models/` به `app/src/main/assets/`، دقیقاً نام‌گذاری
مطابق چیزی که کد انتظار دارد):

```bash
for v in amir ganji ganji_adabi gyro reza_ibrahim; do
  mkdir -p "app/src/main/assets/$v"
  cp "models/$v/fa_IR-$v-medium.onnx" "app/src/main/assets/$v/"
  cp "models/$v/tokens.txt" "app/src/main/assets/$v/"
done
mkdir -p app/src/main/assets/voice_clone
cp models/voice_clone/tone_clone_model.onnx models/voice_clone/tone_color_extract_model.onnx app/src/main/assets/voice_clone/
mkdir -p app/src/main/assets/stt
cp models/stt/whisper-turbo-encoder.int8.onnx models/stt/whisper-turbo-decoder.int8.onnx models/stt/tokens.txt app/src/main/assets/stt/
./gradlew assembleDebug
```

نتیجه: APK حدود **۱.۴۵ گیگابایت** (همه‌چیز به‌جز مدل چت‌بات Gemma که هنوز قابلیتش ساخته نشده — بخش
۱۳)، کاملاً آفلاین از لحظه‌ی نصب، بدون نیاز به اینترنت حتی برای اولین استفاده.

**نکته‌ی حیاتی: این کپی‌ها هرگز commit نمی‌شوند** (`.gitignore` صریحاً `app/src/main/assets/{ganji,
gyro,amir,ganji_adabi,reza_ibrahim,voice_clone,stt}/` را نادیده می‌گیرد) — چون همان فایل‌ها از قبل در
`models/` این ریپو با Git LFS نگه‌داری می‌شوند؛ کپی‌کردن دوباره‌شان به assets فقط فضای LFS را
بی‌دلیل دوبرابر می‌کند (ریپو از قبل به‌خاطر سقف رایگان LFS گیت‌هاب نگران‌کننده است — بخش ۱۳). نسخه‌ی
پیش‌فرضِ commit‌شده در ریپو همیشه لاغر/on-demand می‌ماند؛ این بیلد یک‌تکه فقط محلی و طبق درخواست
مشخص کاربر است.

## ۱۳. مدل Gemma 3n (برای چت‌بات آینده) — دانلود‌شده، هنوز ادغام/commit نشده

کاربر خواست چت‌بات LLM کاملاً آفلاین با Gemma 3n E2B. تحقیق (نه پیاده‌سازی) نشان داد:

- فایل موبایل‌آماده: `google/gemma-3n-E2B-it-litert-preview` روی HuggingFace →
  `gemma-3n-E2B-it-int4.task` (فرمت MediaPipe LLM Inference API)، ۳٬۱۳۶٬۲۲۶٬۷۱۱ بایت (~۲.۹۲ گیگابایت).
  با توکن HuggingFace کاربر (که مجوز Gemma را پذیرفته بود) دانلود و در `models/gemma/` این ماشین
  قرار گرفت (تأیید اندازه‌ی بایت‌به‌بایت).
- **این فایل روی HuggingFace/Kaggle قفل است (gated)** — دانلود بدون لاگین+پذیرش مجوز ممکن نیست؛
  یعنی این مدل را (برخلاف مدل‌های TTS/STT پروژه) نمی‌شود مستقیماً از منبع اصلی به‌صورت anonymous در
  اپ دانلود کرد. راه‌حل: چون مجوز Gemma بازتوزیع را اجازه می‌دهد (بخش‌های ۳.۱/۳.۲ در
  ai.google.dev/gemma/terms)، باید مثل بقیه‌ی مدل‌ها در همین ریپو بازتوزیع شود تا کاربر نهایی بدون
  لاگین بتواند دانلودش کند.
- کتابخانه‌ی ادغام رسمی: `com.google.mediapipe:tasks-genai` (نسخه‌ی پایدار ۰.۱۰.۲۴)، minSdk ۲۴
  (سازگار)، native lib جداگانه (`libllm_inference_engine_jni.so`، بدون تداخل نام با
  `libonnxruntime.so`).
- **پشتیبانی فارسی تأیید نشده** — گوگل فقط «بیش از ۱۴۰ زبان» گفته بدون فهرست دقیق.
- **سرعت واقعی نامشخص روی گوشی‌های میان‌رده** — تنها بنچمارک منتشرشده مربوط به گوشی پرچمدار
  (Samsung S25 Ultra) است: ~۱۷.۶ توکن/ثانیه روی CPU — قابل‌استفاده ولی نه فوری، حتی روی پرچمدار.

**هنوز انجام‌نشده:** commit/push فایل مدل (عمداً، طبق `.gitignore` — بخش ۱۲.۵ را ببین)، افزودن به
`ModelDownloader.kt`، ساخت صفحه‌ی چت‌بات، و تست واقعی کیفیت فارسی/سرعت. این فایل فقط محلی روی این
ماشین cache شده تا وقتی قابلیت چت‌بات واقعاً ساخته شود.

## ۱۴. ایده‌های توسعه‌ی آینده (اختیاری)

بیلد release امضاشده، کنترل سرعت گفتار (`speed` پارامتر `generate()` از قبل پاس داده می‌شود ولی UI
برایش کنترلی ندارد)، پشتیبانی چندخط طولانی با پیشرفت تدریجی (streaming synthesis)، تست خودکار UI
(Espresso)، تست واقعی صفحه‌ی شبیه‌سازی صدا و دانلود مدل‌ها روی دستگاه فیزیکی، بهینه‌سازی FFT در
`ToneColorConverter`، امکان حذف دستیِ یک صدای دانلودشده از UI (برای آزادکردن فضا)، دانلود پس‌زمینه/
پیش‌دانلود اختیاری بقیه‌ی صداها (نه فقط گنجی) هنگام اتصال Wi-Fi، صفحه‌ی مدیریت محدودیت طول ضبط برای
تبدیل گفتار به نوشتار، امکان انتخاب اندازه‌ی مدل Whisper (tiny/base/small) در تنظیمات برای مصالحه‌ی
دقت/حجم توسط خود کاربر.
