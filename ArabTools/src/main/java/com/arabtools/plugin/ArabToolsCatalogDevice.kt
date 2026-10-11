package com.arabtools.plugin

import com.arabtools.plugin.ArabToolsCatalog.Category
import com.arabtools.plugin.ArabToolsCatalog.Def
import com.arabtools.plugin.ArabToolsCatalog.Group
import com.arabtools.plugin.ArabToolsCatalog.Impl
import com.arabtools.plugin.ArabToolsCatalog.Kind

// ═══════════════════════ (٥) الجهاز — `category_device`
// يقابل شاشة CSM: DeviceLayoutSettingsFragment + DeviceLayoutSession + TvDevicePolicy

internal fun deviceCategory() = Category(
    "device", "الجهاز",
    listOf(
        Group(
            "تخطيط الجهاز",
            listOf(
                Def("tv_layout_enabled", "تخطيط تي في", "يُفعّل الواجهة المصمّمة للشاشات الكبيرة"),
                Def("tv_layout_redesign", "التخطيط الجديد", "نسخة محدّثة من تخطيط تي في"),
                Def("tv_default", "تي في هو الافتراضي", "يبدأ التطبيق بتخطيط تي في"),
                Def(
                    "screen_fit_mode", "ملاءمة الشاشة", "كيف يُقاس المحتوى على شاشة جهازك", Kind.LIST,
                    entries = listOf("تلقائي", "هاتف", "لوح", "تي في"),
                    values = listOf("auto", "phone", "tablet", "tv"),
                    default = "auto"
                ),
                Def("tv_larger_posters", "صور أكبر", "بطاقات أكبر تناسب المشاهدة من بعيد"),
                Def("tv_horizontal_scroll", "تمرير أفقي", "صفوف تمرّ أفقياً كما في الشاشات الكبيرة"),
                Def("one_hand", "الاستعمال بيد واحدة", "يحوّل الواجهة لتناسب يداً واحدة"),
                Def(
                    "orientation_lock", "قفل اتجاه الشاشة", "يمنع دوران الشاشة", Kind.LIST,
                    entries = listOf("بلا قفل", "عامودي", "أفقي", "حسب الجهاز"),
                    values = listOf("none", "portrait", "landscape", "sensor"),
                    default = "none"
                )
            )
        ),
        Group(
            "الريموت والتحكّم",
            listOf(
                Def("tv_focus_animation", "حركة التركيز", "تأثير عند التنقّل بالريموت"),
                Def("tv_keyboard_enabled", "لوحة مفاتيح تي في", "استعمال لوحة مفاتيح الشاشة"),
                Def("tv_optimized", "تحسين للريموت", "أزرار وأهداف تناسب الريموت"),
                Def("fire_tv", "وضع فاير تي في", "تحسينات خاصة بأجهزة أمازون"),
                Def("android_tv", "وضع أندرويد تي في", "تحسينات لأجهزة أندرويد تي في"),
                Def("dpad_navigation", "تنقّل بأزرار الاتجاهات", "تحكّم كامل بلا لمس"),
                Def("carousel_focus", "تركيز المعرض", "يُبرز المعرض العلوي عند التركيز")
            )
        ),
        Group(
            "الإرسال إلى شاشة أخرى",
            listOf(
                Def("remote_play_enabled", "التشغيل عن بُعد", "أرسل الفيديو إلى شاشة أخرى"),
                Def("tap_to_tv_discoverable", "إتاحة الجهاز للاكتشاف", "يجعل هاتفك مرئياً للأجهزة الأخرى"),
                Def("tap_to_tv_require_approval", "طلب موافقة قبل الإرسال", "يسألك قبل قبول أي جهاز"),
                Def("tap_to_tv_trusted_devices", "الأجهزة الموثوقة", "أجهزة سبق أن وافقت عليها", Kind.INFO),
                Def("screencast_enabled", "بثّ الشاشة", "أرسل شاشة هاتفك إلى تي في"),
                Def(
                    "screencast_quality", "جودة البثّ", "دقّة الإرسال", Kind.LIST,
                    entries = listOf("تلقائي", "عالية", "متوسطة", "منخفضة"),
                    values = listOf("auto", "high", "medium", "low"),
                    default = "auto"
                ),
                Def("screencast_show_overlay", "طبقة البثّ", "يعرض حالة البثّ على الشاشة")
            )
        ),
        Group(
            "معلومات الجهاز",
            listOf(
                Def("device_model", "طراز الجهاز", "اسم هاتفك كما يعرفه التطبيق", Kind.INFO),
                Def("android_version", "إصدار أندرويد", "نظام تشغيل هاتفك", Kind.INFO),
                Def("screen_resolution", "دقّة الشاشة", "أبعاد الشاشة بالبكسل", Kind.INFO),
                Def("screen_density", "كثافة الشاشة", "عدد النقاط في الإنش", Kind.INFO)
            )
        )
    )
)

