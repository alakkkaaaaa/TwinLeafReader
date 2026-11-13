# Keep JavaScript bridge methods if one is added later.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
