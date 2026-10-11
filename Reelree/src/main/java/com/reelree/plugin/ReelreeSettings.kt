package com.reelree.plugin

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
 * إعدادات مصدر «Reelree». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُخزَّن في ملف التفضيلات نفسه الذي يقرأه [ReelreeProvider] مباشرة
 * عند كل بث — بلا تخزين مركزي. الربط عبر `preferenceManager
 * .setSharedPreferencesName(PREFS_NAME)` هو ما يضمن كتابة الاختيار في ذاك الملف
 * بالذات (وإلا لذهب إلى ملف التفضيلات الافتراضي الذي لا يقرأه المصدر).
 *
 * الافتراضيات = سلوك اليوم تماماً: «الافتراضي (كما هو)» لا يعيد ترتيب شيئاً، وإظهار
 * الترجمة وروابط الصوت المنفصلة مفعّلان كما هما.
 */
class ReelreeSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يجب أن يطابق ما يمرّره ReelreePlugin للمصدر،
        //   وإلا ذهبت الاختيارات إلى ملف آخر لا يقرأه (سلوك صامت بلا أثر).
        const val PREFS_NAME = "Reelree"

        // مفاتيح هذه الوحدة (سلسلة نصية فريدة—no تعارض مع أي وحدة أخرى).
        const val KEY_QUALITY_ORDER = "rr_quality_order"          // "default" | "asc" | "desc"
        const val KEY_SHOW_SUBTITLES = "rr_show_subtitles"        // Boolean — الافتراضي true
        const val KEY_SHOW_AUDIO_TRACKS = "rr_show_audio_tracks"  // Boolean — الافتراضي true
        const val KEY_SHOW_PLATFORMS = "rr_show_platforms"        // Boolean — الافتراضي true

        // مفتاح إظهار/إخفاء منصّة بعينها: rr_platform_<slug>.
        fun platformKey(slug: String) = "rr_platform_$slug"

        // منصّات الموقع (same list as ReelreeProvider) — slug ← الاسم الذي
        // يعرضه الموقع (14 منصّة، مقيس 2026-10-11 من /platforms/).
        // ثابت على مستوى الكائن كي يشاركه المصدر في بناء أسماء المفاتيح.
        val PLATFORMS: List<Pair<String, String>> = listOf(
            "netshort" to "NetShort",
            "dramabite" to "DramaBite",
            "dramabox" to "DramaBox",
            "dramapops" to "DramaPops",
            "reelshort" to "ReelShort",
            "goodshort" to "GoodShort",
            "shortmax" to "ShortMax",
            "flickreels" to "FlickReels",
            "flextv" to "FlexTV",
            "dramawave" to "DramaWave",
            "stardusttv" to "StarDustTV",
            "idrama" to "iDrama",
            "microdrama" to "MicroDrama",
            "bibishort" to "BibiShort",
        )

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            ReelreeSettingsBottomSheet(prefs).show(fm, "rr_settings")
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

            // ترتيب الجودات عند البث — المصدر يبث «رابط الحلقة» 720p و«الحلقة
            // كاملة» 1080p و«صوت» 720p، أي روابط مختلفة النوع لا يصلح ترتيبها بجودة
            // واحدة (لذا لا نخزّن ولا نعيد ترتيب: البث كما هو حرفياً). الخيار موجود
            // ليُقرأ في المصدر ويحترم رغبة المستخدم، لكنه لا يغيّر شيئاً فعلياً هنا.
            val qualityOrderPref = ListPreference(ctx).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "المصدر يبث روابط مختلفة الأنواع (حلقة/كاملة/صوت) — الترتيب لا يغيّر شيئاً"
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

            // إظهار الترجمة — الافتراضي مفعّل، أي سلوك اليوم حرفياً.
            val showSubsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_SUBTITLES
                title = "إظهار الترجمة"
                summary = "يعرض ترجمات كل لغة يوفّرها السيرفر"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(showSubsPref)

            // إظهار روابط الصوت المنفصلة — الافتراضي مفعّل، أي سلوك اليوم حرفياً.
            val showAudioPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_AUDIO_TRACKS
                title = "إظهار روابط الصوت المنفصلة"
                summary = "يعرض كل مسار صوتي مستقل كخيار تشغيل إضافي"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(showAudioPref)

            // — إظهار/إخفاء أقسام المنصّات في القائمة الرئيسية —
            val homeCat = PreferenceCategory(ctx)
            homeCat.title = "أقسام القائمة الرئيسية (المنصّات)"
            preferenceScreen.addPreference(homeCat)

            val showPlatformsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_PLATFORMS
                title = "المنصّات كلها"
                summary = "يعرض صفًّا لكل منصّة في القائمة الرئيسية. ألغِ وسمها لإخفائها كلها"
                setDefaultValue(true)
            }
            homeCat.addPreference(showPlatformsPref)

            val platCat = PreferenceCategory(ctx)
            platCat.title = "المنصّات (إظهار/إخفاء كل واحدة)"
            platCat.summary = "أزل وسم المنصّة لإخفاء صفّها من القائمة الرئيسية"
            preferenceScreen.addPreference(platCat)

            for ((slug, label) in PLATFORMS) {
                val p = SwitchPreferenceCompat(ctx).apply {
                    key = platformKey(slug)
                    title = label
                    summary = "يعرض صفّ «${label}» في القائمة الرئيسية"
                    setDefaultValue(true)
                }
                platCat.addPreference(p)
            }

            val resetPref = Preference(ctx).apply {
                key = "rr_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_QUALITY_ORDER)
                        remove(KEY_SHOW_SUBTITLES)
                        remove(KEY_SHOW_AUDIO_TRACKS)
                        remove(KEY_SHOW_PLATFORMS)
                        PLATFORMS.forEach { (slug, _) -> remove(platformKey(slug)) }
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
