package com.shortdramaar.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «دراما قصيرة» — مطابقة لإعدادات مصدر ARY: نفس المفاتيح،
 * ونفس الفئات، ونفس النصوص، ونفس السلوك. كل الخيارات `SharedPreferences`
 * مفتوحة تُقرأ مباشرةً بلا تخزين مركزي، تماماً كما في ARY، فيلتقطها
 * `ShortDramaProvider` عند كل تشغيل.
 *
 * ★ مزامنة مع ARY: الاختلاف الوحيد المتبقي هو **بادئة المفاتيح** (`sd_`
 * بدل `ary_`) واسم ملف التفضيلات (`SHORTDRAMA` بدل `ARY`) — وإلا لاطّلقت
 * الإضافتان على نفس الإعدادات وتغيّر سلوك ARY بلا قصد. أما ما يراه
 * المستخدم في الورقة فيتطابق سطراً بسطر مع ورقة ARY.
 */
class ShortDramaSettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext()).apply {
            id = android.view.View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        // ★ اسم ملف التفضيلات — يطابق ما يمرّره ShortDramaArPlugin للمصدر.
        const val PREFS_NAME = "SHORTDRAMA"
        const val KEY_PLAYBACK_MODE = "sd_playback_mode"       // "newpipe" | "direct" | "extractor"
        const val KEY_MAX_QUALITY = "sd_max_quality"         // "all" | "high"
        const val KEY_REDIRECT = "sd_use_redirect"           // Boolean بديل النطاق
        const val KEY_QUALITY_ORDER = "sd_quality_order"     // "default" | "asc" | "desc"
        const val KEY_EXTRA_CHANNELS = "sd_extra_channels"    // أسطر: رابط قناة، أو «الاسم | الرابط»
        const val KEY_SUB_LANG = "sd_sub_lang"               // "auto" (المصهر) أو رمز لغة كـ "en"
        const val KEY_AUDIO_PREF = "sd_audio_pref"           // "best" | "opus" | "aac" | "lowest" | "low" | "high"

        /**
         * اللغات المقترحة للترجمة — **نفس قائمة ARY تماماً**، مرتّبةً كما هي.
         *
         * سبب إبقاء القائمة كاملة رغم أن هذه القنوات مدبلجة (فالعربية صوت
         * أصلي لا ترجمة): الإعداد يجب أن يتطابق مع ARY سطراً بسطر كما طلب
         * المستخدم. وبقيّة اللغات يولّدها يوتيوب عند الطلب عبر `tlang` على
         * رابط `timedtext` — فمن اختارها فقد اختار ترجمة آلية، ومن أراد
         * المصهر فليترك «تلقائي» بلا طلب إضافي.
         *
         * تحفّظ (من قياس على هذا الجهاز، لا افتراض): استدعاء `timedtext`
         * بتلقائية بلا `tlang` يعيد 404، ومع أي `tlang` يعيد 429 — فاختيار
         * لغة قد يفشل على هذه الشبكة. «تلقائي» وحده هو المسار الآمن.
         */
        val SUB_LANGUAGES = listOf(
            "auto" to "مصهر (تلقائي)",
            "en" to "الإنجليزية",
            "fr" to "الفرنسية",
            "es" to "الإسبانية",
            "de" to "الألمانية",
            "tr" to "التركية",
            "ru" to "الروسية",
            "fa" to "الفارسية",
            "ur" to "الأردية",
            "hi" to "الهندية",
            "id" to "الإندونيسية",
            "ms" to "الماليزية",
            "zh-Hans" to "الصينية المبسّطة",
            "ja" to "اليابانية",
            "ko" to "الكورية",
            "pt" to "البرتغالية",
            "it" to "الإيطالية",
            "nl" to "الهولندية",
            "sw" to "السواحيلية",
            "so" to "الصومالية"
        )

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            ShortDramaSettings(prefs).show(fm, "shortdrama_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ اجعل الورقة تكتب في ملف التفضيلات نفسه الذي يقرأه
            // ShortDramaProvider («SHORTDRAMA»). بدونه تذهب الاختيارات إلى
            // ملف AndroidX الافتراضي الذي لا يقرأه المصدر — إعدادات تُحفظ
            // بلا أي أثر.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            // شرح الميزة في أول الورقة.
            val intro = PreferenceCategory(ctx)
            intro.title = "عن مصدر دراما قصيرة"
            preferenceScreen.addPreference(intro)

            val aboutPref = Preference(ctx).apply {
                title = "اثنتا عشرة قناة يوتيوب + قنوات إضافية"
                summary = "يعرض مسلسلات الدراما القصيرة من اثنتي عشرة قناة يوتيوب (كاملة ومدبلجة)، ويدعم إضافة قنواتٍ يوتيوب إضافية تظهر كلها في قسمٍ واحد «مقترحاتك» وتشملها نتائج البحث."
                setSelectable(false)
            }
            intro.addPreference(aboutPref)

            val channelsCategory = PreferenceCategory(ctx)
            channelsCategory.title = "قنوات إضافية"
            preferenceScreen.addPreference(channelsCategory)

            val extraChannelPref = EditTextPreference(ctx).apply {
                key = KEY_EXTRA_CHANNELS
                title = "قنوات يوتيوب إضافية"
                summary = "أضف قناةً لتظهر مسلسلاتها ضمن قسم «مقترحاتك» وضمن البحث. لتسمية القناة بنفسك اكتب الاسم ثم | ثم الرابط: الاسم | الرابط"
                dialogTitle = "قنوات يوتيوب إضافية"
                dialogMessage = "رابطٌ أو معرّفُ قناة في كل سطر:\n  https://youtube.com/@xxx\n  @xxx\n  UC…\n\nلتسمية القناة بنفسك اكتب الاسم ثم | ثم الرابط:\n  قناة القصص | https://youtube.com/@xxx\n\nكل القنوات المضافة تظهر في قسمٍ واحد «مقترحاتك» مهما زادت، حتى لا تتقلّب الواجهة. القنوات الاثنتا عشرة الأولى مدمجة في الإضافة ولا تحتاج إضافتها هنا."
                setOnPreferenceChangeListener { _, newVal ->
                    (newVal as? String)?.isNotBlank() == false || newVal != null
                }
            }
            channelsCategory.addPreference(extraChannelPref)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // أسلوب الاستخراج لروابط يوتيوب
            val modePref = ListPreference(ctx).apply {
                key = KEY_PLAYBACK_MODE
                title = "مسار التشغيل"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("newpipe", "direct", "extractor")
                entries = arrayOf(
                    "NewPipe + DASH (موصى به)",
                    "روابط HTML المباشرة",
                    "مستخرج CloudStream المدمج"
                )
                setDefaultValue("newpipe")
            }
            playbackCategory.addPreference(modePref)

            // الجودات: الكل أم الأعلى فقط
            val qualityPref = ListPreference(ctx).apply {
                key = KEY_MAX_QUALITY
                title = "الجودات"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "high")
                entries = arrayOf("كل الجودات", "الأعلى فقط (أسرع)")
                setDefaultValue("all")
            }
            playbackCategory.addPreference(qualityPref)

