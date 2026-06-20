# Keep ONNX Runtime JNI bindings.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Room generated code is kept by the Room ProGuard rules shipped with the library.
# Hilt/Dagger generated code is kept by their consumer rules.
