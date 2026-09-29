package com.aryarabia.plugin

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «ARY العربية». BottomSheet تُعرض من زر الإعدادات أو من قائمة
 * «إعدادات الإضافات» في CloudStream. كل خياراتها `SharedPreferences` مفتوحة —
 * القراءة مباشرة وبدون تخزين مركزي — تماماً كأسلوب re-3arabi، بحيث تلتقطها
 * `resolveFromNewPipe`/`resolveFromHtml` عند كل تشغيل.
 *
 * حالياً تنقر على <خيارات التشغيل> الفعلي: أي مسار يوتيوب نستخدم وكم جودة
 * نُظهر. أضف المزيد بلا كسر (كل مفتاح يستقل بذاته).
 */
class ArySettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يطابق ما يمرّره AryArabiaPlugin للمصدر.
        const val PREFS_NAME = "ARY"
        const val KEY_PLAYBACK_MODE = "ary_playback_mode"     // "newpipe" | "direct" | "extractor"
        const val KEY_MAX_QUALITY = "ary_max_quality"         // "all" | "high"
        const val KEY_REDIRECT = "ary_use_redirect"           // Boolean بديل النطاق
        const val KEY_QUALITY_ORDER = "ary_quality_order"     // "default" | "asc" | "desc"
        const val KEY_EXTRA_CHANNELS = "ary_extra_channels"  // أسطر: رابط قناة، أو «الاسم | الرابط»
        const val KEY_SUB_LANG = "ary_sub_lang"              // "auto" (عربية) أو رمز لغة كـ "en"
        const val KEY_AUDIO_PREF = "ary_audio_pref"          // "best" | "opus" | "aac" | "lowest" | "low" | "high"

        /**
         * لغات الترجمة المقترحة، وكلها من `translationLanguages` التي يعرضها
         * يوتيوب فعلياً (مُتحقَّق منها على فيديو ARY). ملاحظة مهمة: يوتيوب
         * ينشر على ARY ترجمةً عربية واحدة، وما عداها يُولّده عند الطلب عبر
         * `tlang` — فاختيار لغة تكلّف طلباً واحداً لكل حلقة.
         */
        val SUB_LANGUAGES = listOf(
            "auto" to "العربية (تلقائية)",
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
            ArySettingsBottomSheet(prefs).show(fm, "ary_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ اجعل الورقة تكتب في ملف التفضيلات نفسه الذي يقرأه AryProvider
            // («ARY»). بدونه تذهب الاختيارات إلى ملف AndroidX الافتراضي الذي لا
            // يقرأه المصدر — إعدادات تُحفظ بلا أي أثر.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            // شرح الميزة في أول الورقة.
            val intro = PreferenceCategory(ctx)
            intro.title = "عن مصدر ARY العربية"
            preferenceScreen.addPreference(intro)

            val aboutPref = Preference(ctx).apply {
                title = "قناة ARY يوتيوب + قنوات إضافية"
                summary = "يعرض مسلسلات قناة ARY من قوائم يوتيوب (كاملة ومترجمة)، ويدعم إضافة قنواتٍ يوتيوب إضافية تظهر كلها في قسمٍ واحد «المقترحات» وتشملها نتائج البحث."
                setSelectable(false)
            }
            intro.addPreference(aboutPref)

            val channelsCategory = PreferenceCategory(ctx)
            channelsCategory.title = "قنوات إضافية"
            preferenceScreen.addPreference(channelsCategory)

            val extraChannelPref = EditTextPreference(ctx).apply {
                key = KEY_EXTRA_CHANNELS
                title = "قنوات يوتيوب إضافية"
                summary = "أضف قناةً لتظهر مسلسلاتها ضمن قسم «المقترحات» وضمن البحث. لتسمية القناة بنفسك اكتب الاسم ثم | ثم الرابط: الاسم | الرابط"
                dialogTitle = "قنوات يوتيوب إضافية"
                dialogMessage = "رابطٌ أو معرّفُ قناة في كل سطر:\n  https://youtube.com/@xxx\n  @xxx\n  UC…\n\nلتسمية القناة بنفسك اكتب الاسم ثم | ثم الرابط:\n  قناة القصص | https://youtube.com/@xxx\n\nكل القنوات (المدمجة والمضافة) تظهر في قسمٍ واحد «المقترحات» مهما زادت، حتى لا تتقلّب الواجهة.\n\nاتركه فارغاً لسلوك اليوم تماماً."
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
            // التصاعدي/التنازلي يعيدان ترتيب روابط التشغيل فقط (بلا حذف/تكرار).
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

            // ملاحظة مقصودة: ARY لا تملك مسارات صوتية بديلة (لا نسخة
            // إنجليزية من الصوت)، فـ`audioTracks` في CloudStream لا مكان
            // له هنا. ما نتيحه اختيار أيٍّ من تنسيقاتها الصوتية الاثني
            // عشر مع كل جودة.
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
                key = "ary_reset_prefs"
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