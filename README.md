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
su -c setprop debug.ioslock.holddepth 0     # 0 = jangan tahan jam depth (default 1: jam tetap besar di state 1-3)
su -c setprop debug.ioslock.holdgear 0      # 0 = jangan tahan gear jam saat notifikasi masuk (default 1)
su -c setprop debug.ioslock.wpexp 0         # 0 = jangan kecilkan jam depth di tahap 2 (default 1)
su -c setprop debug.ioslock.pilescale 1     # skala kartu notifikasi terbaru saat tertutup
su -c setprop debug.ioslock.pilestep 0.06   # selisih skala tiap kartu lebih lama (tumpukan gaya iOS)
su -c setprop debug.ioslock.peekdp 14       # jarak intip antar kartu di tumpukan
su -c setprop debug.ioslock.pilefrac 1      # garis DASAR tumpukan (1 = dasar layar: kartu naik dari bawah)
su -c setprop debug.ioslock.keepbig 1       # (opsional) paksa gear LARGE; bisa menghilangkan jam depth
su -c setprop debug.ioslock.clockshrink 1   # kecilkan View jam System UI saat tahap 2 (bukan jam depth)
su -c setprop debug.ioslock.countfrac 0.846 # garis acuan: dasar kartu notifikasi paling bawah
su -c setprop debug.ioslock.powerreset 0     # sembunyikan tombol "Restart UI" di power menu

Di wallpaper lockscreen `com.transsion.livewallpaper.custompictorial` jam **digambar oleh wallpaper**
(bukan View System UI): `CustomPictorial.setClockShape(alpha, scale)` dengan nilai diam 1.0/0.95,
dan ukuran/gaya jam mengikuti gear yang dikirim System UI.

### Aturan jam (v0.18)
- state 1 (tanpa notifikasi), state 2 (notifikasi masuk, belum di-swipe), state 3 (stack terbuka):
  jam tetap **besar**. Caranya: nilai diam alpha/scale direkam hanya saat tidak ada notifikasi,
lalu perintah System UI yang mengecilkannya ditulis ulang (`holddepth`), gear state 1 dipakai lagi
  saat notifikasi masuk (`holdgear`), dan bentuk diam dikirim sekali saat notifikasi baru datang.
- state 4 (swipe sekali lagi dari state 3): tahanan dilepas. Skala jam dikalikan `wpmin` (`wpexp`),
  perintah System UI juga diskalakan turun supaya tidak menimpa lagi, dan gear yang tadi diminta
  system (diblokir di state 1-3) dimintakan ulang.
- Kalau jam masih belum kecil di state 4, coba `setprop debug.ioslock.clockshrink 1` (mengecilkan
  View jam System UI) lalu kirim log baris `WPCMD` dan `wpsend`.

### Aturan tumpukan (v0.18)
- Posisi asli kartu dihitung dari `top` tata letak + translasi, jadi tumpukan selalu mendarat
  di tempat yang sama (dulu kartu "muncul dari tengah" karena `top` tidak dihitung).
- Kartu TERBARU menempel di garis `pilefrac` (dasar layar), kartu lama mengintip di atasnya sambil
  mengecil; saat di-swipe, kartu lama bergerak naik lebih dulu (gaya iOS).
- Saat terbuka (state 3) puncak stack berhenti di `restfrac` lalu kartu mengalir ke bawah, jadi
  3 kartu atau lebih **tidak** lagi mendorong stack turun dari garis acuan.

## Eksperimen tanpa rebuild (root)
su -c setprop debug.ioslock.level N      # paksa arg setClockLevel
su -c setprop debug.ioslock.push 1       # jam terdorong mengikuti swipe
su -c setprop debug.ioslock.pushdp 120
su -c setprop debug.ioslock.minscale 0.5
Matikan: setprop debug.ioslock.push 0 / level ""
