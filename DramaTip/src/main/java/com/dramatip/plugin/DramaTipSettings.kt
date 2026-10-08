package com.dramatip.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «DramaTip». الوحدة بلا خيارات قابلة للتعديل حاليًا — الصفوف
 * الرئيسية (المنصّات) ووسوم الصيغ ثابتة. تبقى الورقة للإتساق مع باقي الوحدات
 * وتعرض رسالة واحدة فقط («لا خيارات»).
 */
class DramaTipSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        const val PREFS_NAME = "DramaTip"

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DramaTipSettingsBottomSheet(prefs).show(fm, "dt_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.setSharedPreferencesName(PREFS_NAME)
            val screen = preferenceScreen
            // الوحدة بلا خيارات قابلة للتعديل.
            val info = Preference(requireContext()).apply {
                key = "dt_no_options"
                title = "لا توجد خيارات متاحة"
                summary = "صفوف المنصّات ووسوم الصيغ ثابتة"
                isSelectable = false
            }
            screen.addPreference(info)
        }
    }
}