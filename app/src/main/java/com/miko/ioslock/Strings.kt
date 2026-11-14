package com.miko.ioslock

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** Informasi aplikasi (dipakai di halaman About dan laporan bug). */
object AppInfo {
    const val VERSION = "1.0"
    const val AUTHOR = "Miko Batagor"
    const val CHANNEL_NAME = "Batagor stuff"
    const val CHANNEL_URL = "https://t.me/jstzrvxstuff"
    const val DONATE_TEXT = "Buy me a coffee"
    const val DONATE_URL = "https://sociabuzz.com/mikobatagore/donate"
    const val REPORT_URL = "https://t.me/mkbtgr"
}

/** Pengaturan aplikasi. Nilai ini diterapkan ke System UI lewat property debug.ioslock.* (lihat RootUtil). */
object AppPrefs {
    const val PILL_DEFAULT = 0.845f
    const val PILL_MIN = 0.60f
    const val PILL_MAX = 0.92f

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("ioslock_app", Context.MODE_PRIVATE)

    fun powerBtn(c: Context): Boolean = sp(c).getBoolean("power_btn", false)
    fun setPowerBtn(c: Context, v: Boolean) { sp(c).edit().putBoolean("power_btn", v).apply() }

    fun pillFrac(c: Context): Float = sp(c).getFloat("pill_frac", PILL_DEFAULT).coerceIn(PILL_MIN, PILL_MAX)
    fun setPillFrac(c: Context, v: Float) { sp(c).edit().putFloat("pill_frac", v).apply() }

    fun lang(c: Context): String = sp(c).getString("lang", null) ?: Strings.systemDefault()
    fun setLang(c: Context, v: String) { sp(c).edit().putString("lang", v).apply() }
}

/** Teks tiga bahasa: urutan array = [English, Indonesia, Arab]. */
object Strings {
    const val EN = "en"
    const val ID = "id"
    const val AR = "ar"

    fun systemDefault(): String = when (Locale.getDefault().language) {
        "in", "id" -> ID
        "ar" -> AR
        else -> EN
    }

    fun get(lang: String, key: String): String {
        val a = data[key] ?: return key
        val i = when (lang) {
            ID -> 1
            AR -> 2
            else -> 0
        }
        return a[i]
    }

