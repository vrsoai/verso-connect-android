# The page calls the bridge by name from JavaScript.
-keepclassmembers class ai.tryverso.connect.VersoConnectActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
