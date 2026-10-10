package com.arabtools.plugin

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * محرّك تعديلات «أدوات عربية».
 *
 * الفكرة: `load(context)` في الإضافة يمرّر سياق التطبيق، ومنه نصل إلى
 * `Application` فنُسجّل مستمع دورة حياة لكل الشاشات — وكل هذا **واجهة عامة
 * صريحة** لا انعكاس ولا صنف داخلي مخفي (بخلاف ما تفعله CSM عبر
 * `ActivityThread`، وهو محجوب أصلاً في `targetSdk` الحديث).
 *
 * عناصر التطبيق نجدها بالبحث في شجرة العرض: نوع حقيقي (`BottomNavigationView`)
 * أو اسم معرّف وقت التشغيل (`getIdentifier`) — لا اعتماد على ترتيب العرض ولا
 * على أرقام `R.id` وقت الترجمة، فيصمد التعديل عبر تحديثات التطبيق.
 *
 * ### قواعد السلامة (غير قابلة للتفاوض)
 * 1. كل تعديل داخل [safe] — لا يخرج استثناء أبداً إلى التطبيق.
 * 2. إن لم يُعثر على العنصر: لا شيء يحدث (تجاهل صامت، لا تعطيل).
 * 3. لا نلمس شيئاً لم يطلبه المستخدم، وإذا ألغى الطلب نُرجع ما غيّرناه مرة واحدة.
 * 4. [enabled] مفتاح رئيسي + وضع آمن، والاثنان يُطفآن كل شيء فوراً.
 * 5. [guardCrashLoop]: ثلاث شاشات افتتاح متقاربة ⇒ يُطفأ المفتاح الرئيسي تلقائياً.
 */
