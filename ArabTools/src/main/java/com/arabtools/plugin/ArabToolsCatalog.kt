package com.arabtools.plugin

/**
 * كتالوج إعدادات «أدوات عربية» — النسخة العربية من إعدادات CSM (الإصدار 876).
 *
 * ### من أين جاءت هذه القائمة
 * فُكَّ ملف `CSMProvider.cs3` واستُخرج جدول النصوص من `classes.dex` (58210 سلسلة)،
 * فظهرت **1812 مفتاح إعداد** حقيقي و**95 وحدة ميزة** بأسمائها الأصلية
 * (`PlayerModitions`, `ThemeModitions`, `AutoSkipModitions` ...). هذه القائمة
 * منظّمة على تصنيفات CSM نفسها (`category_player`, `category_appearance`,
 * `category_home`, `category_downloads` ...) وبأسماء الإعدادات الحقيقية، مترجمة
 * إلى العربية ترجمة وظيفية لا حرفية.
 *
 * ### ما تعنيه [Impl]
 * CSM تعدّل التطبيق بالتطعيم العميق (انعكاس على أصناف داخلية و`ActivityThread`)،
 * وهو أسلوب غير متاح لنا: إضافتنا تستعمل واجهات أندرويد العامة فقط. لذلك:
 *
 * - [Impl.LIVE] — منفَّذ فعلاً ويعمل الآن (تعديل على الشاشة أو النافذة).
 * - [Impl.SOON] — معروض بالعربية ومفتاحه محفوظ، لكنه لم يُنفَّذ بعد. نُظهره
 *   بصراحة ولا نوهم بأنه يعمل.
 *
 * القائمة العربية كاملة كما طُلب، ولا كذبة في ما يعمل فعلاً.
 */
object ArabToolsCatalog {

    /** نوع الإعداد كما يُعرض في الشاشة. */
    enum class Kind { SWITCH, LIST, TEXT, INFO }

    /** حالة التنفيذ — بصراحة تامة. */
    enum class Impl { LIVE, SOON }

    /**
     * إعداد واحد في الكتالوج.
     *
     * [key] هو نفس مفتاح CSM إن وُجد، وإلا مفتاحنا ببادئة `art_`.
     */
    data class Def(
        val key: String,
        val title: String,
        val summary: String,
        val kind: Kind = Kind.SWITCH,
        val impl: Impl = Impl.SOON,
        val default: Any? = null,
        val entries: List<String>? = null,
        val values: List<String>? = null
    )

    /** مجموعة داخل تصنيف. */
    data class Group(val title: String, val defs: List<Def>)

    /** تصنيف — يقابل `PreferenceCategory` في شاشة CSM. */
    data class Category(val title: String, val groups: List<Group>)

    /** كل التصنيفات — كل واحد في ملفه كي يسهل تعديله وحده. */
    val CATEGORIES: List<Category> get() = listOf(
        generalCategory(),
        playerCategory(),
        appearanceCategory(),
        navigationCategory(),
        searchCategory(),
        downloadsCategory(),
        sourcesCategory(),
        notificationsCategory(),
        parentalCategory(),
        accessibilityCategory(),
        tvCategory(),
        discordCategory(),
        systemCategory(),
        backupCategory()
    )

    // ---------------------------------------------------------------- مشتركات

    /** ألوان جاهزة تُشارك فيها كل قوائم الألوان. */
    internal val COLOR_ENTRIES = listOf(
        "افتراضي (دون تغيير)", "أسود", "رمادي غامق", "أزرق", "بنفسجي", "أحمر",
        "أخضر", "برتقالي", "سماوي", "وردي"
    )
    internal val COLOR_VALUES = listOf(
        "default", "black", "dark", "blue", "purple", "red",
        "green", "orange", "cyan", "pink"
    )

    /** قائمة ألوان جاهزة — تختصر عشرات التعريفات المتشابهة. */
    internal fun colorList(
        key: String,
        title: String,
        summary: String,
        impl: Impl = Impl.SOON
    ) = Def(key, title, summary, Kind.LIST, impl, "default", COLOR_ENTRIES, COLOR_VALUES)

    /** تذكير يُضاف إلى كل مفتاح لا يظهر أثره إلا بإعادة تشغيل. */
    internal const val RESTART_HINT = "يحتاج إعادة تشغيل التطبيق ليظهر أثره كاملاً"
}
