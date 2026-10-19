package com.dramaglance.plugin

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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «DramaGlance». تُعرض من زر الإعدادات في CloudStream.
 *
 * الإعدادات هنا تشخيصية بحتة — مقصود. الموقع معاينة مجانية فقط، ولطالما كان هذا
 * محطّ سؤال متكرّر («ليش الحلقات ناقصة؟»)، فأعطيته المستخدم مفتاحاً يوثّق ما جاء
 * من الموقع فعلاً، وبطاقة تشرح الحدود بدل أن نُخفيها.
 *
 * لماذا لا يوجد خيار «الجودات» ولا «الترجمة»؟ لأن الموقع لا ينشر أصلاً:
 *  - ملف المعاينة قائمة واحدة بلا #EXT-X-STREAM-INF (لا master ولا جودات) وبلا
 *    #EXT-X-MEDIA (لا مسارات صوت ولا ترجمة) وبلا #EXT-X-KEY (غير مشفّر) — فحص
 *    فعلي على عدة مسلسلات.
 *  - لا يوجد أي ملف .vtt/.srt على www أو video أو cdn، ولا رابط ترجمة في
 *    صفحة التفاصيل ولا في player.js.
 * أي خيار هنا يوحي بإمكانية غير موجودة، فلا داعي له.
 *
 * الربط: كل مفتاح يُخزَّن في ملف التفضيلات «DramaGlance» (نفس الاسم الذي يمرّره
 * DramaGlancePlugin للمصدر) عبر `preferenceManager.setSharedPreferencesName`
 * — بدونه تذهب الاختيارات إلى الملف الافتراضي الذي لا يقرأه المصدر (صمت).
 */
class DramaGlanceSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ يجب أن يطابق ما يمرّره DramaGlancePlugin للمصدر، وإلا ذهبت الاختيارات
        //   إلى ملف آخر لا يقرأه (سلوك صامت بلا أثر).
        const val PREFS_NAME = "DramaGlance"

        // مفاتيح هذه الوحدة (بادئة dg_ فريدة — لا تعارض مع أي وحدة أخرى).
        const val KEY_DIAGNOSTICS = "dg_diagnostics" // Boolean — الافتراضي false

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DramaGlanceSettingsBottomSheet(prefs).show(fm, "dg_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ الربط الحاسم: اجعل الورقة تكتب في نفس الملف الذي يقرأه المصدر.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val diagCategory = PreferenceCategory(ctx)
            diagCategory.title = "تشخيص المصدر"
            preferenceScreen.addPreference(diagCategory)

            // سجلّ الفحص في logcat: عنوان المعاينة وعدد حلقاتها لكل مسلسل تفتحه.
            // افتراضياً مطفأ (المصدر صامت عدا الأخطاء).
            val diagPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_DIAGNOSTICS
                title = "سجلّ الفحص (Logcat)"
                summary = "يكتب في السجلّ رابط المعاينة وعدد حلقاتها لكل مسلسل تفتحه"
                setDefaultValue(false)
            }
            diagCategory.addPreference(diagPref)

            // بطاقة الحدود الحقيقية — لا تفعل شيئاً سوى عرض ما بناه الفحص.
            val limitsPref = Preference(ctx).apply {
                key = "dg_limits"
                title = "حدود هذا الموقع"
                summary = "اضغط لعرض ما يوفّره الموقع فعلياً"
                setOnPreferenceClickListener {
                    Toast.makeText(ctx, LIMITS, Toast.LENGTH_LONG).show()
                    true
                }
            }
            preferenceScreen.addPreference(limitsPref)

            val resetPref = Preference(ctx).apply {
                key = "dg_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().remove(KEY_DIAGNOSTICS).apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }

        companion object {
            val LIMITS =
                "المعاينة مجانية فقط: ملف m3u8 واحد فيه أول 8–16 حلقة من أصل 45–100+ حلقة، " +
                    "والباقي عبر تطبيق DramaBox/ShortMax. لا توجد حلقات منفردة ولا سلسلة كاملة " +
                    "من الموقع. ولا جودات متعددة ولا ملفات ترجمة: كل المقاطع بجودة واحدة " +
                    "وبدون مسارات ترجمة. (فحص فعلي 2026-09)"
        }
    }
}
