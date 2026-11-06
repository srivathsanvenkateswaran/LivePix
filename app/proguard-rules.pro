# Called from depth_decoder.cpp by name.
-keep class com.srivathsan.livepix.media.GrayImage { <init>(int, int, byte[]); }
-keepclasseswithmembernames class com.srivathsan.livepix.media.NativeHevc { native <methods>; }

# ONNX Runtime's native code looks these up by name.
-keep class ai.onnxruntime.** { *; }
