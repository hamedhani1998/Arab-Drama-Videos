package com.mosalsaly.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «Mosalsaly». BottomSheet يُعرض من زر إعدادات الإضافة في CloudStream.
 *
 * كل خيار يُخزَّن في SharedPreferences بصيغة `KEY_*` المفتوحة هنا، ويقرؤه
 * [MosalsalyProvider] مباشرة عند كل تشغيل/تصفح — دون أي وسيط.
 *
 * الخيارات:
 *  - الترجمة: تفعيل/تعطيل فحص وإرسال ملفات الترجمة.
 *  - الجودات: عرض كل الجودات أم الأعلى فقط (أسرع).
 *  - القوائم الرئيسية: إظهار أقسام «الأكثر شعبية/أحدث الإضافات» أم المنصات فقط.
 *  - لكل منصة مفتاح تفعيل مستقل — تعطيلها يحذف روابطها فوراً ولا يزور صفحتها.
 *  - «تحديث الروابط قسرياً»: يحثّ على إعادة جلب الواصف (refresh) حتى للروابط
 *    المخزّنة في ذاكرة التطبيق.
 */
class MosalsalySettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // عام
        const val KEY_SHOW_SUBTITLES = "mos_show_subtitles"          // Boolean الافتراضي true
        const val KEY_QUALITY_MODE = "mos_quality_mode"              // "all" | "high"
        const val KEY_FORCE_REFRESH = "mos_force_refresh"            // Boolean الافتراضي false
        const val KEY_SHOW_EXTRA_SECTIONS = "mos_show_extra"         // Boolean الافتراضي true
        const val KEY_RAW_LINKS = "mos_raw_links"                    // Boolean الافتراضي false

        /** بادئة مفاتيح تفعيل المنصات — لكل منصة مفتاح مستقل. */
        const val KEY_PLATFORM_PREFIX = "mos_platform_"

        // المنصات (تتطابق مع PLATFORMS في MosalsalyProvider)
        val PLATFORM_KEYS = listOf(
            "goodshort" to "GoodShort",
            "reelshort" to "Reelshort",
            "dotdrama" to "DotDrama",
            "dramabite" to "DramaBite",
            "dramabox" to "DramaBox",
            "flickreels" to "FlickReels",
            "happyshort" to "HappyShort",
            "joyreels" to "JoyReels",
            "kalostv" to "KalosTV",
            "moboreels" to "MoboReels",
            "moreshort" to "MoreShort",
            "mydramawave" to "MyDramaWave",
            "netshort" to "NetShort",
            "petadrama" to "PetaDrama",
            "shorttv" to "ShortTV",
            "shortwave" to "ShortWave",
            "stardust" to "Stardust",
            "storyreel" to "StoryReel",
        )

        fun isPlatformEnabled(prefs: SharedPreferences?, key: String): Boolean =
            prefs?.getBoolean(KEY_PLATFORM_PREFIX + key, true) ?: true

        fun showSubtitles(prefs: SharedPreferences?): Boolean =
            prefs?.getBoolean(KEY_SHOW_SUBTITLES, true) ?: true

        fun qualityMode(prefs: SharedPreferences?): String =
            prefs?.getString(KEY_QUALITY_MODE, "all") ?: "all"

        fun forceRefresh(prefs: SharedPreferences?): Boolean =
            prefs?.getBoolean(KEY_FORCE_REFRESH, false) ?: false

        fun showExtra(prefs: SharedPreferences?): Boolean =
            prefs?.getBoolean(KEY_SHOW_EXTRA_SECTIONS, true) ?: true

        fun rawLinks(prefs: SharedPreferences?): Boolean =
            prefs?.getBoolean(KEY_RAW_LINKS, false) ?: false

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            MosalsalySettings(prefs).show(fm, "mosalsaly_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            // ===== عام =====
            val generalCategory = PreferenceCategory(ctx)
            generalCategory.title = "خيارات عامة"
            preferenceScreen.addPreference(generalCategory)

            generalCategory.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_SUBTITLES
                title = "الترجمات"
                summary = "عرض ملفات الترجمة عند توفرها في المصدر"
                setDefaultValue(true)
            })

            generalCategory.addPreference(ListPreference(ctx).apply {
                key = KEY_QUALITY_MODE
                title = "الجودات"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "high")
                entries = arrayOf("كل الجودات", "الأعلى فقط (أسرع)")
                setDefaultValue("all")
            })

            generalCategory.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_FORCE_REFRESH
                title = "تحديث الروابط قسرياً"
                summary = "إعادة جلب روابط التشغيل في كل مرة (أبطأ قليلاً لكن يضمن روابط طازجة)"
                setDefaultValue(false)
            })

            generalCategory.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_EXTRA_SECTIONS
                title = "أقسام الواجهة"
                summary = "إظهار «الأكثر شعبية» و«أحدث الإضافات» أولاً في الواجهة"
                setDefaultValue(true)
            })

            generalCategory.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_RAW_LINKS
                title = "الروابط الخام"
                summary = "إظهار روابط الفيديو كما هي (للتشخيص) بدل أسماء الجودات"
                setDefaultValue(false)
            })

            // ===== المنصات =====
            val platformCategory = PreferenceCategory(ctx)
            platformCategory.title = "المنصات (18)"
            platformCategory.summary = "علِّم كل منصة تريد استخدامها"
            preferenceScreen.addPreference(platformCategory)

            for ((platKey, label) in PLATFORM_KEYS) {
                platformCategory.addPreference(SwitchPreferenceCompat(ctx).apply {
                    setKey(KEY_PLATFORM_PREFIX + platKey)
                    title = label
                    summary = "تمكين روابط $label"
                    setDefaultValue(true)
                })
            }

            // ===== إغلاق =====
            val resetPref = Preference(ctx).apply {
                key = "mos_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد كل الخيارات لأعلاها الافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_SHOW_SUBTITLES)
                        remove(KEY_QUALITY_MODE)
                        remove(KEY_FORCE_REFRESH)
                        remove(KEY_SHOW_EXTRA_SECTIONS)
                        remove(KEY_RAW_LINKS)
                        for ((k, _) in PLATFORM_KEYS) remove(KEY_PLATFORM_PREFIX + k)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}