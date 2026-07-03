# Keep sherpa-onnx JNI-facing classes and their members since native code
# looks them up by name/signature via JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
