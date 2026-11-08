# iOS Lock XOS v0.2 (mode discovery)

Modul LSPosed (legacy API 82), scope: com.android.systemui.

## Build
1. Buka folder di Android Studio, sync (gradle-wrapper.properties sudah ada, Gradle 8.7).
2. Jika repo api.xposed.info gagal: taruh api-82.jar di app/libs, ganti dependency jadi compileOnly(files("libs/api-82.jar")).
3. Build APK, install, aktifkan di LSPosed, centang System UI, restart System UI.

## Ambil log
adb logcat -s IOSLock > ioslock.txt   (atau LSPosed > Logs > filter IOSLock > simpan)
Urutan: nyalakan layar -> kunci -> biarkan notifikasi masuk -> swipe ke atas pelan-pelan sampai batas F -> turun lagi.
Kirim file log utuh (blok "methods of", "fields of", "clock attached", "clockState", setClockLevel, changePanelCollapseAnim).

## Eksperimen tanpa rebuild (root)
su -c setprop debug.ioslock.level N      # paksa arg setClockLevel
su -c setprop debug.ioslock.push 1       # jam terdorong mengikuti swipe
su -c setprop debug.ioslock.pushdp 120
su -c setprop debug.ioslock.minscale 0.5
Matikan: setprop debug.ioslock.push 0 / level ""