// ═══════════════════════ (٦) ديسكورد — `category_discord`
// يقابل CSM: DiscordModitions + DiscordSettingsFragment + StandaloneRpcManager

internal fun discordCategory() = Category(
    "discord", "ديسكورد",
    listOf(
        Group(
            "الحساب والاتصال",
            listOf(
                Def("discord_rpc_enabled", "ربط ديسكورد", "يعرض ما تشاهده على حسابك في ديسكورد"),
                Def("discord_login", "تسجيل الدخول", "اربط حساب ديسكورد"),
                Def("discord_username", "اسم المستخدم", "اسمك في ديسكورد", Kind.INFO),
                Def("discord_application_id", "معرّف التطبيق", "معرّف ديسكورد المستعمل", Kind.TEXT, default = ""),
                Def("discord_token", "رمز الدخول", "رمز الحساب — لا تشاركه مع أحد", Kind.TEXT, default = ""),
                Def("discord_rpc_status", "حالة الاتصال", "هل الربط يعمل الآن؟", Kind.INFO)
            )
        ),
        Group(
            "ما يُعرض على ديسكورد",
            listOf(
                Def("discord_show_title", "إظهار اسم العمل", "يُظهر اسم ما تشاهده"),
                Def("discord_show_episode", "إظهار رقم الحلقة", "يُظهر الحلقة التي عليها"),
                Def("discord_show_provider", "إظهار المصدر", "يُظهر المصدر المستعمل"),
                Def("discord_show_poster", "إظهار الصورة", "يُظهر غلاف العمل"),
                Def("discord_show_progress", "إظهار التقدّم", "يُظهر كم مضى من الوقت"),
                Def("discord_show_time_elapsed", "إظهار الوقت المنقضي", "يعدّ الوقت منذ بدء المشاهدة"),
                Def("discord_show_battery", "إظهار البطارية", "يُظهر نسبة شحن هاتفك"),
                Def("discord_show_device", "إظهار الجهاز", "يُظهر نوع الجهاز"),
                Def("discord_show_wifi", "إظهار الشبكة", "يُظهر نوع الاتصال"),
                Def(
                    "discord_presence_mode", "نمط الظهور", "ما يظهر على حسابك", Kind.LIST,
                    entries = listOf("المشاهدة والنشاط", "المشاهدة فقط", "النشاط فقط"),
                    values = listOf("both", "watching_only", "idle_only"),
                    default = "both"
                )
            )
        ),
        Group(
            "التفاصيل المخصّصة",
            listOf(
                Def("discord_custom_title", "عنوان مخصّص", "نص تختاره يظهر بدل الاسم", Kind.TEXT, default = ""),
                Def("discord_custom_details", "سطر التفاصيل", "النص الثاني المخصّص", Kind.TEXT, default = ""),
                Def("discord_custom_state", "سطر الحالة", "النص الثالث المخصّص", Kind.TEXT, default = ""),
                Def("discord_custom_provider", "اسم المصدر المخصّص", "بديل مخصّص لاسم المصدر", Kind.TEXT, default = ""),
                Def("discord_idle_status", "حالة السكون", "ما يظهر عند عدم المشاهدة", Kind.TEXT, default = ""),
                Def("discord_media", "إظهار وسائط", "يُظهر صورة العمل على ديسكورد"),
                Def("discord_cdn_enabled", "خادم ديسكورد الوسيط", "يمرّر الصور عبر خادم ديسكورد"),
                Def("discord_debug_logging", "سجلّ التشخيص", "يُسجّل تفاصيل الاتصال للتشخيص")
            )
        ),
        Group(
            "التقدّم والوقت",
            listOf(
                Def("discord_timestamps", "إظهار التوقيت", "يعرض وقت البداية ووقت النهاية المتوقّع"),
                Def("discord_progress_bar", "شريط التقدّم", "يعرض كم مضى من الحلقة بصراحة"),
                Def("discord_activity_type", "نوع النشاط", "يُعرض كـ«يشاهد» أو «يستمع»", Kind.TEXT, default = "watching")
            )
        ),
        Group(
            "سطح المكتب والصورة",
            listOf(
                Def("discord_show_cover", "إظهار الغلاف الكبير", "صورة كبيرة للعمل على حسابك"),
                Def("discord_show_provider_icon", "إظهار أيقونة المصدر", "أيقونة صغيرة بجانب التفاصيل"),
                Def("discord_standalone_artwork", "صورة قائمة بذاتها", "يرسل صورة العمل كصورة مستقلّة"),
                Def("discord_upload_artwork", "رفع الصورة إلى ديسكورد", "يرفع الغلاف كي يظهر لكل من يراك"),
                Def("discord_large_text", "نص كبير", "يجعل النصوص المعروضة أكبر"),
                Def("discord_privacy_mode", "وضع الخصوصية", "يُخفي التفاصيل ويُظهر الحالة وحدها")
            )
        )
    )
)

