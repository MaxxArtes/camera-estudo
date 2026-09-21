# Compose, ML Kit e TFLite trazem as próprias regras (mesmo arranjo da câmera).
# ONNX Runtime chama classes/métodos Java por nome a partir do JNI.
-keep class ai.onnxruntime.** { *; }
