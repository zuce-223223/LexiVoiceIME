-keep class com.lexivoice.ime.** { *; }
-keepclassmembers class * extends android.inputmethodservice.InputMethodService {
    *;
}
