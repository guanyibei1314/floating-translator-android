# Keep Android manifest entry points while allowing R8 to remove debug metadata
# and shrink/rename implementation details in release artifacts.
-keep class com.example.floatingtranslator.MainActivity { *; }
-keep class com.example.floatingtranslator.CapturePermissionActivity { *; }
-keep class com.example.floatingtranslator.FloatingTranslatorService { *; }
-keep class com.example.floatingtranslator.ScreenCaptureService { *; }
-keep class com.example.floatingtranslator.AppContracts { *; }
-dontwarn org.jetbrains.annotations.**
