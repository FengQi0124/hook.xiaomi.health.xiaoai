# LSPosed / Xposed 模块不需要混淆（类名会被 Hook 逻辑与 xposed_init 引用）
-keep class com.fengqi.xiaoai.** { *; }
-keepnames class com.fengqi.xiaoai.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.fengqi.xiaoai.**$$serializer { *; }
-keepclassmembers class com.fengqi.xiaoai.** {
    *** Companion;
}
-keepclassmembers class com.fengqi.xiaoai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
