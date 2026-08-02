# ---- Project-specific ProGuard/R8 rules ----
# These ensure that reflection, serialization, and JNI continue to work
# after obfuscation and shrinking.

# ---- AI / LLM (JNI) ----
-keep class com.omnidocs.app.ai.LlamaCppService { native <methods>; }
-keep class com.omnidocs.app.ai.LlamaCppService$Companion { *; }

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

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

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

# ---- Coil ----
-keep class coil.** { *; }
-dontwarn coil.**

# ---- WebView / JavaScript Bridge ----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- DataStore ----
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---- Compose / Material3 ----
-keep class androidx.compose.** { *; }
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