            // ترتيب الجودات عند البث — الافتراضي = ترتيب اليوم تماماً؛
            // التصاعدي/التنازلي يعيدان ترتيب روابط التشغيل فقط.
            val orderPref = ListPreference(ctx).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "افتراضي: نفس ترتيب روابط التشغيل كما هي"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("default", "asc", "desc")
                entries = arrayOf(
                    "الافتراضي (كما هو)",
                    "من الأقل إلى الأعلى",
                    "من الأعلى إلى الأقل"
                )
                setDefaultValue("default")
            }
            playbackCategory.addPreference(orderPref)

            // النطاق البديل (redirector)
            val redirectPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_REDIRECT
                title = "نطاق بديل (redirector)"
                summary = "قد يساعد على الشبكات التي تحجب مضيف rr*—sn-*"
                setDefaultValue(false)
            }
            playbackCategory.addPreference(redirectPref)

            // ===================== الترجمة والصوت =====================
            val mediaCategory = PreferenceCategory(ctx)
            mediaCategory.title = "الترجمة والصوت"
            preferenceScreen.addPreference(mediaCategory)

            val subLangPref = ListPreference(ctx).apply {
                key = KEY_SUB_LANG
                title = "لغة الترجمة"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = SUB_LANGUAGES.map { it.first }.toTypedArray()
                entries = SUB_LANGUAGES.map { it.second }.toTypedArray()
                setDefaultValue("auto")
            }
            mediaCategory.addPreference(subLangPref)

            // ملاحظة مقصودة: هذه القنوات لا تملك مسارات صوتية بديلة (لا
            // نسخة إنجليزية من الصوت الأصلي)، فـ`audioTracks` في CloudStream
            // لا مكان له هنا. ما نتيحه اختيار أيٍّ من تنسيقاتها الصوتية مع
            // كل جودة.
            val audioPref = ListPreference(ctx).apply {
                key = KEY_AUDIO_PREF
                title = "الملف الصوتي"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("best", "opus", "aac", "high", "low", "lowest")
                entries = arrayOf(
                    "الأفضل (موصى به)",
                    "Opus (أصغر حجماً)",
                    "AAC (توافق أوسع)",
                    "العالي",
                    "المتوسط",
                    "الأصغر حجماً"
                )
                setDefaultValue("best")
            }
            mediaCategory.addPreference(audioPref)

            // زر إعادة تسجيل بيانات — احتياطي.
            val resetPref = Preference(ctx).apply {
                key = "sd_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد كل الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_PLAYBACK_MODE); remove(KEY_MAX_QUALITY); remove(KEY_REDIRECT)
                        remove(KEY_QUALITY_ORDER); remove(KEY_EXTRA_CHANNELS)
                        remove(KEY_SUB_LANG); remove(KEY_AUDIO_PREF)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
