package com.arabtools.plugin

import com.arabtools.plugin.ArabToolsCatalog.Category
import com.arabtools.plugin.ArabToolsCatalog.Def
import com.arabtools.plugin.ArabToolsCatalog.Group
import com.arabtools.plugin.ArabToolsCatalog.Impl
import com.arabtools.plugin.ArabToolsCatalog.Kind

// ═══════════════════════════════════════════════ (٨) التنبيهات

internal fun notificationsCategory() = Category(
    "التنبيهات",
    listOf(
        Group(
            "إشعارات عامة",
            listOf(
                Def("update_notifications_enabled", "تنبيه وجود تحديث", "يُخبرك عند صدور تحديث للتطبيق أو الإضافة"),
                Def("new_episode_alerts", "تنبيه الحلقات الجديدة", "يُخبرك عند نزول حلقة جديدة لمسلسل تتابعه"),
                Def("notification_sound", "صوت التنبيه", "الصوت المرافق للتنبيهات"),
                Def("clear_notifications", "مسح كل التنبيهات", "يمحو التنبيهات الظاهرة الآن"),
                Def(
                    "dismissed_notifications", "التنبيهات المُستبعَدة", "تنبيهات أخفيتها — لا تُعرض مرة أخرى", Kind.INFO
                )
            )
        ),
        Group(
            "شكل التنبيه",
            listOf(
                Def("notification_card_style", "شكل بطاقة التنبيه", "مظهر التنبيه في شريط التنبيهات"),
                Def("notification_blur_enabled", "تمويه خلفية التنبيه", "خلفية ضبابية للتنبيه"),
                Def("notification_overlay_opacity", "شفافية طبقة التنبيه", "شدة ظهور التنبيه فوق التطبيق", Kind.TEXT, default = "80"),
                Def("notification_preview", "معاينة التنبيه", "يعرض معاينة للتنبيه الحالي", Kind.INFO)
            )
        ),
        Group(
            "تنبيهات المشاهدة",
            listOf(
                Def("status_notification", "تنبيه حالة التشغيل", "تنبيه ثابت أثناء تشغيل فيديو"),
                Def("now_playing_overlay_enabled", "طبقة «يُشغَّل الآن»", "طبقة تعرض ما يُشغَّل حالياً"),
                Def("episode_air_status_show_watched", "تنبيه الحلقات غير المُشاهَدة", "يعدّ الحلقات التي لم تشاهدها"),
                Def("background_audio_show_notification", "تنبيه الصوت في الخلفية", "تحكّم الصوت من شريط التنبيهات")
            )
        )
    )
)

// ═══════════════════════════════════════════════ (٩) الرقابة الأبوية

internal fun parentalCategory() = Category(
    "الرقابة الأبوية",
    listOf(
        Group(
            "القفل والرمز",
            listOf(
                Def("parental_time_lock", "قفل وقت المشاهدة", "يحدّ وقت المشاهدة اليومي"),
                Def("parental_pin", "الرمز السرّي", "رمز من أرقام يحمي الإعدادات", Kind.TEXT, default = ""),
                Def("change_pin", "تغيير الرمز السرّي", "يستبدل الرمز الحالي"),
                Def("reset_pin", "إعادة تعيين الرمز", "يمسح الرمز إن نسيته — يحتاج تأكيداً"),
                Def("parental_recovery", "استعادة الرمز", "طريقة استرجاع الرمز عند نسيانه")
            )
        ),
        Group(
            "حدود المشاهدة",
            listOf(
                Def("parental_schedule", "جدول المشاهدة المسموح", "الوقت الذي يُسمح فيه بالمشاهدة"),
                Def("parental_time_lock_hour", "ساعة بدء السماح", "بصيغة ٢٤ ساعة", Kind.TEXT, default = "0"),
                Def("parental_time_lock_minute", "دقيقة بدء السماح", "الدقيقة", Kind.TEXT, default = "0"),
                Def(
                    "parental_time_lock_action", "فعل تجاوز الوقت", "ما يحدث عند انتهاء الوقت المسموح", Kind.LIST,
                    entries = listOf("إيقاف التشغيل", "إظهار تنبيه", "طلب الرمز"),
                    values = listOf("stop", "warn", "require_pin"),
                    default = "stop"
                ),
                Def("time_lock_temporarily_disable", "إيقاف القفل مؤقتاً", "يمنح استثناءً لمدّة محدّدة"),
                Def(
                    "parental_scope", "نطاق الرقابة", "ما تخضع له الرقابة", Kind.LIST,
                    entries = listOf("التطبيق كاملاً", "المشغّل فقط", "الإعدادات فقط"),
                    values = listOf("app", "player", "settings"),
                    default = "app"
                ),
                Def("maturity_filter_enabled", "تصفية التصنيف العمري", "يُخفي المحتوى فوق تصنيف محدّد"),
                Def("hide_adult_content", "إخفاء محتوى البالغين", "يمنع ظهور الأعمال المخصّصة للبالغين")
            )
        ),
        Group(
            "الحالة",
            listOf(
                Def("parental_state", "حالة القفل", "هل القفل مفعّل الآن؟", Kind.INFO),
                Def("parental_schedule_status", "حالة الجدول", "هل الوقت الآن مسموح؟", Kind.INFO),
                Def("pin_failed_attempts", "محاولات الرمز الخاطئة", "كم مرة أُدخل رمز خاطئ", Kind.INFO)
            )
        )
    )
)