// ═══════════════════════ (٧) التحميلات — `category_downloads`
// يقابل CSM: DownloadsSettingsFragment + DownloadFormatModitions + DownloadRules + DownloadWifiModitions

internal fun downloadsCategory() = Category(
    "downloads", "التحميلات",
    listOf(
        Group(
            "إعدادات التحميل",
            listOf(
                Def(
                    "preferred_quality", "الجودة المفضّلة", "الجودة التي تُحمَّل افتراضياً", Kind.LIST,
                    entries = listOf("أفضل جودة", "1080p", "720p", "480p", "360p", "أقل جودة"),
                    values = listOf("best", "1080", "720", "480", "360", "worst"),
                    default = "best"
                ),
                Def("batch_download", "تحميل دفعة واحدة", "حمّل كل الحلقات أو الموسم بضغطة"),
                Def("download_wifi_only", "التحميل عبر الواي فاي فقط", "يمنع التحميل على بيانات الجوال"),
                Def("download_charging_only", "التحميل أثناء الشحن فقط", "يبدأ التحميل عند توصيل الشاحن"),
                Def("download_concurrent", "عدد التحميلات المتزامنة", "كم ملفاً يُحمَّل في الوقت نفسه", Kind.TEXT, default = "3"),
                Def("download_progress_notification", "إظهار تقدّم التحميل", "شريط تقدّم في شريط التنبيهات"),
                Def(
                    "download_format", "صيغة الحفظ", "الصيغة التي تُحفظ بها الملفات", Kind.LIST,
                    entries = listOf("الأصلية", "MP4", "MKV", "TS"),
                    values = listOf("original", "mp4", "mkv", "ts"),
                    default = "original"
                ),
                Def("episode_download_button", "زر التحميل في بطاقة الحلقة", "يُظهر زر تحميل مباشر على كل حلقة"),
                Def("download_queue", "قائمة انتظار التحميل", "يعرض طابور التحميلات"),
                Def("auto_try_next_source", "تجربة المصدر التالي تلقائياً", "إن فشل مصدر ينتقل إلى التالي بلا سؤال")
            )
        ),
        Group(
            "مُحمِّل خارجي",
            listOf(
                Def("external_downloader", "استعمال مُحمِّل خارجي", "حمّل بواسطة تطبيق آخر"),
                Def("external_downloader_package", "تطبيق المُحمِّل", "اسم حزمة التطبيق المستعمل", Kind.TEXT, default = ""),
                Def(
                    "external_downloader_app", "المُحمِّل المفضّل", "التطبيق الجاهز المستعمل", Kind.LIST,
                    entries = listOf("بدون", "ADM", "IDM", "1DM", "NewPipe", "المتصفّح"),
                    values = listOf("none", "adm", "idm", "idm_lite", "newpipe", "browser"),
                    default = "none"
                ),
                Def("launch_external_downloader", "فتح المُحمِّل فوراً", "يفتح التطبيق الخارجي بمجرّد بدء التحميل")
            )
        ),
        Group(
            "إدارة الملفات",
            listOf(
                Def("cache_size", "حدّ المخزّق المؤقت (ميجابايت)", "الحجم الأقصى المخصّص للمخزّق", Kind.TEXT, default = "1000"),
                Def("clear_image_cache", "حذف مخزّق الصور", "يمحو الصور المحفوظة مؤقتاً — لا يحذف تحميلاتك"),
                Def("clear_all_cache", "حذف كل المخزّق المؤقت", "يمحو ملفات المؤقّت كلها"),
                Def(
                    "full_sweep_cache_policy", "نمط التنظيف الشامل", "متى يبدأ", Kind.LIST,
                    entries = listOf("يدوي", "عند امتلاء القرص", "أسبوعياً", "شهرياً"),
                    values = listOf("manual", "low_disk", "weekly", "monthly"),
                    default = "manual"
                ),
                Def("restore_deleted_plugins", "استعادة الإضافات المحذوفة", "يرجع إضافات حذفتها بالخطأ"),
                Def("app_cache_size", "حجم مخزّق التطبيق", "يعرض كم يشغل التطبيق من مساحة", Kind.INFO)
            )
        )
    )
)

