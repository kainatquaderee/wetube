# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# The browser engine ships snakeyaml, and its JavaBean support reaches for the
# `java.beans` introspection API, which Android does not have. Only
# DebugConfig.fromFile() takes that path, and it is the engine's development
# hook for loading a YAML config file: the app never calls it, so the classes are
# never needed at runtime. Without these rules R8 refuses to minify the release.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# GeckoView drives its Java side from native code (JNI) and instantiates some of
# it by reflection. Without these the release build dies in Gecko's launcher
# thread at address 0x0 before the first page loads, while the same code runs
# unminified.
-keep class org.mozilla.geckoview.** { *; }
-keepclassmembers class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keepclassmembers class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.**
