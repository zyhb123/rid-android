# usb-serial 库保留
-keep class com.hoho.android.usbserial.** { *; }
# JavascriptInterface 注解的方法不能被混淆，否则 WebView 调不到
-keepclassmembers class com.zhangyuan.ridscanner.** {
    @android.webkit.JavascriptInterface <methods>;
}