// ═══════════════════════════════════════════════ (١٠) إمكانية الوصول

internal fun accessibilityCategory() = Category(
    "إمكانية الوصول",
    listOf(
        Group(
            "الرؤية",
            listOf(
                Def("high_contrast", "تباين عالٍ", "نصوص وألوان أوضح لمن يجد الصور باهتة"),
                Def("font_scale", "تكبير الخط العام", "يكبّر كل نصوص التطبيق", Kind.TEXT, default = "1.0"),
                Def("reduce_motion", "تقليل الحركة", "يخفّف الحركات — مفيد لمن يتعب من الحركة"),
                Def("screen_reader_hints", "تلميحات قارئ الشاشة", "نصوص صوتية إضافية لقارئ الشاشة"),
                Def("reduce_transparency", "تقليل الشفافية", "يجعل الخلفيات معتمة لا شفافة")
            )
        ),
        Group(
            "اللمس والصوت",
            listOf(
                Def("haptic_feedback_enabled", "الاهتزاز اللمسي", "اهتزاز خفيف عند لمس الأزرار"),
                Def("large_touch_targets", "أهداف لمس كبيرة", "أزرار أكبر يسهل لمسها"),
                Def("one_hand", "الاستعمال بيد واحدة", "يحوّل الواجهة لتناسب يداً واحدة"),
                Def("vocal_booster", "تقوية الأصوات", "يُبرز الكلام على الموسيقى"),
                Def("mono_audio", "صوت أحادي القناة", "يدمج القناتين — مفيد لمن بسمع في أذن واحدة"),
                Def("hide_microphone", "إخفاء زر الميكروفون", "يُخفي زر البحث الصوتي")
            )
        ),
        Group(
            "الوصول السريع",
            listOf(
                Def("app_shortcut", "اختصار التطبيق", "أضف اختصاراً سريعاً على الشاشة الرئيسية"),
                Def("create_default_shortcut", "إنشاء اختصار بدء عادي", "اختصار يفتح التطبيق مباشرة"),
                Def("create_custom_shortcut", "إنشاء اختصار مخصّص", "اختصار يفتح شاشة بعينها"),
                Def("app_shortcut_search", "اختصار البحث", "اختصار يفتح البحث مباشرة")
            )
        )
    )
)

// ═══════════════════════════════════════════════ (١١) أندرويد تي في

internal fun tvCategory() = Category(
    "أندرويد تي في",
    listOf(
        Group(
            "التخطيط",
            listOf(
                Def("tv_layout_enabled", "تخطيط تي في", "يُفعّل الواجهة المصمّمة للشاشات الكبيرة"),
                Def("tv_layout_redesign", "التخطيط الجديد", "نسخة محدّثة من تخطيط تي في"),
                Def("tv_larger_posters", "صور أكبر", "بطاقات أكبر تناسب المشاهدة من بعيد"),
                Def("tv_horizontal_scroll", "تمرير أفقي", "صفوف تمرّ أفقياً كما في الشاشات الكبيرة"),
                Def("tv_focus_animation", "حركة التركيز", "تأثير عند التنقّل بالريموت"),
                Def("tv_default", "تي في هو الافتراضي", "يبدأ التطبيق بتخطيط تي في"),
                Def("carousel_focus", "تركيز المعرض", "يُبرز المعرض العلوي عند التركيز")
            )
        ),
        Group(
            "الريموت والتحكّم",
            listOf(
                Def("tv_keyboard_enabled", "لوحة مفاتيح تي في", "استعمال لوحة مفاتيح الشاشة"),
                Def("tv_optimized", "تحسين للريموت", "أزرار وأهداف تناسب الريموت"),
                Def("fire_tv", "وضع فاير تي في", "تحسينات خاصة بأجهزة أمازون"),
                Def("android_tv", "وضع أندرويد تي في", "تحسينات لأجهزة أندرويد تي في")
            )
        ),
        Group(
            "الإرسال إلى تي في",
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
        )
    )
)

// ═══════════════════════════════════════════════ (١٢) ديسكورد

internal fun discordCategory() = Category(
    "ديسكورد",
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
        )
    )
)

// ═══════════════════════════════════════════════ (١٣) النظام

