# تبدیل متن فارسی به گفتار (Persian Piper TTS)

اپلیکیشن اندروید بومی (Kotlin) که متن فارسی را به‌صورت کاملاً **آفلاین** با موتور
[Piper](https://github.com/rhasspy/piper) (از طریق [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx))
به گفتار تبدیل می‌کند. همچنین شامل شبیه‌سازی صدا (آزمایشی) و تبدیل گفتار به نوشتار با Whisper است.

> **این ریپو فقط سورس‌کد و مستندات است — هیچ فایل مدل یا APKای در آن commit نشده.**
> قبل از build کردن، حتماً بخش «تهیه‌ی مدل‌ها» زیر را کامل بخوان و انجام بده.

## تهیه‌ی مدل‌ها (ضروری، قبل از هر build)

سه دسته مدل لازم است که هیچ‌کدام در این ریپو نیستند (حجم‌شان چند گیگابایت است و از سقف رایگان
Git LFS گیت‌هاب عبور می‌کند). همه را در پوشه‌ی `models/` (که در `.gitignore` است، فقط محلی می‌ماند)
آماده کن:

### ۱. پنج صدای Piper (هرکدام ~۶۳ مگابایت)

نیاز به پایتون + پکیج `onnx` دارد: `pip install onnx`

```bash
for v in amir ganji ganji_adabi gyro reza_ibrahim; do
  mkdir -p "models/$v"
  base="https://huggingface.co/rhasspy/piper-voices/resolve/main/fa/fa_IR/$v/medium"
  curl -sL -o "models/$v/fa_IR-$v-medium.onnx" "$base/fa_IR-$v-medium.onnx"
  curl -sL -o "models/$v/fa_IR-$v-medium.onnx.json" "$base/fa_IR-$v-medium.onnx.json"
  py convert_piper.py "models/$v/fa_IR-$v-medium.onnx" "models/$v/fa_IR-$v-medium.onnx.json"
done
```

`convert_piper.py` یک `tokens.txt` می‌سازد و متادیتای لازم برای sherpa-onnx را داخل خود فایل ONNX
تزریق می‌کند — بدون این مرحله، مدل خام Piper اصلاً بارگذاری نمی‌شود.

### ۲. دو مدل شبیه‌سازی صدا (~۱۳۱ مگابایت مجموع)

```bash
mkdir -p models/voice_clone
curl -sL -o models/voice_clone/tone_color_extract_model.onnx \
  https://huggingface.co/seasonstudio/openvoice_tone_clone_onnx/resolve/main/tone_color_extract_model.onnx
curl -sL -o models/voice_clone/tone_clone_model.onnx \
  https://huggingface.co/seasonstudio/openvoice_tone_clone_onnx/resolve/main/tone_clone_model.onnx
```

### ۳. مدل تشخیص گفتار Whisper large-v3-turbo (~۱ گیگابایت مجموع)

```bash
mkdir -p models/stt
base="https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main"
curl -sL -C - --retry 5 -o models/stt/whisper-turbo-encoder.int8.onnx "$base/turbo-encoder.int8.onnx"
curl -sL -C - --retry 5 -o models/stt/whisper-turbo-decoder.int8.onnx "$base/turbo-decoder.int8.onnx"
curl -sL -o models/stt/tokens.txt "$base/turbo-tokens.txt"
```

فایل‌های بزرگ را با `-C -` (ادامه‌ی دانلود ناقص) بگیر — این دو فایل روی اینترنت معمولی معمولاً حداقل
یک‌بار وسط دانلود قطع می‌شوند.

جزئیات کامل هر مدل (چرا این‌ها، معماری، منابع) در [`CLAUDE.md`](CLAUDE.md) بخش «تهیه‌ی مدل‌ها».

## بیلد مجدد از سورس

نیازمندی‌ها: JDK 17، Android SDK (platform-tools + platform 34 + build-tools)، اینترنت، و مدل‌های
بالا از قبل در `models/` آماده باشند.

```bash
export JAVA_HOME="/path/to/jdk-17"
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # این فایل commit نشده، خودت بساز

./gradlew assembleDebug
# خروجی: app/build/outputs/apk/debug/app-debug.apk
```

اگر شبکه‌ی شما به `dl.google.com` دسترسی ندارد (برخی شبکه‌ها/پراکسی‌ها این را بلاک می‌کنند)، `settings.gradle`
از آینه‌ی `redirector.gvt1.com` به‌جای `google()` استفاده می‌کند — نیازی به تغییر دستی نیست مگر آن آینه هم در دسترس نباشد.

با این دستور، اپ به‌صورت پیش‌فرض در حالت **on-demand-download** ساخته می‌شود: APK کوچک است (چند ده
مگابایت) اما چون مدل‌ها دیگر جایی میزبانی نمی‌شوند (این ریپو خودش دیگر مدل‌ها را ندارد)، دانلود واقعی
مدل‌ها در اپ **کار نمی‌کند** مگر خودت `models/` را جایی (fork خودت از این ریپو، یا هر CDN دیگر) میزبانی
کرده و ثابت‌های `ModelDownloader.kt` را به آن اشاره داده باشی. برای تست ساده روی گوشی خودت، بیلد
کاملاً بسته‌بندی‌شده‌ی زیر را ترجیح بده.

### بیلد کاملاً بسته‌بندی‌شده (یک APK خودکفا، پیشنهادی برای تست شخصی)

مدل‌ها را قبل از build به `assets` کپی کن — کد به‌طور خودکار تشخیص می‌دهد که مدل bundle شده و اصلاً
تلاشی برای دانلود نمی‌کند:

```bash
for v in amir ganji ganji_adabi gyro reza_ibrahim; do
  mkdir -p "app/src/main/assets/$v"
  cp "models/$v/fa_IR-$v-medium.onnx" "models/$v/tokens.txt" "app/src/main/assets/$v/"
done
mkdir -p app/src/main/assets/voice_clone app/src/main/assets/stt
cp models/voice_clone/*.onnx app/src/main/assets/voice_clone/
cp models/stt/*.onnx models/stt/tokens.txt app/src/main/assets/stt/
./gradlew assembleDebug   # خروجی حدود ۱.۴۵ گیگابایت
```

نتیجه: APK کاملاً آفلاین از لحظه‌ی نصب، بدون نیاز به اینترنت حتی در اولین اجرا. این کپی‌ها را commit
نکن (`.gitignore` جلویشان را می‌گیرد) — فقط برای build محلی هستند.

### افزودن صدای جدید

1. فایل `.onnx` و `.onnx.json` صدای موردنظر را از `rhasspy/piper-voices` دانلود کن.
2. `py convert_piper.py <model.onnx> <model.onnx.json>` را اجرا کن تا `tokens.txt` بسازد و متادیتا را داخل مدل تزریق کند.
3. پوشه‌ی صدا (شامل `.onnx` + `tokens.txt`) را زیر `models/<voice-name>/` بگذار.
4. یک `VoiceOption` جدید در لیست `voices` داخل `MainActivity.kt` اضافه کن.
5. برای بیلد بسته‌بندی‌شده، همین پوشه را به `app/src/main/assets/<voice-name>/` هم کپی کن (بخش بالا).

## نحوه‌ی استفاده

1. گوینده را از منوی کشویی انتخاب کنید (پیش‌فرض: «گنجی»).
2. متن فارسی را در کادر بزرگ وارد کنید.
3. دکمه‌ی «تبدیل به صوت» را بزنید.
4. با دکمه‌ی پخش، صدا را گوش دهید.
5. با «ذخیره» فایل WAV در پوشه‌ی Music گوشی ذخیره می‌شود؛ با «اشتراک‌گذاری» می‌توانید آن را برای اپ دیگری بفرستید.

اگر بیلد on-demand استفاده می‌کنید (و مدل‌ها جایی میزبانی شده‌اند)، اولین استفاده از هر صدا یک نوار
پیشرفت درصدی دانلود نشان می‌دهد؛ بعد از آن همان صدا کاملاً آفلاین کار می‌کند.

## صداهای موجود

هر ۵ صدای فارسی موجود در مخزن [`rhasspy/piper-voices`](https://huggingface.co/rhasspy/piper-voices/tree/main/fa/fa_IR)
روی HuggingFace (همگی کیفیت medium — تمام صداهای فارسی‌ای که Piper دارد) پشتیبانی می‌شوند: گنجی
(پیش‌فرض)، گایرو، امیر، گنجی (ادبی)، رضا ابراهیم.

داده‌ی `espeak-ng-data` (لازم برای phonemization، شامل پشتیبانی فارسی `fa`) همیشه در
`app/src/main/assets/espeak-ng-data/` بسته‌بندی است و نیازی به دانلود ندارد — بدون آن هیچ صدایی کار نمی‌کند.

هر مدل با اسکریپت [`convert_piper.py`](convert_piper.py) از فرمت اصلی Piper (`.onnx` + `.onnx.json`) به فرمتی که sherpa-onnx انتظار دارد تبدیل شده (تولید `tokens.txt` و تزریق متادیتا به داخل مدل ONNX).

> **نکته‌ی مهم:** سه صدای امیر/گنجی‌ادبی/رضا ابراهیم قبلاً به‌خاطر یک باگ در `convert_piper.py` روی
> ویندوز (`tokens.txt` با پایان خط CRLF نوشته می‌شد؛ پارسر C++ سمت sherpa-onnx با `\r` باقی‌مانده
> در انتهای هر شناسه‌ی عددی کرش می‌کرد) هنگام انتخاب از منوی گوینده بلافاصله اپ را کرش می‌کردند.
> `convert_piper.py` اکنون با `newline="\n"` می‌نویسد — اگر خودت این اسکریپت را تغییر دادی، حتماً
> `file models/*/tokens.txt` را چک کن که «UTF-8 text» باشد نه «with CRLF line terminators».

## ساختار پروژه

```
app/
  src/main/java/.../MainActivity.kt      ← تمام منطق UI + موتور TTS در یک اکتیویتی
  src/main/java/.../ModelDownloader.kt   ← دانلودکننده‌ی مشترک مدل‌ها هنگام نیاز (on-demand)
  src/main/java/.../voiceclone/          ← صفحه‌ی «شبیه‌سازی صدا» (voice cloning، آزمایشی)
  src/main/java/.../stt/                 ← صفحه‌ی «تبدیل گفتار به نوشتار» (Whisper)
  src/main/assets/                       ← فقط espeak-ng-data (مدل‌ها bundle نمی‌شوند مگر بیلد بسته‌بندی‌شده)
  src/main/res/                          ← لایوت و رشته‌های فارسی
  libs/sherpa-onnx.aar                   ← کتابخانه‌ی sherpa-onnx (پیش‌ساخته، در ریپو commit شده)
convert_piper.py                         ← تبدیل مدل خام Piper به فرمت sherpa-onnx
gen_icon.js                              ← تولید آیکون لانچر (Node، بدون وابستگی)
models/                                  ← محلی فقط — مدل‌های آماده‌شده (بخش «تهیه‌ی مدل‌ها»)، commit نمی‌شود
dist/                                    ← محلی فقط — خروجی APK بعد از build، commit نمی‌شود
```

جزئیات معماری/تصمیم‌های فنی در [`CLAUDE.md`](CLAUDE.md) مستند شده.

## شبیه‌سازی صدا (Voice Cloning) — آزمایشی

از صفحه‌ی اصلی، دکمه‌ی «شبیه‌سازی صدا (آزمایشی)» یک صفحه‌ی جدید باز می‌کند که در آن می‌توانید:

1. یک نمونه صدا ضبط کنید (میکروفون، حداقل ۲ ثانیه صدای واضح) یا فایل صوتی موجود را انتخاب کنید.
2. متن فارسی دلخواه را وارد کنید.
3. دکمه‌ی «شبیه‌سازی و تولید صدا» را بزنید تا گفتاری با محتوای فارسیِ درست ولی با تُن/طنین صدای نمونه تولید شود.

**معماری:** این قابلیت صدای Piper (صدای «گنجی») را برای تولید محتوای گفتاری صحیح فارسی استفاده می‌کند،
سپس با مدل ONNX «مبدل رنگ صدا»ی [OpenVoice](https://github.com/myshell-ai/OpenVoice) (نسخه‌ی ONNX
جامعه: [`seasonstudio/openvoice_tone_clone_onnx`](https://huggingface.co/seasonstudio/openvoice_tone_clone_onnx)
روی HuggingFace)، تُن صدای نمونه‌ی کاربر را جایگزین تُن صدای خروجی Piper می‌کند. کاملاً آفلاین،
بدون هیچ تماس شبکه‌ای در زمان اجرا (بعد از این‌که مدل‌ها موجود باشند). جزئیات کامل معماری/تحقیق در
[`CLAUDE.md`](CLAUDE.md) بخش «شبیه‌سازی صدا».

**محدودیت‌های شناخته‌شده‌ی این بخش:**
- کیفیت شبیه‌سازی best-effort است؛ یک مدل سبک‌وزنِ تبدیل تن صدا است، نه کلون‌کننده‌ی کامل صدا.
- تست واقعی روی دستگاه انجام نشده (این ماشین امولاتور/دستگاه اندروید ندارد)؛ صحت با بررسی دقیق کد
  و یک اثبات مفهوم کامل در پایتون (STFT + هر دو مدل ONNX، اجرای واقعی) تأیید شده.
- STFT به‌صورت دستی در Kotlin با DFT/FFT ساده پیاده‌سازی شده (نه کتابخانه‌ی بهینه)؛ روی متن‌های
  طولانی ممکن است چند ثانیه طول بکشد.
- کتابخانه‌ی onnxruntime-android بخشی از APK است (چون کد Kotlin به آن نیاز دارد، نه فقط مدل).

## تبدیل گفتار به نوشتار (Speech-to-Text)

از صفحه‌ی اصلی، دکمه‌ی «تبدیل گفتار به نوشتار» یک صفحه‌ی جدید باز می‌کند که در آن می‌توانید:

1. یک نمونه صدا ضبط کنید (میکروفون) یا فایل صوتی موجود را انتخاب کنید.
2. صبر کنید تا متن فارسیِ گفتار آن نمونه نمایش داده شود.
3. متن را با دکمه‌ی «کپی» در کلیپ‌بورد کپی یا با «اشتراک‌گذاری» برای اپ دیگری بفرستید.

**معماری:** این قابلیت از مدل [Whisper](https://github.com/openai/whisper) نسخه‌ی «large-v3-turbo»
(چندزبانه، decoder هرس‌شده‌ی large-v3 برای سرعت بالا، فشرده‌شده به int8) از طریق همان کتابخانه‌ی
sherpa-onnx موجود در پروژه استفاده می‌کند (بدون افزودن هیچ کتابخانه‌ی بومی جدید).

> **نکته درباره‌ی Vosk:** در بررسی اولیه فرض شده بود Vosk مدل فارسی ندارد؛ این فرض نادرست بود — Vosk
> مدل‌های رسمی فارسی دارد (`vosk-model-fa-0.42` و جدیدتر). دلیل واقعی انتخاب Whisper این است که
> **sherpa-onnx (تنها runtime این پروژه) اصلاً از فرمت مدل Vosk (بر پایه‌ی Kaldi) پشتیبانی نمی‌کند**،
> در حالی که پشتیبانی از Whisper به‌صورت native و کامل در همان کتابخانه وجود دارد. جزئیات کامل در
> بخش «تبدیل گفتار به نوشتار» [`CLAUDE.md`](CLAUDE.md).

**محدودیت‌های شناخته‌شده‌ی این بخش:**
- تست واقعی روی دستگاه انجام نشده (این ماشین امولاتور/دستگاه اندروید ندارد)؛ صحت فقط با build موفق
  و بازبینی دقیق کد/بایت‌کد `sherpa-onnx.aar` (برای تأیید واقعی وجود کلاس‌های `OfflineRecognizer` و
  پشتیبانی Whisper) تأیید شده.
- دانلود اول این قابلیت ~۱ گیگابایت است (`large-v3-turbo`) — عمدی، طبق درخواست کاربر برای بهترین
  دقت/سرعت با گوشیِ دارای رم زیاد؛ روی گوشی‌های ضعیف‌تر یا اینترنت کند ممکن است این دانلود اول کند باشد.
- روی گوشی‌های ضعیف، تبدیل ضبط‌های طولانی هنوز می‌تواند چند ثانیه طول بکشد (استنتاج با CPU)، هرچند
  `turbo` به‌مراتب سریع‌تر از `large-v3` خام است.
- Whisper همیشه ۱۶۰۰۰ هرتز مونو انتظار دارد؛ صدای ضبط‌شده (۲۲۰۵۰ هرتز) یا فایل انتخاب‌شده (هر نرخی)
  قبل از تشخیص با یک resample خطی ساده به ۱۶۰۰۰ هرتز تبدیل می‌شود.

## محدودیت‌های شناخته‌شده

- **این پروژه روی یک ماشین بدون شبیه‌ساز/دستگاه فیزیکی اندروید ساخته و build شده.** صحت منطق کد با
  خواندن دقیق کد، decompile کردن `sherpa-onnx.aar` (برای تأیید رفتار `newFromFile` در برابر
  `newFromAsset`) و تأیید مستقیم نمونه‌URLهای دانلود با `curl` انجام شده — نه با اجرای واقعی
  دانلود/پخش صدا روی دستگاه. قبل از انتشار رسمی حتماً روی حداقل یک دستگاه واقعی با اینترنت واقعی تست کنید.
- بیلد on-demand-download به‌صورت پیش‌فرض کار نمی‌کند چون این ریپو دیگر مدل‌ها را میزبانی نمی‌کند
  (بالا توضیح داده شد) — برای تست شخصی از بیلد کاملاً بسته‌بندی‌شده استفاده کنید.
- دانلود ناموفق (قطعی شبکه وسط دانلود، فایل نیمه‌کاره و…) با پیام فارسی روشن گزارش می‌شود و با
  انتخاب دوباره‌ی همان صدا/باز کردن دوباره‌ی صفحه قابل‌تلاش‌دوباره است؛ فایل‌های نیمه‌دانلودشده با
  پسوند موقت `.part` نوشته می‌شوند تا با یک فایل معتبر قبلی اشتباه گرفته نشوند.
