package com.pakistanilive.plugin

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
 * إعدادات مصدر «Pakistanilive» (باكستاني لايف). تُعرض من زر الإعدادات في CloudStream.
 *
 * الافتراضيات = سلوك اليوم تماماً: عرض الترجمة (SRT المطمور في الصفحة) مفعّل،
 * ومسار التشغيل NewPipe + DASH (كما كان)، وكل الجودات، والترتيب الافتراضي.
 *
 * استُعير نمط «مسار التشغيل» من إضافة aryarabia (ARY العربية): NewPipe + DASH
 * محلي، أو روابط HTML المباشرة، أو مستخرج CloudStream المدمج — لأي شبكة منهم
 * يستجيب. عند تغيير المسار تُجرَّب المسارات تلقائياً احتياطياً (الطلب من
 * التقارير: روابط التشغيل لا تعمل على بعض الشبكات لفيديوهات باكستانية).
 */
class PakistaniliveSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يجب أن يطابق ما يمرّره PakistanilivePlugin للمصدر.
        const val PREFS_NAME = "Pakistanilive"

        // مفاتيح هذه الوحدة (سلسلة نصية فريدة — لا تعارض مع أي وحدة أخرى).
        const val KEY_SHOW_SUBS = "pki_show_subs"             // Boolean — الافتراضي true
        const val KEY_PLAYBACK_MODE = "pki_playback_mode"     // "newpipe" | "direct" | "extractor"
        const val KEY_MAX_QUALITY = "pki_max_quality"         // "all" | "high"
        const val KEY_QUALITY_ORDER = "pki_quality_order"     // "default" | "asc" | "desc"

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            PakistaniliveSettingsBottomSheet(prefs).show(fm, "pki_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ الربط الحاسم: اجعل الورقة تكتب في نفس الملف الذي يقرأه المصدر.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val subsCategory = PreferenceCategory(ctx)
            subsCategory.title = "الترجمة"
            preferenceScreen.addPreference(subsCategory)

            // إظهار الترجمة العربية المطمورة في صفحة الحلقة — الافتراضي مفعّل.
            val showSubsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_SUBS
                title = "إظهار الترجمة"
                summary = "عرض الترجمة العربية المرفقة بالحلقة"
                setDefaultValue(true)
            }
            subsCategory.addPreference(showSubsPref)

            // —— خيارات التشغيل (مأخوذة من aryarabia) ——
            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // أسلوب الاستخراج لروابط يوتيوب
            val modePref = ListPreference(ctx).apply {
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
            playbackCategory.addPreference(modePref)

            // الجودات: الكل أم الأعلى فقط
            val qualityPref = ListPreference(ctx).apply {
                key = KEY_MAX_QUALITY
                title = "الجودات"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "high")
                entries = arrayOf("كل الجودات", "الأعلى فقط (أسرع)")
                setDefaultValue("all")
            }
            playbackCategory.addPreference(qualityPref)

            // ترتيب الجودات عند البث — الافتراضي = ترتيب اليوم تماماً؛
            // التصاعدي/التنازلي يعيدان ترتيب روابط التشغيل فقط (بلا حذف/تكرار).
            val orderPref = ListPreference(ctx).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "افتراضي: نفس ترتيب روابط التشغيل كما هي"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("default", "asc", "desc")
                entries = arrayOf(
                    "الافتراضي (كما هو)",
                    "من الأقل إلى الأعلى",
                    "من الأعلى إلى الأقل"
                )
                setDefaultValue("default")
            }
            playbackCategory.addPreference(orderPref)

            val resetPref = Preference(ctx).apply {
                key = "pki_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_SHOW_SUBS)
                        remove(KEY_PLAYBACK_MODE)
                        remove(KEY_MAX_QUALITY)
                        remove(KEY_QUALITY_ORDER)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}