internal fun systemCategory() = Category(
    "النظام",
    listOf(
        Group(
            "معلومات التطبيق",
            listOf(
                Def("app_name", "اسم التطبيق الظاهر", "الاسم الذي يظهر على أيقونة التطبيق", Kind.TEXT, default = "CloudStream"),
                Def("custom_app_icon", "أيقونة التطبيق", "أيقونة بديلة تختارها"),
                Def("app_icon_color", "لون أيقونة التطبيق", "لون الأيقونة المختصرة"),
                Def("custom_title", "عنوان مخصّص", "عنوان يظهر أعلى الشاشات", Kind.TEXT, default = ""),
                Def("version_text", "نص الإصدار", "النص الظاهر في المعلومات", Kind.INFO),
                Def("installed_version", "الإصدار المثبّت", "رقم الإصدار الحالي", Kind.INFO),
                Def("os_version", "إصدار أندرويد", "نظام تشغيل هاتفك", Kind.INFO)
            )
        ),
        Group(
            "الصيانة والتشخيص",
            listOf(
                Def("developer_mode", "وضع المطوّر", "يُظهر أدوات متقدّمة للتشخيص"),
                Def("csm_debug_logging", "سجلّ التشخيص", "يكتب تفاصيل العمل في السجلّ"),
                Def("debug_overlay", "طبقة التشخيص", "يعرض معلومات تقنية فوق التطبيق"),
                Def("view_inspector", "مفتّش الواجهة", "يُظهر معرّفات العناصر عند لمسها"),
                Def("view_dump", "تفريغ شجرة الواجهة", "يسجّل كل عناصر الشاشة الحالية", Kind.INFO),
                Def("export_settings", "تصدير الإعدادات", "يحفظ كل إعداداتك في نصّ"),
                Def("import_settings", "استيراد الإعدادات", "يستعيد إعدادات من نصّ محفوظ"),
                Def("reset_settings", "إعادة ضبط كل الإعدادات", "يرجع كل شيء إلى الافتراضي — لا يمكن التراجع"),
                Def("bug_report", "إبلاغ عن مشكلة", "يفتح صفحة الإبلاغ مع تفاصيل جهازك"),
                Def("system_report", "تقرير النظام", "ملخّص معلومات الجهاز والتطبيق", Kind.INFO)
            )
        ),
        Group(
            "التخزين",
            listOf(
                Def("clear_image_cache", "حذف مخزّق الصور", "يمحو الصور المؤقّتة"),
                Def("clear_all_cache", "حذف كل المخزّق المؤقت", "يمحو الملفات المؤقّتة كلها"),
                Def("coil_cache", "حجم مخزّق الصور", "يعرض كم تشغل الصور المؤقّتة", Kind.INFO),
                Def("system_storage", "استعمال التخزين", "تفصيل المساحة المستعملة", Kind.INFO)
            )
        )
    )
)

// ═══════════════════════════════════════════════ (١٤) النسخ الاحتياطي

internal fun backupCategory() = Category(
    "النسخ الاحتياطي والمزامنة",
    listOf(
        Group(
            "النسخ الاحتياطي",
            listOf(
                Def("system_backup", "نسخة احتياطية كاملة", "يحفظ إعداداتك ومكتبتك وملفاتك الشخصية"),
                Def("export_settings", "تصدير الإعدادات فقط", "يحفظ الإعدادات بلا محتوى"),
                Def("export_include_player", "تضمين إعدادات المشغّل", "يضمّ إعدادات المشغّل في النسخة"),
                Def("export_include_discord", "تضمين إعدادات ديسكورد", "يضمّ إعدادات ديسكورد في النسخة"),
                Def("import_settings", "استعادة نسخة", "يرجع الإعدادات من ملف نسخة"),
                Def("last_sync_time", "وقت آخر مزامنة", "متى تمّت آخر مزامنة", Kind.INFO)
            )
        ),
        Group(
            "المزامنة السحابية",
            listOf(
                Def("cloud_sync", "المزامنة السحابية", "يزامن مكتبتك عبر خدمة سحابية"),
                Def("watch_progress_sync", "مزامنة تقدّم المشاهدة", "يشارك «وصلت إلى هنا» بين أجهزتك"),
                Def("cloudstream_accounts", "حسابات CloudStream", "اربط حساباً للمزامنة"),
                Def("alternative_account", "حساب بديل", "حساب آخر للمزامنة"),
                Def("sync_interval", "مدّة المزامنة (دقائق)", "كل كم يزامن تلقائياً", Kind.TEXT, default = "15")
            )
        ),
        Group(
            "التصدير والاستيراد",
            listOf(
                Def("exported_at", "تاريخ آخر تصدير", "متى أُنشئت آخر نسخة", Kind.INFO),
                Def("copy_settings_to_clipboard", "نسخ الإعدادات إلى الحافظة", "ينسخ كل الإعدادات نصّاً"),
                Def("restore_from_clipboard", "استعادة من الحافظة", "يقرأ إعدادات من الحافظة"),
                Def("reset_all", "حذف كل الإعدادات", "يمحو كل الإعدادات بلا رجعة")
            )
        )
    )
)
