# Keep the accessibility service class intact so Android can bind it by class name.
-keep class com.reelblock.app.ReelBlockerService { *; }

# Keep Kotlin metadata (helps reflection-based frameworks and nicer stack traces).
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
