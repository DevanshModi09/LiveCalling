# JavaScript calls these methods by name; retain them in consuming release APKs.
-keepclassmembers class com.vcd.calling.WebRtcCallView$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
