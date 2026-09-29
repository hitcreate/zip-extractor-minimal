# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep nothing extra - we are minimal
-keep class com.minimal.zipextractor.** { *; }

# Archive engines rely on internal structures; keep them from R8 to be safe.
-keep class net.lingala.zip4j.** { *; }
-keep class com.github.junrar.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.junrar.**
-dontwarn net.lingala.zip4j.**
-dontwarn org.tukaani.xz.**
