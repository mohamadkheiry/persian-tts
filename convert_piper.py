#!/usr/bin/env python3
"""
Convert a rhasspy/piper-voices ONNX model + its .onnx.json config into the
format sherpa-onnx expects for VITS/Piper models:
  - generate tokens.txt from phoneme_id_map
  - embed model_type/comment/language/voice/has_espeak/n_speakers/sample_rate
    metadata into the ONNX model itself (sherpa-onnx's native loader reads
    these from the model's metadata_props at load time).

Usage: py convert_piper.py <path-to-onnx> <path-to-onnx.json>
"""
import json
import sys
import onnx


def add_meta_data(filename, meta_data):
    model = onnx.load(filename)
    # جلوگیری از تزریق تکراری متادیتا اگر اسکریپت دوباره روی یک فایل از قبل تبدیل‌شده اجرا شود
    # (onnx.checker.check_model کلیدهای تکراری در metadata_props را نامعتبر می‌داند).
    existing_keys = {m.key for m in model.metadata_props}
    if existing_keys.intersection(meta_data.keys()):
        del model.metadata_props[:]
    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = str(value)
    onnx.save(model, filename)


def main():
    onnx_path = sys.argv[1]
    json_path = sys.argv[2]

    with open(json_path, "r", encoding="utf-8") as f:
        config = json.load(f)

    id_map = config["phoneme_id_map"]
    tokens_path = onnx_path.rsplit(".onnx", 1)[0] + ".tokens.txt"
    # sherpa-onnx expects a file literally named tokens.txt per model dir
    import os
    tokens_path = os.path.join(os.path.dirname(onnx_path), "tokens.txt")
    # newline="\n" صراحتاً لازم است: در ویندوز، حالت متنی پیش‌فرض "w" هر \n را به \r\n تبدیل
    # می‌کند. پارسر C++ سمت sherpa-onnx خط را با split روی فاصله می‌خواند و اگر \r باقی بماند،
    # به انتهای عدد شناسه (id) می‌چسبد و تبدیل عدد را با کرش خاتمه می‌دهد (این دقیقاً علت کرش
    # صداهای amir/ganji_adabi/reza_ibrahim بود که tokens.txtشان روی ویندوز و با CRLF تولید شده بود).
    with open(tokens_path, "w", encoding="utf-8", newline="\n") as f:
        for s, i in id_map.items():
            f.write("%s %d\n" % (s, i[0]))
    print("Generated", tokens_path)

    meta_data = {
        "model_type": "vits",
        "comment": "piper",
        "language": config.get("language", {}).get("name_english", "Persian"),
        "voice": config.get("espeak", {}).get("voice", "fa"),
        "has_espeak": 1,
        "n_speakers": config.get("num_speakers", 1),
        "sample_rate": config.get("audio", {}).get("sample_rate", 22050),
    }
    print("meta_data:", meta_data)
    add_meta_data(onnx_path, meta_data)
    print("Done embedding metadata into", onnx_path)


if __name__ == "__main__":
    main()
