package com.dramavideoshow.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
 * إعدادات مصدر «DramaVideoShow». تُعرض من زر الإعدادات في CloudStream، وتُحفظ
 * في ملف تفضيلات خاص بالوحدة (تنميط مثل Dramadunyam).
 *
 * الخيارات هنا خاصة بالإضافة لا عامة: الموقع بلا ترجمات (لا يبث srt/vtt أبداً)
 * وكل حلقة برابطٍ واحد، فلا معنى لإعداد «الترجمة» أو «عدد مصادر التشغيل». ما
 * ينفع المستخدم فعلاً: إظهار الواجهة أو إخفاؤها، عدد أقسامها (زمن أول رسم)،
 * ترتيب الحلقات، ووسم الصيغة في اسم السيرفر.
 */
class DramaVideoShowSettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        /** اسم ملف التفضيلات — يطابق ما يمرّره [DramaVideoShowPlugin]. */
        const val PREFS_NAME = "DramaVideoShow"

        // مفاتيح هذه الوحدة (بادئة فريدة — لا تعارض مع أي وحدة أخرى).
        const val KEY_SHOW_HOME = "dvs_show_home"        // Boolean — إظهار الواجهة الرئيسية
        const val KEY_HOME_ROWS = "dvs_home_rows"        // "all" | "6" | "4" — عدد أقسام الرئيسية
        const val KEY_EPISODE_ORDER = "dvs_episode_order" // "as_is" | "desc" — ترتيب الحلقات
        const val KEY_FORMAT_TAG = "dvs_format_tag"      // Boolean — وسم الصيغة في اسم السيرفر

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DramaVideoShowSettings(prefs).show(fm, "dvs_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val homeCategory = PreferenceCategory(ctx)
            homeCategory.title = "الواجهة الرئيسية"
            preferenceScreen.addPreference(homeCategory)

            // إظهار الصفحة الرئيسية (أقسام «أحدث الإصدارات» وغيرها). `hasMainPage`
            // خاصية ديناميكية فيُسحَب المصدر من الصفحة الرئيسية أو يُخفى فوراً.
            val showHomePref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_HOME
                title = "إظهار الواجهة الرئيسية"
                summary = "أقسام الموقع الرئيسية (الأحدث، الأكثر رواجًا، التصنيفات)"
                setDefaultValue(true)
            }
            homeCategory.addPreference(showHomePref)

            // عدد أقسام الرئيسية المعروضة — كل قسم صفٌّ في الواجهة، والواجهة
            // تنتظر كل الصفوف قبل أول رسم، فتقليل العدد يسرّع فتح المصدر.
            val homeRowsPref = ListPreference(ctx).apply {
                key = KEY_HOME_ROWS
                title = "عدد أقسام الرئيسية"
                summary = "كل قِسْم صفٌّ ينتظره أول رسم — الأقل أسرع"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "6", "4")
                entries = arrayOf(
                    "كل الأقسام (الافتراضي)",
                    "6 أقسام (أسرع)",
                    "4 أقسام (الأسرع)"
                )
                setDefaultValue("all")
            }
            homeCategory.addPreference(homeRowsPref)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // ترتيب الحلقات في صفحة التفاصيل.
            val episodeOrderPref = ListPreference(ctx).apply {
                key = KEY_EPISODE_ORDER
                title = "ترتيب الحلقات"
                summary = "افتراضي: من الحلقة الأولى"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("as_is", "desc")
                entries = arrayOf(
                    "من الحلقة الأولى (الافتراضي)",
                    "من الأحدث إلى الأقدم"
                )
                setDefaultValue("as_is")
            }
            playbackCategory.addPreference(episodeOrderPref)

            // وسم الصيغة في اسم سيرفر التشغيل، ليُميّز المستخدم بين MP4 وM3U8
            // قبل التشغيل (المشغّل يعرض ExtractorLink.name فقط في قائمة السيرفرات).
            val formatTagPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_FORMAT_TAG
                title = "إظهار صيغة السيرفر في الاسم"
                summary = "يُضيف [M3U8] أو [MP4] بجانب الجودة في اسم الخادم"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(formatTagPref)

            val resetPref = Preference(ctx).apply {
                key = "dvs_reset_prefs"
                title = "إعادة الضبط"
                summary = "يعيد الخيارات أعلاه إلى الافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_SHOW_HOME)
                        remove(KEY_HOME_ROWS)
                        remove(KEY_EPISODE_ORDER)
                        remove(KEY_FORMAT_TAG)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}