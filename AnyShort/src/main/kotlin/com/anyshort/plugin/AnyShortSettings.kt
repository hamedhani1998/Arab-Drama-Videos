package com.anyshort.plugin

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
 * إعدادات مصدر «AnyShort». تُعرض من زر الإعدادات في CloudStream وتُحفظ في ملف
 * تفضيلات خاص بالوحدة (تنميط RimShort).
 *
 * الموقع بثلاث عائلات مصادر: `netshort` (mp4 متعدّد الجودات + ترجمات VTT
 * منفصلة)، `pinedrama` (mp4 بترجمة مدموجة في الصورة — لكل لغة ملفّ)، و`reelshort`
 * (قائمة تكيّفية M3U8). فما ينفع المستخدم فعلاً: إظهار الواجهة أو إخفاؤها، عدد
 * أقسامها، ترتيب الحلقات، ووسم الصيغة في اسم السيرفر (يُميّز MP4 من M3U8 قبل
 * التشغيل)، وإظهار ملفّات الترجمة.
 */
class AnyShortSettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        /** اسم ملف التفضيلات — يطابق ما يمرّره [AnyShortPlugin]. */
        const val PREFS_NAME = "AnyShort"

        // مفاتيح هذه الوحدة (بادئة فريدة — لا تعارض مع أي وحدة أخرى).
        const val KEY_SHOW_HOME = "ash_show_home"        // Boolean — إظهار الواجهة الرئيسية
        const val KEY_HOME_ROWS = "ash_home_rows"        // "all" | "6" | "4" — عدد أقسام الرئيسية
        const val KEY_EPISODE_ORDER = "ash_episode_order" // "as_is" | "desc" — ترتيب الحلقات
        const val KEY_FORMAT_TAG = "ash_format_tag"      // Boolean — وسم الصيغة في اسم السيرفر
        const val KEY_SHOW_SUBS = "ash_show_subs"        // Boolean — إظهار ملفّات الترجمة
        const val KEY_PROXY = "ash_proxy"                // Boolean — وسيط التشغيل المحلّي

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            AnyShortSettings(prefs).show(fm, "ash_settings")
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

            val showHomePref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_HOME
                title = "إظهار الواجهة الرئيسية"
                summary = "أقسام الموقع: المختار، الأحدث، الأكثر رواجًا وأقسام المحرّرين"
                setDefaultValue(true)
            }
            homeCategory.addPreference(showHomePref)

            // كل قسم صفٌّ تنتظره الواجهة قبل أول رسم — تقليله يسرّع الفتح.
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
            // قبل التشغيل (المشغّل يعرض ExtractorLink.name فقط). المصدر بثلاث
            // عائلات: netshort وpinedrama ملفّات MP4، وreelshort قوائم M3U8.
            val formatTagPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_FORMAT_TAG
                title = "إظهار صيغة السيرفر في الاسم"
                summary = "يُضيف [MP4] أو [M3U8] بجانب الجودة في اسم الخادم"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(formatTagPref)

            // الترجمات: أعمال netshort تحمل ملفّات VTT منفصلة (تجلبها الواجهة
            // مع الروابط بلا كلفة تُذكر)، وأعمال pinedrama تترجم داخل الصورة
            // فلا تردّ ملفّات — والنوع يظهر من وسم الصيغة في الاسم.
            val showSubsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_SUBS
                title = "إظهار ملفّات الترجمة"
                summary = "ترجمات netshort المنفصلة — pinedrama مدموجة في الصورة أصلاً"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(showSubsPref)

            // الوسيط المحلّي: يُعيد المحاولة على اتصالٍ طازج حين يتعثّر طور
            // الرأس (قِيس 2026-10-21: ٢/٥ طلبات tiktokcdn تُصفَّر بلا رؤوس،
            // و v-mps بلغ 21.8 ث لتقرير الحالة)، ثم يُمرّر الجسم حيّاً.
            val proxyPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_PROXY
                title = "وسيط التشغيل المحلّي"
                summary = "يعالج التأخّر والتقطّع: إعادة محاولة عند تعثّر الاتصال. أوقفه لو ظهرت مشكلة"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(proxyPref)

            val resetPref = Preference(ctx).apply {
                key = "ash_reset_prefs"
                title = "إعادة الضبط"
                summary = "يعيد الخيارات أعلاه إلى الافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_SHOW_HOME)
                        remove(KEY_HOME_ROWS)
                        remove(KEY_EPISODE_ORDER)
                        remove(KEY_FORMAT_TAG)
                        remove(KEY_SHOW_SUBS)
                        remove(KEY_PROXY)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
