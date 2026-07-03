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

> **نکته‌ی مهم (رفع‌شده):** سه صدای امیر/گنجی‌ادبی/رضا ابراهیم به‌خاطر یک باگ در همین اسکریپت روی
> ویندوز (`tokens.txt` با پایان خط CRLF نوشته می‌شد؛ پارسر C++ سمت sherpa-onnx با `\r` باقی‌مانده
> در انتهای هر شناسه‌ی عددی کرش می‌کرد) هنگام انتخاب از منوی گوینده بلافاصله اپ را کرش می‌کردند.
> `convert_piper.py` اکنون با `newline="\n"` می‌نویسد و هر ۵ `tokens.txt` بازتولید شده‌اند.

## ساختار پروژه

```
app/
  src/main/java/.../MainActivity.kt   ← تمام منطق UI + موتور TTS در یک اکتیویتی
  src/main/java/.../voiceclone/       ← صفحه‌ی «شبیه‌سازی صدا» (voice cloning، آزمایشی)
  src/main/assets/                    ← مدل‌های صوتی + espeak-ng-data + مدل‌های تبدیل تن صدا
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

## شبیه‌سازی صدا (Voice Cloning) — آزمایشی

از صفحه‌ی اصلی، دکمه‌ی «شبیه‌سازی صدا (آزمایشی)» یک صفحه‌ی جدید باز می‌کند که در آن می‌توانید:

1. یک نمونه صدا ضبط کنید (میکروفون، حداقل ۲ ثانیه صدای واضح) یا فایل صوتی موجود را انتخاب کنید.
2. متن فارسی دلخواه را وارد کنید.
3. دکمه‌ی «شبیه‌سازی و تولید صدا» را بزنید تا گفتاری با محتوای فارسیِ درست ولی با تُن/طنین صدای نمونه تولید شود.

**معماری:** این قابلیت صدای Piper (صدای «گنجی») را برای تولید محتوای گفتاری صحیح فارسی استفاده می‌کند،
سپس با مدل ONNX «مبدل رنگ صدا»ی [OpenVoice](https://github.com/myshell-ai/OpenVoice) (نسخه‌ی ONNX
جامعه: [`seasonstudio/openvoice_tone_clone_onnx`](https://huggingface.co/seasonstudio/openvoice_tone_clone_onnx)
روی HuggingFace)، تُن صدای نمونه‌ی کاربر را جایگزین تُن صدای خروجی Piper می‌کند. کاملاً آفلاین،
بدون هیچ تماس شبکه‌ای در زمان اجرا. جزئیات کامل معماری/تحقیق در [`CLAUDE.md`](CLAUDE.md) بخش «شبیه‌سازی صدا».

**محدودیت‌های شناخته‌شده‌ی این بخش:**
- کیفیت شبیه‌سازی best-effort است؛ یک مدل سبک‌وزنِ تبدیل تن صدا است، نه کلون‌کننده‌ی کامل صدا.
- تست واقعی روی دستگاه انجام نشده (این ماشین امولاتور/دستگاه اندروید ندارد)؛ صحت با بررسی دقیق کد
  و یک اثبات مفهوم کامل در پایتون (STFT + هر دو مدل ONNX، اجرای واقعی) تأیید شده.
- STFT به‌صورت دستی در Kotlin با DFT/FFT ساده پیاده‌سازی شده (نه کتابخانه‌ی بهینه)؛ روی متن‌های
  طولانی ممکن است چند ثانیه طول بکشد.
- حجم APK به‌خاطر افزودن دو مدل ONNX جدید (~۱۳۱ مگابایت) و کتابخانه‌ی رسمی onnxruntime-android
  افزایش یافته (نسخه‌ی debug فعلی ~۵۰۴ مگابایت).

## محدودیت‌های شناخته‌شده

- حجم APK نسبتاً بالاست (~۵۰۴ مگابایت) چون هر ۵ مدل صوتی Piper + espeak-ng-data + دو مدل تبدیل تن صدا +
  کتابخانه‌های onnxruntime به‌صورت آفلاین بسته‌بندی شده‌اند. (ایده‌ی دانلود مدل‌ها هنگام نیاز به‌جای
  بسته‌بندی در APK بررسی شد ولی در این نشست پیاده‌سازی نشد — نیاز به هاست‌کردن فایل‌های تبدیل‌شده
  دارد که دسترسی `gh`/توکن گیت‌هاب در این محیط موجود نبود؛ به بخش «کارهای آینده» در CLAUDE.md مراجعه کنید.)
- این پروژه روی یک ماشین بدون شبیه‌ساز/دستگاه فیزیکی اندروید ساخته و build شده؛ صحت کد با خواندن دقیق و موفقیت `gradlew assembleDebug` تأیید شده، اما تست دستی پخش صدا روی دستگاه واقعی انجام نشده است.
