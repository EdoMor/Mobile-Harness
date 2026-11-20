# Mobile Harness alpha: release shrinking is intentionally disabled.

# Started by name through `app_process` over Wireless ADB, never referenced from app code.
-keep class com.jarves.mh.adb.ScreenPowerMain { public static void main(java.lang.String[]); }
