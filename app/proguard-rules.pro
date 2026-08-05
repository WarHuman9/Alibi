# ProGuard/R8 rules for Alibi app

# --- Attribute Preservation ---
# Essential for reflection, serialization, and annotations
-keepattributes AnnotationDefault, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations, Signature, Exceptions, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# --- Kotlinx Serialization ---
# Keep all classes and members annotated with @Serializable
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    *** serializer(...);
}
# Keep the generated serializer classes to prevent 'Serializer not found' errors
-keep class **$$serializer { *; }
-keepclassmembers class ** {
    *** write$Self(...);
}

# --- Jetpack Navigation 3 & Adaptive ---
# Navigation 3 relies on serialization of NavKey implementations
-keep class * implements androidx.navigation3.runtime.NavKey { *; }
-keep interface androidx.navigation3.runtime.NavKey { *; }
# Keep internal adaptive logic for multi-pane layouts
-keep class androidx.compose.material3.adaptive.** { *; }

# --- Telecom Framework ---
# Ensure that subclasses of Telecom services are not stripped or renamed
-keep class * extends android.telecom.ConnectionService { *; }
-keep class * extends android.telecom.InCallService { *; }
-keep class * extends android.telecom.Connection { *; }
# Keep the entire telecom package for reflection-safe service interactions
-keep class com.example.alibi.telecom.** { *; }

# --- Kotlin Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}

# --- General Project Specifics ---
# Ensure all route data classes are kept for navigation stability
-keep class com.example.alibi.ui.**Route { *; }
-keep class com.example.alibi.ui.MainTabsRoute { *; }

# --- Android Standard ---
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
