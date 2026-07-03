# تبدیل متن فارسی به گفتار (Persian Piper TTS)

اپلیکیشن اندروید بومی (Kotlin) که متن فارسی را به‌صورت کاملاً **آفلاین** با موتور
[Piper](https://github.com/rhasspy/piper) (از طریق [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx))
به گفتار تبدیل می‌کند.

## نصب APK آماده روی گوشی

1. فایل [`dist/PersianTTS-debug.apk`](dist/PersianTTS-debug.apk) را روی گوشی اندرویدی (نسخه ۷ / API 24 به بالا) کپی کنید.
2. در تنظیمات گوشی، نصب از منابع ناشناس (Install unknown apps) را برای مرورگر/فایل‌منیجری که با آن فایل را باز می‌کنید فعال کنید.
3. روی فایل apk بزنید و نصب کنید.
4. برنامه را باز کنید — نیازی به اینترنت نیست.

> این یک بیلد **debug** است (بدون امضای release). برای انتشار در Google Play باید یک بیلد release امضاشده بسازید.

## نحوه‌ی استفاده

1. گوینده را از منوی کشویی انتخاب کنید (پیش‌فرض: «گنجی»).
2. متن فارسی را در کادر بزرگ وارد کنید.
3. دکمه‌ی «تبدیل به صوت» را بزنید.
4. با دکمه‌ی پخش، صدا را گوش دهید.
5. با «ذخیره» فایل WAV در پوشه‌ی Music گوشی ذخیره می‌شود؛ با «اشتراک‌گذاری» می‌توانید آن را برای اپ دیگری بفرستید.

## صداهای موجود (bundle‌شده، آفلاین)

هر ۵ صدای فارسی موجود در مخزن [`rhasspy/piper-voices`](https://huggingface.co/rhasspy/piper-voices/tree/main/fa/fa_IR) روی HuggingFace بسته‌بندی شده‌اند (همگی کیفیت medium — این تمام صداهای فارسی‌ای است که Piper در حال حاضر دارد):

| نام نمایشی | پوشه‌ی assets | منبع دانلود |
|---|---|---|
| گنجی (پیش‌فرض) | `app/src/main/assets/ganji/` | `.../fa/fa_IR/ganji/medium/fa_IR-ganji-medium.onnx` (+ `.onnx.json`) |
| گایرو | `app/src/main/assets/gyro/` | `.../fa/fa_IR/gyro/medium/fa_IR-gyro-medium.onnx` (+ `.onnx.json`) |
| امیر | `app/src/main/assets/amir/` | `.../fa/fa_IR/amir/medium/fa_IR-amir-medium.onnx` (+ `.onnx.json`) |
| گنجی (ادبی) | `app/src/main/assets/ganji_adabi/` | `.../fa/fa_IR/ganji_adabi/medium/fa_IR-ganji_adabi-medium.onnx` (+ `.onnx.json`) |
| رضا ابراهیم | `app/src/main/assets/reza_ibrahim/` | `.../fa/fa_IR/reza_ibrahim/medium/fa_IR-reza_ibrahim-medium.onnx` (+ `.onnx.json`) |

(`...` = `https://huggingface.co/rhasspy/piper-voices/resolve/main`)

> چون هر ۵ مدل + espeak-ng-data داخل APK بسته‌بندی شده‌اند، حجم فایل نصب حدود **۳۶۰ مگابایت** است.

داده‌ی `espeak-ng-data` (لازم برای phonemization، شامل پشتیبانی فارسی `fa`) در `app/src/main/assets/espeak-ng-data/` بسته‌بندی شده است.

هر مدل با اسکریپت [`convert_piper.py`](convert_piper.py) از فرمت اصلی Piper (`.onnx` + `.onnx.json`) به فرمتی که sherpa-onnx انتظار دارد تبدیل شده (تولید `tokens.txt` و تزریق متادیتا به داخل مدل ONNX).

## ساختار پروژه

```
app/
  src/main/java/.../MainActivity.kt   ← تمام منطق UI + موتور TTS در یک اکتیویتی
  src/main/assets/                    ← مدل‌های صوتی + espeak-ng-data (bundle آفلاین)
  src/main/res/                       ← لایوت و رشته‌های فارسی
  libs/sherpa-onnx.aar                ← کتابخانه‌ی sherpa-onnx (پیش‌ساخته)
convert_piper.py                      ← تبدیل مدل خام Piper به فرمت sherpa-onnx
gen_icon.js                           ← تولید آیکون لانچر (Node، بدون وابستگی)
dist/PersianTTS-debug.apk             ← خروجی آماده‌ی نصب
```

جزئیات معماری/تصمیم‌های فنی در [`CLAUDE.md`](CLAUDE.md) مستند شده.

## بیلد مجدد از سورس

نیازمندی‌ها: JDK 17، Android SDK (platform-tools + platform 34 + build-tools)، اینترنت (برای دانلود وابستگی‌های Gradle — مدل‌های صوتی و AAR از قبل در ریپو هستند).

```bash
# JAVA_HOME را به JDK 17 اشاره بده
export JAVA_HOME="/path/to/jdk-17"
# sdk.dir را در local.properties بگذار (این فایل commit نشده چون مسیر محلی است)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
# خروجی: app/build/outputs/apk/debug/app-debug.apk
```

اگر شبکه‌ی شما به `dl.google.com` دسترسی ندارد (برخی شبکه‌ها/پراکسی‌ها این را بلاک می‌کنند)، `settings.gradle`
از آینه‌ی `redirector.gvt1.com` به‌جای `google()` استفاده می‌کند — نیازی به تغییر دستی نیست مگر آن آینه هم در دسترس نباشد.

### افزودن صدای جدید

1. فایل `.onnx` و `.onnx.json` صدای موردنظر را از `rhasspy/piper-voices` دانلود کن.
2. `py convert_piper.py <model.onnx> <model.onnx.json>` را اجرا کن تا `tokens.txt` بسازد و متادیتا را داخل مدل تزریق کند.
3. پوشه‌ی صدا (شامل `.onnx` + `tokens.txt`) را زیر `app/src/main/assets/<voice-name>/` بگذار.
4. یک `VoiceOption` جدید در لیست `voices` داخل `MainActivity.kt` اضافه کن.

## محدودیت‌های شناخته‌شده

- حجم APK نسبتاً بالاست (~۳۶۰ مگابایت) چون هر ۵ مدل صوتی + espeak-ng-data + کتابخانه‌ی onnxruntime به‌صورت آفلاین بسته‌بندی شده‌اند.
- این پروژه روی یک ماشین بدون شبیه‌ساز/دستگاه فیزیکی اندروید ساخته و build شده؛ صحت کد با خواندن دقیق و موفقیت `gradlew assembleDebug` تأیید شده، اما تست دستی پخش صدا روی دستگاه واقعی انجام نشده است.
