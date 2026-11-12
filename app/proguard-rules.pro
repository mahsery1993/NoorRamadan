# Keep WebView JavaScript interfaces and Compose classes
-keepattributes *Annotation*,InnerClasses,Signature
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn androidx.compose.**