class ArabToolsMods(
    private val app: Application,
    private val prefs: SharedPreferences
) : Application.ActivityLifecycleCallbacks {

    /** القيم الأصلية للتطبيق — نحفظها قبل أول تلاعب حتى يمكن الإرجاع. */
    private class Originals {
        var nightMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        var statusColor: Int? = null
        var navColor: Int? = null
        var orientation: Int? = null
    }

    /** عنصر تبويب كما كان في الشريط قبل أي تعديل — للإرجاع كما كان. */
    private data class NavItem(val id: Int, val title: CharSequence?, val icon: Drawable?)

    private val handler = Handler(Looper.getMainLooper())
    private val originals = Originals()

    private var probeView: View? = null
    private var probeListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var lastApplyAt = 0L

    private var originalNavItems: List<NavItem>? = null
    private var lastTabsSignature: String? = null
    private var navTouched = false

    private var statusAppliedTarget: Activity? = null
    private var statusHidden = false

    private var countedThisProcess = false
    private var stableToken = 0

    init {
        safe { originals.nightMode = AppCompatDelegate.getDefaultNightMode() }
    }

    // ------------------------------------------------------------------ أدوات

    /** يشغّل [block] ولا يسمح لأي خطأ بالخروج — التعديل يسقط بصمت لا يُعطّل التطبيق. */
    private inline fun safe(block: () -> Unit) {
        runCatching(block)
    }

    /** هل التعديلات مفعّلة الآن؟ (المفتاح الرئيسي مفتوح والوضع الآمن مغلق) */
    private fun enabled(): Boolean =
        prefs.getBoolean(ArabToolsPrefs.MASTER, true) &&
            !prefs.getBoolean(ArabToolsPrefs.SAFE_MODE, false)

    /** يبحث عن كل العروض التي تحقق [predicate] داخل شجرة [root] (بحدّ أقصى للعرض). */
    private fun findViews(root: View?, predicate: (View) -> Boolean): List<View> {
        val found = ArrayList<View>()
        var visited = 0
        fun walk(view: View?) {
            if (view == null || visited > 4000) return
            visited++
            safe { if (predicate(view)) found.add(view) }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return found
    }

    private fun idOf(resources: Resources?, name: String): Int =
        if (resources == null) 0
        else runCatching { resources.getIdentifier(name, "id", ArabToolsPrefs.APP_PACKAGE) }.getOrDefault(0)

    private fun colorOf(value: String?): Int? = when (value) {
        "black" -> Color.parseColor("#FF000000")
        "dark" -> Color.parseColor("#FF212121")
        "blue" -> Color.parseColor("#FF0D47A1")
        "purple" -> Color.parseColor("#FF4A148C")
        "red" -> Color.parseColor("#FFB71C1C")
        else -> null
    }

    // ------------------------------------------------------- دورة حياة الشاشات

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        safe { guardCrashLoop(activity) }
    }

    override fun onActivityResumed(activity: Activity) {
        safe {
            attachProbe(activity)
            lastApplyAt = 0L
            applyAll(activity)
            markStable(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        safe {
            if (probeView === activity.window?.decorView) detachProbe()
            if (statusAppliedTarget === activity) {
                statusAppliedTarget = null
                statusHidden = false
            }
            originals.orientation = null
        }
    }

    /** يُلغى التسجيل عند إلغاء تحميل الإضافة. */
    fun detach() {
        safe { app.unregisterActivityLifecycleCallbacks(this) }
        safe { detachProbe() }
        safe { handler.removeCallbacksAndMessages(null) }
    }

    // ------------------------------------------------------------ محرّك التطبيق

    /**
     * نطبّق عند كل «رسم» للشجرة لا عند كل نداء: التبويب أو المشغّل يظهران
     * بإعادة رسم، فيكفينا مستمع واحد بدل استقصاء دوري. التطبيق مرقّم بزمن
     * (300ms) حتى لا نُثقل الواجهة عند التمرير.
     */
    private fun attachProbe(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (probeView === decor && probeListener != null) return
        detachProbe()
        val listener = ViewTreeObserver.OnGlobalLayoutListener { safe { applyAll(activity) } }
        decor.viewTreeObserver?.addOnGlobalLayoutListener(listener)
        probeView = decor
        probeListener = listener
    }

    private fun detachProbe() {
        val view = probeView
        val listener = probeListener
        probeView = null
        probeListener = null
        if (view != null && listener != null) safe { view.viewTreeObserver?.removeOnGlobalLayoutListener(listener) }
    }

    private fun applyAll(activity: Activity) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastApplyAt < 300L) return
        lastApplyAt = now
        val on = enabled()
        applyNightMode(on)
        applyNavTabs(activity, on)
        applySystemBars(activity, on)
        applyPlayerOrientation(activity, on)
    }

    // -------------------------------------------------------------- (٢) المشغّل

    /** هل هذه الشاشة (أو أحد أبنائها) شاشة تشغيل؟ نقارن أسماء الأصناف كنصوص فلا نستوردها. */
    private fun isPlayerVisible(activity: Activity): Boolean {
        if (activity.javaClass.name.contains(".ui.player.")) return true
        val host = activity as? FragmentActivity ?: return false
        return hasPlayerFragment(host.supportFragmentManager.fragments)
    }

    private fun hasPlayerFragment(fragments: List<Fragment>): Boolean {
        for (fragment in fragments) {
            if (fragment.javaClass.name.contains(".ui.player.")) return true
            if (hasPlayerFragment(fragment.childFragmentManager.fragments)) return true
        }
        return false
    }

    private fun applyPlayerOrientation(activity: Activity, on: Boolean) {
        val wantsLandscape = on &&
            prefs.getBoolean(ArabToolsPrefs.PLAYER_LANDSCAPE, false) &&
            isPlayerVisible(activity)

        val current = activity.requestedOrientation
        if (!wantsLandscape) {
            // لم يطلبها المستخدم: نُرجع الاتجاه الأصلي مرة واحدة إن كنا غيّرناه، ثم لا نلمس شيئاً.
            val original = originals.orientation
            originals.orientation = null
            if (original != null && current != original) safe { activity.requestedOrientation = original }
            return
        }
        if (originals.orientation == null) originals.orientation = current
        if (current != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            safe { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        }
    }

    // --------------------------------------------------------------- (٣) المظهر

    private fun applyNightMode(on: Boolean) {
        val mode = if (!on) {
            originals.nightMode
        } else when (prefs.getString(ArabToolsPrefs.NIGHT_MODE, "auto")) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> originals.nightMode
        }
        // لا نُغيّر الوضع إلا عند اختلافه فعلاً — وإلا أعدنا إنشاء الشاشة في حلقة لا نهائية.
        safe {
            if (AppCompatDelegate.getDefaultNightMode() != mode) {
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars(activity: Activity, on: Boolean) {
        val window = activity.window ?: return

        val wantHide = on && prefs.getBoolean(ArabToolsPrefs.HIDE_STATUS, false)
        val needsHide = wantHide && (statusAppliedTarget !== activity || !statusHidden)
        val needsShow = !wantHide && statusHidden
        if (needsHide || needsShow) {
            statusHidden = wantHide
            statusAppliedTarget = if (wantHide) activity else null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController
                safe {
                    if (wantHide) controller?.hide(WindowInsets.Type.statusBars())
                    else controller?.show(WindowInsets.Type.statusBars())
                }
            } else {
                val decor = window.decorView
                val flags = decor.systemUiVisibility
                safe {
                    decor.systemUiVisibility = if (wantHide) {
                        flags or View.SYSTEM_UI_FLAG_FULLSCREEN
                    } else {
                        flags and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
                    }
                }
            }
        }

        // ألوان الأشرطة: أندرويد 15 (API 35) صار يتجاهلها في الوضع الشامل، فلا نلمسها هناك.
        if (Build.VERSION.SDK_INT >= 35) return

        val statusColor = if (on) colorOf(prefs.getString(ArabToolsPrefs.STATUS_COLOR, "default")) else null
        if (statusColor != null) {
            if (originals.statusColor == null) originals.statusColor = window.statusBarColor
            safe { window.statusBarColor = statusColor }
        } else {
            originals.statusColor?.let { original ->
                originals.statusColor = null
                safe { window.statusBarColor = original }
            }
        }

        val navColor = if (on) colorOf(prefs.getString(ArabToolsPrefs.NAV_COLOR, "default")) else null
        if (navColor != null) {
            if (originals.navColor == null) originals.navColor = window.navigationBarColor
            safe { window.navigationBarColor = navColor }
        } else {
            originals.navColor?.let { original ->
                originals.navColor = null
                safe { window.navigationBarColor = original }
            }
        }
    }

    // --------------------------------------------------- (٤) الرئيسية والتنقل

    private fun applyNavTabs(activity: Activity, on: Boolean) {
        val hideBar = on && prefs.getBoolean(ArabToolsPrefs.NAV_HIDE_BAR, false)
        val hidden = if (on) hiddenTabIds(activity.resources) else emptySet()
        val wantsChange = hideBar || hidden.isNotEmpty()

        // لا نمرّ على شجرة العرض إطلاقاً إن لم يطلب المستخدم أي تغيير ولم نكن قد
        // غيّرنا الشريط سابقاً — فلا نُثقل رسم الشاشة بلا سبب.
        if (!wantsChange && !navTouched) return

        val bars = findViews(activity.window?.decorView) { it is BottomNavigationView }
            .filterIsInstance<BottomNavigationView>()
        if (bars.isEmpty()) return

        val signature = hidden.sorted().joinToString(",")
        val changed = signature != lastTabsSignature

        for (bar in bars) {
            val wanted = if (hideBar) View.GONE else View.VISIBLE
            if (bar.visibility != wanted) safe { bar.visibility = wanted }
            if (changed) safe { rebuildMenu(bar, hidden) }
        }
        if (changed) lastTabsSignature = signature
        navTouched = wantsChange
    }

    private fun hiddenTabIds(resources: Resources?): Set<Int> {
        if (resources == null) return emptySet()
        val hidden = HashSet<Int>()
        for (tab in ArabToolsPrefs.TABS) {
            if (!prefs.getBoolean(tab.key, false)) continue
            val id = idOf(resources, tab.resName)
            if (id != 0) hidden.add(id)
        }
        return hidden
    }

    /**
     * إعادة بناء تبويبات الشريط من نسخة «الأصل» المحفوظة عند أول رؤية، مع حذف
     * المخفيّ منها. الحذف عبر القائمة هو الأسلوب المتّبع في Material (لا تكفي
     * `isVisible`)، والإرجاع ممكن لأننا نحفظ العنوان والأيقونة والترتيب.
     */
    private fun rebuildMenu(bar: BottomNavigationView, hidden: Set<Int>) {
        val menu = bar.menu

        if (originalNavItems == null) {
            val saved = ArrayList<NavItem>()
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i) ?: continue
                saved.add(NavItem(item.itemId, item.title, item.icon))
            }
            if (saved.isNotEmpty()) originalNavItems = saved
        }
        val original = originalNavItems ?: return

        val wanted = original.filter { it.id !in hidden }
        val present = HashSet<Int>()
        for (i in 0 until menu.size()) present.add(menu.getItem(i)?.itemId ?: 0)
        if (present == wanted.map { it.id }.toSet()) return

        val selected = bar.selectedItemId
        menu.clear()
        wanted.forEachIndexed { index, item ->
            val added = menu.add(0, item.id, index, item.title ?: "")
            added.icon = item.icon
        }
        if (wanted.any { it.id == selected }) safe { bar.selectedItemId = selected }
    }

    // --------------------------------------------- حماية من تكرار توقّف التطبيق

    /**
     * لو كان التطبيق يُفتح ثم يسقط، فكل إقلاع جديد يُسجّل بصمة. ثلاث بصمات خلال
     * دقيقة ⇒ المفتاح الرئيسي يُغلق وحده، فلا يمكن أن نحبس المستخدم في حلقة
     * انهيار بسبب إضافة أدوات. تُصفَّر البصمات بعد 20 ثانية من استقرار شاشة.
     */
    private fun guardCrashLoop(activity: Activity) {
        if (countedThisProcess) return
        countedThisProcess = true

        val now = System.currentTimeMillis()
        val last = prefs.getLong(ArabToolsPrefs.BOOT_TS, 0L)
        val count = if (now - last in 1..60_000L) prefs.getInt(ArabToolsPrefs.BOOT_COUNT, 0) + 1 else 1

        val editor = prefs.edit()
            .putLong(ArabToolsPrefs.BOOT_TS, now)
            .putInt(ArabToolsPrefs.BOOT_COUNT, count)
        if (count >= 3) {
            editor.putBoolean(ArabToolsPrefs.MASTER, false)
                .putInt(ArabToolsPrefs.BOOT_COUNT, 0)
                .putBoolean(ArabToolsPrefs.WAS_DISABLED, true)
        }
        editor.apply()

        if (prefs.getBoolean(ArabToolsPrefs.WAS_DISABLED, false)) {
            prefs.edit().putBoolean(ArabToolsPrefs.WAS_DISABLED, false).apply()
            safe {
                Toast.makeText(
                    activity,
                    "أدوات عربية: أُوقفت التعديلات تلقائياً بعد تكرار توقّف التطبيق. أعِد تفعيلها من الإعدادات.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun markStable(activity: Activity) {
        val token = ++stableToken
        handler.postDelayed({
            safe {
                if (token == stableToken && !activity.isFinishing && !activity.isDestroyed) {
                    prefs.edit().putInt(ArabToolsPrefs.BOOT_COUNT, 0).apply()
                }
            }
        }, 20_000L)
    }
}
