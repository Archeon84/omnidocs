# ---- Project-specific ProGuard/R8 rules ----
# These ensure that reflection, serialization, and JNI continue to work
# after obfuscation and shrinking.

# ---- AI / LLM / Embedding (JNI) ----
-keep class com.omnidocs.app.ai.LlamaCppService { native <methods>; }
-keep class com.omnidocs.app.ai.LlamaCppService$Companion { *; }
-keep class com.omnidocs.app.ai.EmbeddingEngine { native <methods>; }
-keep class com.omnidocs.app.ai.EmbeddingEngine$Companion { *; }

# ---- Sherpa-onnx / STT ----
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ---- PDFBox ----
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# ---- Flexmark (Markdown parser) ----
-keep class com.vladsch.flexmark.** { *; }
-dontwarn com.vladsch.flexmark.**

# ---- Room (entities + DAOs) ----
-keep class com.omnidocs.app.data.local.entity.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ---- Hilt / Dagger ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$ActivityContextWrapper { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }

# ---- Kotlin Coroutines ----
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ---- Kotlin Serialization / Reflection ----
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }

# ---- SQLCipher ----
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# ---- OkHttp (ships its own consumer R8 rules; keeps would pin ~1MB unshrunk) ----
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- ML Kit ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---- Google APIs (Drive, Sign-In, Auth) ----
-keep class com.google.android.gms.** { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**

# ---- CameraX ----
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- iTextPDF ----
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# ---- Desktop-JVM classes referenced by PDFBox/POI transitive code ----
# (never loaded on Android; R8 missing_rules.txt confirms the full set)
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.slf4j.impl.**

# ---- Apache POI + transitive deps (log4j, bnd, findbugs) ----
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-dontwarn aQute.bnd.**
-dontwarn edu.umd.cs.findbugs.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn com.microsoft.schemas.**

# ---- Jsoup ----
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---- Coil (R8 full-mode compatible out of the box, no keeps needed) ----
-dontwarn coil.**

# ---- WebView / JavaScript Bridge ----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- DataStore ----
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---- Compose / Material3 (libraries ship their own consumer R8 rules) ----
-dontwarn androidx.compose.**

# ---- Keep enum members (used in when() exhaustiveness) ----
-keepclassmembers enum * { *; }

# ---- Keep Parcelable implementations ----
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ---- Keep Serializable classes ----
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---- Keep custom Application class ----
-keep class * extends android.app.Application { *; }

# ---- WorkManager (workers are instantiated by class name via reflection) ----
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# ---- OmniDocs Evidence-First Domain & Agent Tool Models ----
-keep class com.omnidocs.app.study.** { *; }
-keep class com.omnidocs.app.calendar.** { *; }
-keep class com.omnidocs.app.email.** { *; }
-keep class com.omnidocs.app.sync.** { *; }
-keep class com.omnidocs.app.vocabulary.** { *; }
-keep class com.omnidocs.app.agent.AgentTool** { *; }
-keep interface com.omnidocs.app.agent.AgentTool { *; }

