package com.daramly.plugin

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
 * إعدادات مصدر «Daramly». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُحفظ في الملف نفسه الذي يمرّره [DaramlyPlugin] للمصدر، فيقرأه
 * [DaramlyProvider] عند كل طلب مباشرةً.
 */
class DaramlySettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        const val PREFS_NAME = "Daramly"

        // الرئيسية
        const val KEY_ROW_LIMIT = "dr_row_limit"          // "all" | "12" | "8" | "4"
        const val KEY_CARDS_PER_ROW = "dr_cards_per_row"  // "36" | "24" | "12"
        const val KEY_CACHE_MIN = "dr_cache_min"          // "5" | "10" | "30" | "60"

        // سيرفرات التشغيل
        const val KEY_EXTRA_SERVERS = "dr_extra_servers"  // Boolean — ضمّ «سيرفرات إضافية»
        const val KEY_EXTRA_PAGES = "dr_extra_pages"      // "0".."3"
        const val KEY_RUMBLE_AUTO = "dr_rumble_auto"      // Boolean — بثّ رامبل التلقائي
        const val KEY_SUBS = "dr_subs"                    // Boolean — ترجمات cc
        const val KEY_QUALITY_ORDER = "dr_quality_order"  // "site" | "desc" | "asc"
        const val KEY_YT_MODE = "dr_yt_mode"              // "auto" | "newpipe" | "html" | "extractor"

        /** مفتاح صفٍّ مستقل في الواجهة الرئيسية (بالترتيب في [DARAMLY_ROWS]). */
        fun rowKey(index: Int) = "dr_row_$index"

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DaramlySettingsBottomSheet(prefs).show(fm, "dr_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(PREFS_NAME)
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            // ---------------------------- الرئيسية ----------------------------
            val homeCat = PreferenceCategory(ctx).apply { title = "الواجهة الرئيسية" }
            preferenceScreen.addPreference(homeCat)

            homeCat.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_ROW_LIMIT
                    title = "عدد الصفوف المعروضة"
                    summary = "أقل صفوف = ظهور أسرع للواجهة الرئيسية"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("all", "12", "8", "4")
                    entries = arrayOf(
                        "كل الصفوف (افتراضي)",
                        "أول 12 صفًا",
                        "أول 8 صفوف",
                        "أول 4 صفوف"
                    )
                    setDefaultValue("all")
                }
            )

            homeCat.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_CARDS_PER_ROW
                    title = "عدد الأعمال في الصف"
                    summary = "عدد البطاقات المسحوبة من كل صف"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("36", "24", "12")
                    entries = arrayOf("كل المتاح", "24 عملًا", "12 عملًا")
                    setDefaultValue("36")
                }
            )

            homeCat.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_CACHE_MIN
                    title = "مدة حفظ الرئيسية"
                    summary = "بعدها يُعاد جلب الصفحة من الموقع عند أول تحديث"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("5", "10", "30", "60")
                    entries = arrayOf("5 دقائق", "10 دقائق (افتراضي)", "30 دقيقة", "ساعة")
                    setDefaultValue("10")
                }
            )

            // صفوف الرئيسية: مفتاح لكل صف — الأقسام الأربعة ثم التصنيفات العشرة.
            val sectionsCat = PreferenceCategory(ctx).apply {
                title = "صفوف الأقسام"
                summary = "أول أربعة صفوف المأخوذة من صفحة الموقع"
            }
            preferenceScreen.addPreference(sectionsCat)

            val catsCat = PreferenceCategory(ctx).apply {
                title = "صفوف التصنيفات"
                summary = "صفحات /category/ العشرة"
            }
            preferenceScreen.addPreference(catsCat)

            DARAMLY_ROWS.forEachIndexed { index, (_, label) ->
                val pref = SwitchPreferenceCompat(ctx).apply {
                    key = rowKey(index)
                    title = label
                    summary = "إظهار/إخفاء صف «$label»"
                    setDefaultValue(true)
                }
                if (index < 4) sectionsCat.addPreference(pref) else catsCat.addPreference(pref)
            }

            // ------------------------- سيرفرات التشغيل -------------------------
            val playCat = PreferenceCategory(ctx).apply { title = "سيرفرات التشغيل" }
            preferenceScreen.addPreference(playCat)

            playCat.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_EXTRA_SERVERS
                    title = "ضمّ «سيرفرات إضافية»"
                    summary = "صفحات السيرفرات البديلة التي يعلنها الموقع لنفس العمل"
                    setDefaultValue(true)
                }
            )

            playCat.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_EXTRA_PAGES
                    title = "أقصى عدد لصفحات السيرفرات الإضافية"
                    summary = "كل صفحة تُضيف طلبًا إضافيًا عند التشغيل"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("0", "1", "2", "3")
                    entries = arrayOf("بلا صفحات إضافية", "صفحة واحدة", "صفحتان", "ثلاث صفحات (افتراضي)")
                    setDefaultValue("3")
                }
            )

            playCat.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_RUMBLE_AUTO
                    title = "بثّ Rumble التلقائي"
                    summary = "إظهار رابط auto/HLS إلى جانب جودات mp4"
                    setDefaultValue(true)
                }
            )

            playCat.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SUBS
                    title = "ترجمات السيرفرات (cc)"
                    summary = "إظهار أي ملفات ترجمة يعلنها سيرفر التضمين"
                    setDefaultValue(true)
                }
            )

            playCat.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_QUALITY_ORDER
                    title = "ترتيب الجودات في قائمة السيرفرات"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("site", "desc", "asc")
                    entries = arrayOf(
                        "كما يظهرها الموقع (افتراضي)",
                        "الأعلى جودة أولًا",
                        "الأدنى جودة أولًا"
                    )
                    setDefaultValue("site")
                }
            )

            playCat.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_YT_MODE
                    title = "طريقة حلّ روابط يوتيوب"
                    summary = "للسيرفرات التي تضمّن فيديو يوتيوب"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("auto", "newpipe", "html", "extractor")
                    entries = arrayOf(
                        "تلقائي — الأقوى (افتراضي)",
                        "NewPipe أولًا",
                        "صفحة المشاهدة أولًا",
                        "المُستخرِج المدمج أولًا"
                    )
                    setDefaultValue("auto")
                }
            )

            // ----------------------------- إعادة الضبط -----------------------------
            preferenceScreen.addPreference(
                Preference(ctx).apply {
                    key = "dr_reset"
                    title = "إعادة الضبط الافتراضي"
                    summary = "يعيد كل الخيارات أعلاه إلى الافتراضي"
                    setOnPreferenceClickListener {
                        prefs.edit().apply {
                            remove(KEY_ROW_LIMIT)
                            remove(KEY_CARDS_PER_ROW)
                            remove(KEY_CACHE_MIN)
                            remove(KEY_EXTRA_SERVERS)
                            remove(KEY_EXTRA_PAGES)
                            remove(KEY_RUMBLE_AUTO)
                            remove(KEY_SUBS)
                            remove(KEY_QUALITY_ORDER)
                            remove(KEY_YT_MODE)
                            DARAMLY_ROWS.indices.forEach { remove(rowKey(it)) }
                        }.apply()
                        Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            )
        }
    }
}
