# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# Protobuf Lite: Keep all members of generated message classes to allow reflection-based serialization
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}

# MediaPipe: Keep the public Task API and options used in the app
-keep class com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier { *; }
-keep class com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier$AudioClassifierOptions { *; }
-keep class com.google.mediapipe.tasks.core.BaseOptions { *; }
-keep class com.google.mediapipe.tasks.components.containers.AudioData { *; }

# MediaPipe Framework and Core: Many classes are accessed via JNI
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.tasks.core.** { *; }
-keep interface com.google.mediapipe.framework.** { *; }
-keep interface com.google.mediapipe.tasks.core.** { *; }

# MediaPipe internal logging (targeted)
-keep class com.google.mediapipe.tasks.core.logging.TasksStatsProtoLogger {
    <fields>;
    <methods>;
}
-dontwarn com.google.mediapipe.tasks.core.logging.**
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.auto.value.extension.memoized.Memoized

# Flogger: Specific rules to prevent stack-walking failures in MediaPipe
-keep class com.google.common.flogger.FluentLogger { *; }
-keep class com.google.common.flogger.backend.system.StackBasedCallerFinder { *; }
-keep class com.google.common.flogger.backend.Platform { *; }
-dontwarn com.google.common.flogger.backend.system.StackBasedCallerFinder

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile