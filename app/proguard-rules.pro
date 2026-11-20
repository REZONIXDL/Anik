-keep class ai.onnxruntime.** { *; }
# LiteRT: کلاس‌های Interpreter/DataType با رفلکشن لود می‌شوند؛ R8 نباید حذفشان کند
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
