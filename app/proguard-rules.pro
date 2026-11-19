# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to rules specified
# in /Users/.../build/tools/build-tools/.../proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep FFmpeg classes
-keep class com.github.pao11.libffmpeg.** { *; }
-keep class com.github.pao11.libffmpeg.exceptions.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}