    private val data: Map<String, Array<String>> = mapOf(
        "subtitle" to arrayOf(
            "iOS-style lock screen for TranOS",
            "Layar kunci gaya iOS untuk TranOS",
            "شاشة قفل بأسلوب iOS لنظام TranOS"
        ),
        "root_checking" to arrayOf(
            "Checking root access…",
            "Memeriksa akses root…",
            "جارٍ التحقق من صلاحية الروت…"
        ),
        "root_ok" to arrayOf(
            "Root access granted",
            "Akses root diberikan",
            "صلاحية الروت متاحة"
        ),
        "root_no" to arrayOf(
            "No root access, settings can't be applied",
            "Tidak ada akses root, pengaturan tidak bisa diterapkan",
            "لا توجد صلاحية روت، لن تُطبَّق الإعدادات"
        ),
        "sec_power" to arrayOf("Power menu", "Menu power", "قائمة الطاقة"),
        "powerbtn_title" to arrayOf(
            "Reset UI button",
            "Tombol Reset UI",
            "زر إعادة تشغيل الواجهة"
        ),
        "powerbtn_desc" to arrayOf(
            "Show a Reset UI button below Restart in the power menu.",
            "Tampilkan tombol Reset UI di bawah Restart pada menu power.",
            "إظهار زر «Reset UI» أسفل زر إعادة التشغيل في قائمة الطاقة."
        ),
        "sec_pill" to arrayOf("Notification pill", "Pil notifikasi", "كبسولة الإشعارات"),
        "pill_title" to arrayOf("Pill position", "Posisi pil", "موضع الكبسولة"),
        "pill_desc" to arrayOf(
            "Moves the notification count pill and the point where the notification dock comes out.",
            "Menggeser pil jumlah notifikasi sekaligus titik awal munculnya dock notifikasi.",
            "يحرّك كبسولة عدد الإشعارات ونقطة ظهور حزمة الإشعارات معها."
        ),
        "pill_position" to arrayOf("Position", "Posisi", "الموضع"),
        "pill_default" to arrayOf("Reset to default", "Kembalikan default", "استعادة الافتراضي"),
        "sec_more" to arrayOf("More", "Lainnya", "المزيد"),
        "bug_title" to arrayOf("Bug report", "Laporkan bug", "الإبلاغ عن خطأ"),
        "bug_desc" to arrayOf(
            "Send a report to the developer via Telegram",
            "Kirim laporan ke pengembang lewat Telegram",
            "أرسل تقريرًا إلى المطوّر عبر تيليجرام"
        ),
        "about_title" to arrayOf("About", "Tentang", "حول التطبيق"),
        "about_desc" to arrayOf(
            "Version, author, support and links",
            "Versi, pembuat, dukungan dan tautan",
            "الإصدار والمطوّر والدعم والروابط"
        ),
        "settings" to arrayOf("Settings", "Pengaturan", "الإعدادات"),
        "language" to arrayOf("Language", "Bahasa", "اللغة"),
        "restart_cd" to arrayOf("Restart System UI", "Restart System UI", "إعادة تشغيل واجهة النظام"),
        "restart_title" to arrayOf("Restart System UI?", "Restart System UI?", "إعادة تشغيل واجهة النظام؟"),
        "restart_msg" to arrayOf(
            "The lock screen and status bar will reload for a moment.",
            "Layar kunci dan status bar akan dimuat ulang sebentar.",
            "ستُعاد تحميل شاشة القفل وشريط الحالة للحظات."
        ),
        "restart_yes" to arrayOf("Restart", "Restart", "إعادة التشغيل"),
        "cancel" to arrayOf("Cancel", "Batal", "إلغاء"),
        "restarting" to arrayOf(
            "Restarting System UI…",
            "Me-restart System UI…",
            "جارٍ إعادة تشغيل واجهة النظام…"
        ),
        "root_needed" to arrayOf(
            "Root access is needed for this",
            "Butuh akses root untuk ini",
            "هذه الميزة تحتاج إلى صلاحية الروت"
        ),
        "back" to arrayOf("Back", "Kembali", "رجوع"),

        // laporan bug
        "bug_header" to arrayOf("Bug report", "Laporkan bug", "الإبلاغ عن خطأ"),
        "f_device" to arrayOf("Device", "Perangkat", "الجهاز"),
        "f_tranos" to arrayOf("TranOS version", "Versi TranOS", "إصدار TranOS"),
        "f_problem" to arrayOf("Problem", "Masalah", "المشكلة"),
        "hint_problem" to arrayOf(
            "Describe the problem and the steps to reproduce it",
            "Jelaskan masalahnya dan langkah untuk memunculkannya",
            "صف المشكلة وخطوات تكرارها"
        ),
        "bug_note" to arrayOf(
            "The report opens in Telegram. Review it, then press send there.",
            "Laporan akan dibuka di Telegram. Periksa lalu tekan kirim di sana.",
            "سيُفتح التقرير في تيليجرام. راجعه ثم اضغط إرسال هناك."
        ),
        "send" to arrayOf("Send", "Kirim", "إرسال"),
        "log_attach" to arrayOf(
            "Attach log file",
            "Lampirkan file log",
            "إرفاق ملف السجل"
        ),
        "log_attach_desc" to arrayOf(
            "Module log from System UI. Contains app package names only, no message content.",
            "Log modul dari System UI. Hanya berisi nama paket aplikasi, tanpa isi pesan.",
            "سجل الوحدة من واجهة النظام. يحتوي على أسماء الحزم فقط دون محتوى الرسائل."
        ),
        "bug_note_log" to arrayOf(
            "Telegram opens a share list. Choose @mkbtgr and press send; the log file is attached.",
            "Telegram akan membuka daftar bagikan. Pilih @mkbtgr lalu tekan kirim; file log ikut terlampir.",
            "سيفتح تيليجرام قائمة المشاركة. اختر @mkbtgr ثم اضغط إرسال؛ ملف السجل مرفق."
        ),
        "log_collecting" to arrayOf(
            "Collecting log…",
            "Mengumpulkan log…",
            "جارٍ جمع السجل…"
        ),
        "log_no_root" to arrayOf(
            "No root access, the report is sent without the module log",
            "Tidak ada akses root, laporan dikirim tanpa log modul",
            "لا توجد صلاحية روت، سيُرسل التقرير دون سجل الوحدة"
        ),
        "pick_chat" to arrayOf(
            "Choose @mkbtgr in Telegram",
            "Pilih @mkbtgr di Telegram",
            "اختر @mkbtgr في تيليجرام"
        ),
        "problem_required" to arrayOf(
            "Please describe the problem first",
            "Isi masalahnya dulu",
            "يرجى وصف المشكلة أولًا"
        ),
        "copied" to arrayOf(
            "Report copied. Paste it in the chat if it doesn't appear.",
            "Laporan disalin. Tempel di chat jika belum muncul.",
            "تم نسخ التقرير. الصقه في المحادثة إن لم يظهر."
        ),
        "link_failed" to arrayOf(
            "Couldn't open the link",
            "Tidak bisa membuka tautan",
            "تعذّر فتح الرابط"
        ),

        // halaman about
        "version" to arrayOf("Version", "Versi", "الإصدار"),
        "author" to arrayOf("Author", "Pembuat", "المطوّر"),
        "channel" to arrayOf("Telegram channel", "Channel Telegram", "قناة تيليجرام"),
        "donate" to arrayOf("Donate", "Donasi", "التبرع"),
        "info_title" to arrayOf("Information", "Informasi", "المعلومات"),
        "support_title" to arrayOf("Support", "Dukungan", "الدعم"),
        "about_text" to arrayOf(
            "IOSLock brings an iOS-style notification experience to the lock screen of TranOS devices (Infinix, Tecno, itel). " +
                "Notifications gather in a neat stacked dock that opens with smooth swipe gestures, the clock keeps its depth effect, " +
                "and a glass pill shows the notification count together with a mini media player.",
            "IOSLock menghadirkan pengalaman notifikasi ala iOS di layar kunci perangkat TranOS (Infinix, Tecno, itel). " +
                "Notifikasi dikumpulkan dalam dock bertumpuk yang terbuka lewat gestur geser yang halus, jam tetap punya efek kedalaman, " +
                "dan pil kaca menampilkan jumlah notifikasi lengkap dengan mini media player.",
            "يقدّم IOSLock تجربة إشعارات بأسلوب iOS على شاشة قفل أجهزة TranOS (إنفينيكس وتكنو وإيتل). " +
                "تُجمَع الإشعارات في حزمة مرتّبة تُفتح بإيماءات سحب سلسة، وتحتفظ الساعة بتأثير العمق، " +
                "وتعرض كبسولة زجاجية عدد الإشعارات مع مشغّل وسائط مصغّر."
        ),
        "feat_title" to arrayOf("Highlights", "Fitur utama", "أبرز الميزات"),
        "feat1" to arrayOf(
            "Stacked notification dock with a smooth open animation",
            "Dock notifikasi bertumpuk dengan animasi buka yang halus",
            "حزمة إشعارات مرتّبة مع حركة فتح سلسة"
        ),
        "feat2" to arrayOf(
            "Scrollable list with a top fade and a side slider",
            "Daftar yang bisa digulir dengan fade atas dan slider samping",
            "قائمة قابلة للتمرير مع تلاشٍ علوي ومنزلق جانبي"
        ),
        "feat3" to arrayOf(
            "Glass pill with a mini media player",
            "Pil kaca dengan mini media player",
            "كبسولة زجاجية مع مشغّل وسائط مصغّر"
        ),
        "feat4" to arrayOf(
            "Adjustable pill position and an optional Reset UI button in the power menu",
            "Posisi pil bisa diatur dan tombol Reset UI opsional di menu power",
            "موضع كبسولة قابل للضبط وزر «Reset UI» اختياري في قائمة الطاقة"
        ),
        "req_title" to arrayOf("Requirements", "Persyaratan", "المتطلبات"),
        "req_text" to arrayOf(
            "Root access and LSPosed with the module enabled for System UI. After enabling it, restart System UI.",
            "Akses root dan LSPosed dengan modul aktif untuk System UI. Setelah diaktifkan, restart System UI.",
            "صلاحية الروت وLSPosed مع تفعيل الوحدة لواجهة النظام. بعد التفعيل أعد تشغيل واجهة النظام."
        )
    )
}
