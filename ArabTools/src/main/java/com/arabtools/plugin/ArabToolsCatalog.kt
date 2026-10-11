package com.arabtools.plugin

/**
 * كتالوج إعدادات «أدوات عربية» — مبنيّ على تصنيفات CSM الحقيقية (الإصدار ٨٧٦).
 *
 * ### من أين جاءت هذه القائمة
 * فُكَّ ملف `CSMProvider.cs3` (وهو ملف مضغوط يحوي `classes.dex`) وقُرئ جدول النصوص
 * منه، فظهرت أسماء تصنيفاته الحقيقية: `category_advancements`، `category_animations`،
 * `category_appearance`، `category_developer`، `category_device`، `category_discord`،
 * `category_downloads`، `category_home`، `category_navigation`، `category_notifications`،
 * `category_parental`، `category_player`، `category_rows`، `category_sources`،
 * `category_sync`، `category_system` — **ستة عشر تصنيفاً**، وهي نفسها ترتيب هذه القائمة.
 *
 * ولم تكن الأسماء وحدها: ظهرت كذلك شاشات CSM لكل تصنيف
 * (`PlayerSettingsFragment`، `AppearanceSettingsFragment`، `AdvancementsSettingsFragment` ...)
 * ووحدات ميزاته (`PlayerModitions`، `AutoSkipModitions`، `DownloadFormatModitions` ...)،
 * فبُنيت المجموعات داخل كل تصنيف على أساسها. كل [Category] تحمل حقل [Category.csm]
 * وهو مفتاح CSM الأصلي — فيمكن التحقّق من التطابق آلياً لا بالظنّ.
 *
 * ### ما تعنيه [Impl]
 * CSM تعدّل التطبيق بالتطعيم العميق (انعكاس على أصناف داخلية و`ActivityThread`)، وهو
 * أسلوب غير متاح لنا: إضافتنا تستعمل واجهات أندرويد العامة فقط. لذلك:
 *
 * - [Impl.LIVE] — منفَّذ فعلاً ويعمل الآن (تعديل على الشاشة أو النافذة).
 * - [Impl.SOON] — معروض بالعربية ومفتاحه محفوظ، لكنه لم يُنفَّذ بعد. نُظهره بصراحة
 *   ولا نوهم بأنه يعمل.
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

    /**
     * تصنيف — يقابل `PreferenceCategory` في شاشة CSM.
     *
     * [csm] هو مفتاح CSM الأصلي (`category_appearance` مثلاً) بلا بادئة `category_`،
     * ووُضع كي يمكن إثبات التطابق: التصنيفات الستة عشر هنا هي الستة عشر هناك.
     */
    data class Category(val csm: String, val title: String, val groups: List<Group>)

    /**
     * كل التصنيفات — ستة عشر، بترتيب CSM نفسه. كل أربعة في ملف لتسهيل التعديل وحده.
     *
     * [csmCategories] أدناه هي القائمة المرجعية المستخرجة من `classes.dex`، ويُتحقَّق
     * منها في [parity] — فإن سقط تصنيف أو زِيد آخر ظهر ذلك فوراً.
     */
    val CATEGORIES: List<Category> get() = listOf(
        advancementsCategory(),
        animationsCategory(),
        appearanceCategory(),
        developerCategory(),
        deviceCategory(),
        discordCategory(),
        downloadsCategory(),
        homeCategory(),
        navigationCategory(),
        notificationsCategory(),
        parentalCategory(),
        playerCategory(),
        rowsCategory(),
        sourcesCategory(),
        syncCategory(),
        systemCategory()
    )

    /** مفاتيح تصنيفات CSM الحقيقية كما استُخرجت من الـdex — المرجع للتحقّق. */
    val CSM_KEYS = listOf(
        "category_advancements", "category_animations", "category_appearance",
        "category_developer", "category_device", "category_discord",
        "category_downloads", "category_home", "category_navigation",
        "category_notifications", "category_parental", "category_player",
        "category_rows", "category_sources", "category_sync", "category_system"
    )

    /**
     * يقارن تصنيفاتنا بتصنيفات CSM.
     *
     * @return قائمة الفروق — فارغة تعني تطابقاً تامّاً.
     */
    fun parity(): List<String> {
        val ours = CATEGORIES.map { "category_${it.csm}" }
        val diffs = mutableListOf<String>()
        CSM_KEYS.filterNot { it in ours }.forEach { diffs += "ناقص عندنا: $it" }
        ours.filterNot { it in CSM_KEYS }.forEach { diffs += "زائد عندنا: $it" }
        if (ours != CSM_KEYS) diffs += "الترتيب مختلف عن ترتيب CSM"
        return diffs
    }

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
