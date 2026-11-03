package com.kisskhba.plugin

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
 * إعدادات مصدر «KissKH.ba» وحدها.
 *
 * الموقع يقدّم لكل حلقة سيرفرين مختلفين: `Standard` (vidbasic) و`HD`
 * (megaplay، وهو صاحب اللغات الستّ في `player-payload`)، وقد يضيف روابط
 * تحميل. الخيارات هنا تخصّ هذا الموقع فقط، بملفّ تفضيلاتٍ مستقلّ (بادئة
 * `kkb_`) عن `kisskh.bz` و`kisskh.la`.
 */
class KissKHBASettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = View.generateViewId()
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        const val PREFS_NAME = "KissKHBA"

        const val KEY_SHOW_SUBS = "kkb_show_subs"       // Boolean
        const val KEY_LANG = "kkb_lang_mode"            // all | ar | en | ar_en
        const val KEY_QUALITY = "kkb_quality_mode"      // all | best
        const val KEY_SERVERS = "kkb_server_mode"       // all | hd | standard
        const val KEY_SHOW_DOWNLOADS = "kkb_show_downloads" // Boolean

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            KissKHBASettings(prefs).show(fm, "kkb_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(PREFS_NAME)
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val subs = PreferenceCategory(ctx)
            subs.title = "الترجمات"
            preferenceScreen.addPreference(subs)

            subs.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SHOW_SUBS
                    title = "إظهار ملفات الترجمة"
                    summary = "ترجمات vidbasic المفكوكة + كل لغات megaplay"
                    setDefaultValue(true)
                }
            )

            subs.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_LANG
                    title = "لغات الترجمة المعروضة"
                    summary = "الموقع يوفّر حتى ٦ لغات — يمكن حصر القائمة"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("all", "ar", "en", "ar_en")
                    entries = arrayOf(
                        "كل اللغات (الافتراضي)",
                        "العربية فقط",
                        "الإنجليزية فقط",
                        "العربية والإنجليزية",
                    )
                    setDefaultValue("all")
                }
            )

            val links = PreferenceCategory(ctx)
            links.title = "السيرفرات والروابط"
            preferenceScreen.addPreference(links)

            links.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_SERVERS
                    title = "السيرفرات المعروضة"
                    summary = "HD هو megaplay (أكثر اللغات)، وStandard هو vidbasic"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("all", "hd", "standard")
                    entries = arrayOf(
                        "كل السيرفرات (الافتراضي)",
                        "HD فقط (megaplay)",
                        "Standard فقط (vidbasic)",
                    )
                    setDefaultValue("all")
                }
            )

            links.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_QUALITY
                    title = "الجودات المعروضة"
                    summary = "افتراضيًا تُعرض كل جودةٍ داخل القائمة دون حذف"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("all", "best")
                    entries = arrayOf(
                        "كل الجودات (الافتراضي)",
                        "أعلى جودة فقط",
                    )
                    setDefaultValue("all")
                }
            )

            links.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SHOW_DOWNLOADS
                    title = "إظهار روابط التحميل"
                    summary = "روابط data-kkh-downloads عند توفّرها (وسم DOWNLOAD)"
                    setDefaultValue(true)
                }
            )

            preferenceScreen.addPreference(
                Preference(ctx).apply {
                    key = "kkb_reset_prefs"
                    title = "إعادة الضبط"
                    summary = "يعيد خيارات هذه الإضافة إلى الافتراضي"
                    setOnPreferenceClickListener {
                        prefs.edit().apply {
                            remove(KEY_SHOW_SUBS)
                            remove(KEY_LANG)
                            remove(KEY_QUALITY)
                            remove(KEY_SERVERS)
                            remove(KEY_SHOW_DOWNLOADS)
                        }.apply()
                        Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            )
        }
    }
}
