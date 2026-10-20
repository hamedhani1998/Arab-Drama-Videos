package com.shortdramahub.plugin

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
 * إعدادات مصدر «شورت دراما هب» — ورقة سفلية بنفس نمط إضافة «دراما قصيرة».
 *
 * ## لماذا هذه الخيارات بالذات
 *
 * الموقع فهرسُ أفلامٍ قصيرة **مستقلة** (لا مسلسلات ولا حلقات ولا مواسم)،
 * وكل فيلم على يوتيوب، ولا مرايا ولا جودات مُسمّاة من الموقع. فهذه الورقة
 * تعرض ما له معنى فعلاً هنا، ولا تعرض ما لا معنى له:
 *
 * - **لا قسم «قنوات إضافية»**: الموقع لا يملك قنوات، بل فهرساً واحداً.
 * - **لا قسم «ترتيب/تسمية الأقسام»**: الأقسام الثلاثة ثابتة من الخادم
 *   (Movies/Drama/Action)، وروابط التنقّل العربية في الموقع معطوبة أصلاً.
 * - **لا اختيار لغة ترجمة**: يوتيوب ينشر على هذه المقاطع مساراً تلقائياً
 *   واحداً (`ar`)، فقائمةُ لغاتٍ لن يكون فيها إلا عنصر واحد. مفتاحُ تشغيل/
 *   إيقاف هو الصدق هنا.
 *
 * ★ ملف التفضيلات (`SHORTDRAMAHUB`) وبادئة المفاتيح (`sdh_`) مستقلّان عن
 *   «دراما قصيرة» (`SHORTDRAMA` / `sd_`) — وإلا لاطّلقت الإضافتان على نفس
 *   الإعدادات فتغيّر سلوك إحداهما بلا قصد.
 */
class ShortDramaHubSettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        // ★ اسم ملف التفضيلات — يطابق ما يمرّره ShortDramaHubPlugin للمصدر.
        const val PREFS_NAME = "SHORTDRAMAHUB"
        const val KEY_PLAYBACK_MODE = "sdh_playback_mode"  // "newpipe" | "direct" | "extractor"
        const val KEY_MAX_QUALITY = "sdh_max_quality"      // "all" | "high"
        const val KEY_QUALITY_ORDER = "sdh_quality_order"  // "default" | "asc" | "desc"
        const val KEY_REDIRECT = "sdh_use_redirect"        // Boolean بديل النطاق
        const val KEY_SUBTITLES = "sdh_subtitles"          // Boolean ترجمة تلقائية

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            ShortDramaHubSettings(prefs).show(fm, "shortdramahub_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ اجعل الورقة تكتب في ملف التفضيلات نفسه الذي يقرأه
            // ShortDramaHubProvider («SHORTDRAMAHUB»). بدونه تذهب
            // الاختيارات إلى ملف AndroidX الافتراضي الذي لا يقرأه المصدر —
            // إعدادات تُحفظ بلا أي أثر.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            // ————— عن المصدر —————
            val intro = PreferenceCategory(ctx)
            intro.title = "عن المصدر"
            preferenceScreen.addPreference(intro)

            intro.addPreference(
                Preference(ctx).apply {
                    title = "أفلام قصيرة مدبلجة — شورت دراما هب"
                    summary = "يعرض فهرس موقع shortdramahub.world: أربعة صفوف " +
                        "(أحدث الإضافات، أفلام، دراما، أكشن) والبحث في الموقع كاملاً. " +
                        "كل فيلم يعمل بجودةً كاملة عبر مسار يوتيوب الداخلي."
                    setSelectable(false)
                }
            )

            // ————— خيارات التشغيل —————
            val playback = PreferenceCategory(ctx)
            playback.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playback)

            playback.addPreference(
                ListPreference(ctx).apply {
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
            )

            playback.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_MAX_QUALITY
                    title = "الجودات"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("all", "high")
                    entries = arrayOf("كل الجودات", "الأعلى فقط (أسرع)")
                    setDefaultValue("all")
                }
            )

            playback.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_QUALITY_ORDER
                    title = "ترتيب الجودات"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("default", "asc", "desc")
                    entries = arrayOf(
                        "الافتراضي (كما هو)",
                        "من الأقل إلى الأعلى",
                        "من الأعلى إلى الأقل"
                    )
                    setDefaultValue("default")
                }
            )

            playback.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_REDIRECT
                    title = "نطاق بديل (redirector)"
                    summary = "قد يساعد على الشبكات التي تحجب مضيف rr*—sn-*"
                    setDefaultValue(false)
                }
            )

            // ————— الترجمة —————
            val subs = PreferenceCategory(ctx)
            subs.title = "الترجمة"
            preferenceScreen.addPreference(subs)

            subs.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SUBTITLES
                    title = "ترجمة تلقائية"
                    summary = "يوتيوب يولّد على هذه الأفلام ترجمةً عربية تلقائية. " +
                        "أوقفها إن ظهرت فارغة أو غير مرغوبة."
                    setDefaultValue(true)
                }
            )

            // ————— إعادة الضبط —————
            preferenceScreen.addPreference(
                Preference(ctx).apply {
                    key = "sdh_reset_prefs"
                    title = "إعادة الضبط الافتراضي"
                    summary = "يعيد كل الخيارات أعلاه للافتراضي"
                    setOnPreferenceClickListener {
                        prefs.edit().apply {
                            remove(KEY_PLAYBACK_MODE)
                            remove(KEY_MAX_QUALITY)
                            remove(KEY_QUALITY_ORDER)
                            remove(KEY_REDIRECT)
                            remove(KEY_SUBTITLES)
                        }.apply()
                        Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            )
        }
    }
}
