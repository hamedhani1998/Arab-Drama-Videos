package com.onshort.plugin

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
 * إعدادات مصدر «OnShort». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُخزَّن في ملف التفضيلات نفسه الذي يقرأه [OnShortProvider] مباشرة
 * عند كل بث — بلا تخزين مركزي. الربط عبر `preferenceManager
 * .setSharedPreferencesName(PREFS_NAME)` هو ما يضمن كتابة الاختيار في ذاك الملف
 * بالذات (وإلا لذهب إلى ملف التفضيلات الافتراضي الذي لا يقرأه المصدر).
 *
 * الافتراضيات = سلوك اليوم تماماً: ترتيب «افتراضي (كما هو)» لا يعيد ترتيب
 * شيئاً، وإظهار الرابط التلقائي مفعّل، وأقل جودة معروضة = «كل الجودات».
 * فأي اختيار غير ممسوس يُبثّ نفس الروابط بنفس ترتيبها ونفس عددها.
 */
class OnShortSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يجب أن يطابق ما يمرّره OnShortPlugin للمصدر،
        //   وإلا ذهبت الاختيارات إلى ملف آخر لا يقرأه (سلوك صامت بلا أثر).
        const val PREFS_NAME = "OnShort"

        // مفاتيح هذه الوحدة (سلسلة نصية فريدة—no تعارض مع أي وحدة أخرى).
        const val KEY_QUALITY_ORDER = "os_quality_order"    // "default" | "asc" | "desc"
        const val KEY_SHOW_AUTO = "os_show_auto"           // Boolean — الافتراضي true
        const val KEY_MIN_HEIGHT = "os_min_height"         // "all" | "480" | "360"
        const val KEY_BRIDGE_ENABLED = "os_bridge_enabled"  // Boolean — جسر Mosalsaly، الافتراضي true

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            OnShortSettingsBottomSheet(prefs).show(fm, "os_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ الربط الحاسم: اجعل الورقة تكتب في نفس الملف الذي يقرأه المصدر
            // (الاسم نفسه، والنمط الافتراضي MODE_PRIVATE يطابق ما يمرّره المكوّن).
            // بدونه تُحفظ الاختيارات في الملف الافتراضي وتضيع بصمت.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // ترتيب الجودات عند البث — «افتراضي» يبثّ الروابط بنفس ترتيبها تماماً
            // كما كان (بلا أي تغيير)، و«تصاعدي/تنازلي» يعيدان ترتيبها فقط (فرز مستقر:
            // المتساوية تحتفظ بترتيبها، ولا حذف ولا تكرار).
            val qualityOrderPref = ListPreference(ctx).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "يعيد ترتيب روابط الجودات المعروضة — بلا حذف ولا تكرار"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("default", "asc", "desc")
                entries = arrayOf(
                    "الافتراضي (كما هو)",
                    "من الأقل إلى الأعلى",
                    "من الأعلى إلى الأقل"
                )
                setDefaultValue("default")
            }
            playbackCategory.addPreference(qualityOrderPref)

            // إظهار رابط «Auto · OnShort» (رابط الموقع الأساسي) — الافتراضي مفعّل،
            // أي سلوك اليوم حرفياً. إطفاؤه يُخفيه فقط عندما يكون مفعّلاً؛ لا يمس
            // روابط الجودات الصريحة ولا ناتج «أقل جودة معروضة» (Auto غير مشمول بها).
            val showAutoPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_AUTO
                title = "إظهار الرابط التلقائي (Auto)"
                summary = "رابط الموقع الأساسي — يختفي عند اختيار عرض الجودات فقط"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(showAutoPref)

            // أقل جودة معروضة — «كل الجودات» هو سلوك اليوم بلا تغيير. عند اختيار
            // عتبة، تُخفى المرشحات الأقل منها فقط داخل حلقة المرشحين (.Auto يبقى
            // معرفياً من هذا الفلتر لأن جودته مجهولة).
            val minHeightPref = ListPreference(ctx).apply {
                key = KEY_MIN_HEIGHT
                title = "أقل جودة معروضة"
                summary = "يخفي مرشّحات الجودة الأقل من العتبة المختارة"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "480", "360")
                entries = arrayOf(
                    "كل الجودات (افتراضي)",
                    "من 480p وأعلى",
                    "من 360p وأعلى"
                )
                setDefaultValue("all")
            }
            playbackCategory.addPreference(minHeightPref)

            // جسر Mosalsaly: عند رفض سيرفر OnShort تشغيل منصة نهائيًا
            // (NetShort/ShortMax/GoodShort/DramaBite/StoryReel/VibeShort) يبحث
            // جسرٌ تلقائي بالعنوان في mosalsaly.com ليوفّر روابط التشغيل.
            val bridgePref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_BRIDGE_ENABLED
                title = "جسر تشغيل Mosalsaly"
                summary = "تشغيل المنصات المرفوضة من OnShort عبر Mosalsaly (بحث بالعنوان)"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(bridgePref)

            val resetPref = Preference(ctx).apply {
                key = "os_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_QUALITY_ORDER)
                        remove(KEY_SHOW_AUTO)
                        remove(KEY_MIN_HEIGHT)
                        remove(KEY_BRIDGE_ENABLED)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