// ═══════════════════════ (٨) الرئيسية — `category_home`
// يقابل CSM: HomePageSettingsFragment + HomeUIModitions + HomeUILayouts

internal fun homeCategory() = Category(
    "home", "الرئيسية",
    listOf(
        Group(
            "شكل الشبكة",
            listOf(
                Def(
                    "home_columns", "عدد الأعمدة في الرئيسية", "كم بطاقة في الصف الواحد", Kind.LIST,
                    entries = listOf("عمودان", "٣ أعمدة", "٤ أعمدة", "٥ أعمدة"),
                    values = listOf("2", "3", "4", "5"),
                    default = "3"
                ),
                Def("compact_grid", "شبكة مضغوطة", "بطاقات أصغر وعدد أكثر في الشاشة"),
                Def("preview_carousel", "معرض المعاينة العلوي", "شريط الصور الكبيرة أعلى الرئيسية"),
                Def("hide_home_fab", "إخفاء الزر العائم", "يُخفي الزر الدائري العائم في الرئيسية")
            )
        ),
        Group(
            "صفوف الرئيسية",
            listOf(
                Def("editable_rows", "صفوف قابلة للتعديل", "رتّب الصفوف أو أخفِها كما تريد"),
                Def("reorder_rows", "ترتيب الصفوف", "رتّب صفوف الرئيسية", Kind.TEXT, default = ""),
                Def("hide_rows", "إخفاء صفوف", "أسماء صفوف لا تريد رؤيتها", Kind.TEXT, default = ""),
                Def("infinite_scroll", "تمرير بلا نهاية", "يُحمّل مزيداً من البطاقات كلما نزلت"),
                Def("sticky_headers", "عناوين ثابتة", "يبقي اسم الصف ظاهراً أثناء التمرير"),
                Def("hide_empty_rows", "إخفاء الصفوف الفارغة", "لا تُعرض صفوف بلا نتائج"),
                Def("auto_disable_failed_rows", "تعطيل الصفوف المتعطّلة", "يُخفي مصدراً لا تُجلب صفوفه بدل إظهار مكانه فارغاً")
            )
        ),
        Group(
            "الشريط العلوي",
            listOf(
                Def("hide_toolbar", "إخفاء الشريط العلوي", "يُخفي شريط العنوان تماماً"),
                Def("collapsing_toolbar", "شريط علوي منكمش", "يتقلّص الشريط عند النزول"),
                Def("toolbar_elevation", "ظل الشريط العلوي", "مقدار الظل تحت الشريط", Kind.TEXT, default = "4")
            )
        ),
        Group(
            "المكتبة",
            listOf(
                Def(
                    "library_columns", "أعمدة المكتبة", "كم بطاقة في الصف", Kind.LIST,
                    entries = listOf("عمودان", "٣ أعمدة", "٤ أعمدة", "٥ أعمدة"),
                    values = listOf("2", "3", "4", "5"),
                    default = "3"
                ),
                Def(
                    "library_sort", "ترتيب المكتبة", "الترتيب المستعمل في مكتبتك", Kind.LIST,
                    entries = listOf("أُضيف حديثاً", "أبجدي", "التقييم", "سنة الإصدار", "آخر مشاهدة"),
                    values = listOf("date_added", "alpha", "rating", "year", "watched"),
                    default = "date_added"
                ),
                Def("unwatched_first", "غير المُشاهَد أولاً", "يقدّم ما لم تشاهده بعد"),
                Def("library_loading_shimmer", "وميض أثناء تحميل المكتبة", "هيكل بطاقات وامض بدل الشاشة الفارغة"),
                Def("library_search_filter", "البحث داخل المكتبة", "شريط بحث في أعلى المكتبة"),
                Def("library_tab_layout", "تبويبات المكتبة", "أقسام المكتبة كأشرطة تبويب"),
                Def("library_random", "زر الاختيار العشوائي", "يختار عملاً عشوائياً من مكتبتك"),
                Def(
                    "continuous_playback_last_episode_action", "فعل آخر حلقة", "ماذا يفعل التطبيق عند انتهاء آخر حلقة", Kind.LIST,
                    entries = listOf("لا شيء", "التالي في المكتبة", "الصفحة الرئيسية"),
                    values = listOf("none", "next_in_library", "home"),
                    default = "none"
                )
            )
        )
    )
)
