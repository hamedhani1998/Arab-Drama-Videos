package com.huangguodrama.plugin

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
 * إعدادات مصدر «HuangguoDrama». تُعرض من زر الإعدادات في CloudStream.
 *
 * الربط عبر `preferenceManager.setSharedPreferencesName(PREFS_NAME)` يضمن أن تكتب
 * الورقة في ذاك الملف بالذات (وإلا لذهب الاختيار إلى الملف الافتراضي الذي لا
 * يقرأه المصدر) — وهو ما يمرّره [HuangguoDramaPlugin] للمصدر.
 *
 * ★ الافتراضيات = سلوك اليوم تماماً، خياراً خياراً:
 *  - «إظهار معاينات الإعلان» = true  ⇒ عنصر ١٢ يبقى معروضاً بكل معايناته.
 *  - «حد الحلقات المعروضة» = 0 (بلا حد) ⇒ كل الحلقات المتاحة كما هي.
 *  - «الترتيب» = as-is ⇒ نفس ترتيب الموقع تماماً.
 *  - «تأكيد التشغيل» = false ⇒ التشغيل يبدأ مباشرة بلا خطوة إضافية.
 */
class HuangguoDramaSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يجب أن يطابق ما يمرّره HuangguoDramaPlugin للمصدر.
        const val PREFS_NAME = "HuangguoDrama"

        const val KEY_SHOW_PREVIEWS = "hgd_show_previews"   // Boolean الافتراضي true
        const val KEY_EPISODE_LIMIT = "hgd_episode_limit"    // String "0" = بلا حد
        const val KEY_EPISODE_ORDER = "hgd_episode_order"    // "as_is" | "desc"
        const val KEY_CONFIRM_PLAY = "hgd_confirm_play"      // Boolean الافتراضي false

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            HuangguoDramaSettingsBottomSheet(prefs).show(fm, "hgd_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ الربط الحاسم: اجعل الورقة تكتب في نفس الملف الذي يقرأه المصدر.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val listCategory = PreferenceCategory(ctx)
            listCategory.title = "خيارات القائمة"
            preferenceScreen.addPreference(listCategory)

            // عنصر ١٢ وحده صفحة إعلان بلا حلقات: الحلقة ١ + تسع معاينات صامتة
            // مدتها ١٠ ثوانٍ. الافتراضي = إظهارها كما هي الآن، والخيار
            // يسري عليها وحدها ولا يمسّ المسلسلات.
            listCategory.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_PREVIEWS
                title = "معاينات الإعلان"
                summary = "إظهار المقاطع القصيرة (١٠ ثوانٍ) — دراما الإعلان فقط"
                setDefaultValue(true)
            })

            // الموقع يعلنّ عدداً أكبر من حلقاته المتاحة بكثير (٤١ معلنة مقابل ٣
            // متاحة مثلاً). الحدّ يقصّ المعروض فقط، ولا يلمس ما هو متاح أصلاً.
            // الافتراضي = 0 ⇒ بلا قصّ، أي كل الحلقات كما هي.
            listCategory.addPreference(ListPreference(ctx).apply {
                key = KEY_EPISODE_LIMIT
                title = "حد الحلقات المعروضة"
                summary = "اقتراضي: بلا حد — كل الحلقات المتاحة"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("0", "3", "5", "10", "20")
                entries = arrayOf(
                    "بلا حد (الافتراضي)",
                    "أول ٣ حلقات",
                    "أول ٥ حلقات",
                    "أول ١٠ حلقات",
                    "أول ٢٠ حلقة"
                )
                setDefaultValue("0")
            })

            val epCategory = PreferenceCategory(ctx)
            epCategory.title = "خيارات الحلقات"
            preferenceScreen.addPreference(epCategory)

            // الترتيب = إعادة ترتيب فقط بلا حذف ولا تكرار؛ الافتراضي = ترتيب
            // الموقع تماماً.
            epCategory.addPreference(ListPreference(ctx).apply {
                key = KEY_EPISODE_ORDER
                title = "ترتيب الحلقات"
                summary = "افتراضي: كما هي مرتّبة في الموقع"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("as_is", "desc")
                entries = arrayOf(
                    "كما هي في الموقع (الافتراضي)",
                    "من الأحدث إلى الأقدم"
                )
                setDefaultValue("as_is")
            })

            val playCategory = PreferenceCategory(ctx)
            playCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playCategory)

            playCategory.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_CONFIRM_PLAY
                title = "تأكيد قبل التشغيل"
                summary = "إظهار خطوة تأكيد قبل فتح الفيديو"
                setDefaultValue(false)
            })

            playCategory.addPreference(Preference(ctx).apply {
                key = "hgd_open_site"
                title = "فتح الموقع في المتصفح"
                summary = "تصفّح huangguodrama.ai/ar"
                setOnPreferenceClickListener {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://huangguodrama.ai/ar/")
                            )
                        )
                    }
                    true
                }
            })

            val resetPref = Preference(ctx).apply {
                key = "hgd_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_SHOW_PREVIEWS)
                        remove(KEY_EPISODE_LIMIT)
                        remove(KEY_EPISODE_ORDER)
                        remove(KEY_CONFIRM_PLAY)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
