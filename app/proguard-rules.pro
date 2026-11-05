# Keep JavaScript interface methods if you add any later
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
