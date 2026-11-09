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

## Tuning gesture tanpa rebuild (root)
su -c setprop debug.ioslock.restfrac 0.55   # posisi diam stack tahap 1 (0..1 tinggi layar)
su -c setprop debug.ioslock.endfrac 0.33    # posisi stack di akhir tahap 2
su -c setprop debug.ioslock.wpmin 0.5       # skala jam depth akhir tahap 2 (1 = tidak mengecil)
su -c setprop debug.ioslock.wpalpha 1       # alpha jam depth akhir tahap 2 (0 = hilang)
su -c setprop debug.ioslock.keepbig 0       # 0 = jangan paksa gear LARGE di lockscreen
su -c setprop debug.ioslock.gearswipe 1     # 1 = paksa gear SMALL di tahap 2
su -c setprop debug.ioslock.labels 0        # sembunyikan "Notification Center" / "N Notifications"
su -c setprop debug.ioslock.bubble 0        # matikan bubble frosted di belakang "N Notifications"
su -c setprop debug.ioslock.liveblur 0      # bubble pakai potongan wallpaper statis, bukan blur live
su -c setprop debug.ioslock.holddepth 1     # (opsional) tahan alpha/scale jam depth milik sistem
su -c setprop debug.ioslock.wpexp 1         # (opsional) kecilkan jam depth sendiri saat tahap 2
su -c setprop debug.ioslock.keepbig 1       # (opsional) paksa gear LARGE; bisa menghilangkan jam depth
su -c setprop debug.ioslock.clockshrink 1   # kecilkan View jam System UI saat tahap 2 (bukan jam depth)
su -c setprop debug.ioslock.countfrac 0.846 # garis acuan: dasar kartu notifikasi paling bawah
su -c setprop debug.ioslock.powerreset 0     # sembunyikan tombol "Restart UI" di power menu

Di wallpaper lockscreen `com.transsion.livewallpaper.custompictorial` jam **digambar oleh wallpaper**
(bukan View System UI): `CustomPictorial.setClockShape(alpha, scale)` dengan nilai diam 1.0/0.95,
dan ukuran/gaya jam mengikuti gear yang dikirim System UI. Karena itu v0.15 berhenti memaksa gear
(default `keepbig`/`holddepth`/`wpexp` MATI) supaya jam depth kembali seperti bawaan sistem.

## Eksperimen tanpa rebuild (root)
su -c setprop debug.ioslock.level N      # paksa arg setClockLevel
su -c setprop debug.ioslock.push 1       # jam terdorong mengikuti swipe
su -c setprop debug.ioslock.pushdp 120
su -c setprop debug.ioslock.minscale 0.5
Matikan: setprop debug.ioslock.push 0 / level ""
