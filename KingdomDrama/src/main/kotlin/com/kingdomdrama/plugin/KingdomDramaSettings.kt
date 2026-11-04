package com.kingdomdrama.plugin

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
 * إعدادات مصدر «KingdomDrama». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُحفظ في الملف نفسه الذي يمرّره [KingdomDramaPlugin] للمصدر، فيقرأه
 * [KingdomDramaProvider] عند كل طلب مباشرةً.
 */
class KingdomDramaSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        const val PREFS_NAME = "KingdomDrama"

        // الرئيسية
        const val KEY_ROW_LIMIT = "kd_row_limit"          // "all" | "10" | "6" | "3"
        const val KEY_CARDS_PER_ROW = "kd_cards_per_row"  // "50" | "24" | "12"
        const val KEY_SHOW_PANES = "kd_show_panes"        // Boolean — صفوف تبويبات الرئيسية
        const val KEY_SHOW_COUNTRIES = "kd_show_countries"// Boolean — صفوف الدول
        const val KEY_SHOW_YEAR = "kd_show_year"          // Boolean — صف سنة 2026
        const val KEY_CACHE_MIN = "kd_cache_min"          // "5" | "10" | "30" | "60"

        // التشغيل / يوتيوب
        const val KEY_YT_MODE = "kd_yt_mode"              // "auto" | "newpipe" | "html" | "extractor"
        const val KEY_MAX_HEIGHT = "kd_max_height"        // "0" | "1080" | "720" | "480"
        const val KEY_SUBS = "kd_subs"                    // Boolean
        const val KEY_SUB_LANGS = "kd_sub_langs"          // "ar,en" أو فارغ = الكل

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            KingdomDramaSettingsBottomSheet(prefs).show(fm, "kd_settings")
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
                    entryValues = arrayOf("all", "10", "6", "3")
                    entries = arrayOf(
                        "كل الصفوف (افتراضي)",
                        "أول 10 صفوف",
                        "أول 6 صفوف",
                        "أول 3 صفوف"
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
                    entryValues = arrayOf("50", "24", "12")
                    entries = arrayOf("كل المتاح", "24 عملًا", "12 عملًا")
                    setDefaultValue("50")
                }
            )

            homeCat.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SHOW_PANES
                    title = "صفوف تبويبات الموقع"
                    summary = "كل الأعمال + أحدث الأعمال + الملياردير + الذكاء الاصطناعي + الانتقام"
                    setDefaultValue(true)
                }
            )

            homeCat.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SHOW_COUNTRIES
                    title = "صفوف الدول"
                    summary = "صيني، كوري، ياباني، هونغ كونغ، غربي، تايلاندي، هندي، فيتنامي"
                    setDefaultValue(true)
                }
            )

            homeCat.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SHOW_YEAR
                    title = "صف أعمال 2026"
                    setDefaultValue(true)
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

            // ------------------------- التشغيل / يوتيوب -------------------------
            val playCat = PreferenceCategory(ctx).apply { title = "التشغيل وروابط يوتيوب" }
            preferenceScreen.addPreference(playCat)

            playCat.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_YT_MODE
                    title = "طريقة حلّ روابط يوتيوب"
                    summary = "«تلقائي» يجرّب NewPipe ثم صفحة المشاهدة ثم المُستخرِج المدمج"
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

            playCat.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_MAX_HEIGHT
                    title = "أعلى جودة مسموحة"
                    summary = "تُسقط الجودات الأعلى من الحد (0 = بلا حد)"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("0", "1080", "720", "480")
                    entries = arrayOf("بلا حد — كل الجودات (افتراضي)", "حتى 1080p", "حتى 720p", "حتى 480p")
                    setDefaultValue("0")
                }
            )

            playCat.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SUBS
                    title = "ترجمات يوتيوب"
                    summary = "إظهار ترجمات الفيديو في المشغّل (best effort)"
                    setDefaultValue(true)
                }
            )

            playCat.addPreference(
                EditTextPreference(ctx).apply {
                    key = KEY_SUB_LANGS
                    title = "لغات الترجمة المطلوبة"
                    summary = "مثال: ar,en — فارغ = العربية والإنجليزية"
                    setDefaultValue("")
                    dialogTitle = "لغات الترجمة المطلوبة"
                    setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT }
                }
            )

            // ----------------------------- إعادة الضبط -----------------------------
            preferenceScreen.addPreference(
                Preference(ctx).apply {
                    key = "kd_reset"
                    title = "إعادة الضبط الافتراضي"
                    summary = "يعيد كل الخيارات أعلاه إلى الافتراضي"
                    setOnPreferenceClickListener {
                        prefs.edit().apply {
                            remove(KEY_ROW_LIMIT)
                            remove(KEY_CARDS_PER_ROW)
                            remove(KEY_SHOW_PANES)
                            remove(KEY_SHOW_COUNTRIES)
                            remove(KEY_SHOW_YEAR)
                            remove(KEY_CACHE_MIN)
                            remove(KEY_YT_MODE)
                            remove(KEY_MAX_HEIGHT)
                            remove(KEY_SUBS)
                            remove(KEY_SUB_LANGS)
                        }.apply()
                        Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            )
        }
    }
}
