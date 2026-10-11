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
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.navigation.NavigationBarView

/**
 * محرّك تعديلات «أدوات عربية».
 *
 * الفكرة: `load(context)` في الإضافة يمرّر سياق التطبيق، ومنه نصل إلى
 * `Application` فنُسجّل مستمع دورة حياة لكل الشاشات — وكل هذا **واجهة عامة
 * صريحة** لا انعكاس ولا صنف داخلي مخفي (بخلاف ما تفعله CSM عبر
 * `ActivityThread`، وهو محجوب أصلاً في `targetSdk` الحديث).
 *
 * ### التوقيت — أهم درس في هذا المحرّك
 * التطبيق يُحمِّل الإضافات من داخل `MainActivity.onCreate` عبر مسار مؤجَّل
 * (تحقّقنا من ذلك في الـbytecode: `MainActivity$onCreate$12$4` ←
 * `PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins`). أي أن
 * `load()` يصلنا **بعد** أن تكون الشاشة الأولى قد مرّت بـ`onResume` بالفعل،
 * فلا يصلنا لها أي نداء دورة حياة، ولا يُطبَّق تعديل واحد. لذلك لا نعتمد على
 * الدورة وحدها: السياق المارّ إلى `load` هو الشاشة نفسها، فنأخذها منه مباشرةً
 * في [prime] ونربط المراقب ونطبّق فوراً.
 *
 * عناصر التطبيق نجدها بالبحث في شجرة العرض: نوع حقيقي (`BottomNavigationView`)
 * أو اسم معرّف وقت التشغيل (`getIdentifier`) — لا اعتماد على ترتيب العرض ولا
 * على أرقام `R.id` وقت الترجمة، فيصمد التعديل عبر تحديثات التطبيق.
 *
 * ### قواعد السلامة (غير قابلة للتفاوض)
 * 1. كل تعديل داخل [safe] — لا يخرج استثناء أبداً إلى التطبيق (ويُسجَّل فقط).
 * 2. إن لم يُعثر على العنصر: لا شيء يحدث (تجاهل صامت، لا تعطيل).
 * 3. لا نلمس شيئاً لم يطلبه المستخدم، وإذا ألغى الطلب نُرجع ما غيّرناه مرة واحدة.
 * 4. [enabled] مفتاح رئيسي + وضع آمن، والاثنان يُطفآن كل شيء فوراً.
 * 5. [guardCrashLoop]: إقلاعات سريعة متكرّرة دون استقرار ⇒ يُطفأ المفتاح الرئيسي.
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
        runCatching(block).onFailure {
            ArabToolsStatus.lastError = it.javaClass.simpleName + ": " + it.message
            Log.w(TAG, "تعديل سقط بصمت", it)
        }
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

    /** وصف عربي للتعديلات المطلوبة الآن — يُستخدم في الرسالة وفي التشخيص. */
    private fun activeSummary(): String {
        if (!enabled()) return "لا شيء (التعديلات موقوفة)"
        val parts = ArrayList<String>()
        if (prefs.getBoolean(ArabToolsPrefs.PLAYER_LANDSCAPE, false)) parts.add("التدوير الأفقي")
        when (prefs.getString(ArabToolsPrefs.NIGHT_MODE, "auto")) {
            "dark" -> parts.add("الوضع الليلي")
            "light" -> parts.add("الوضع النهاري")
        }
        if (prefs.getBoolean(ArabToolsPrefs.HIDE_STATUS, false)) parts.add("إخفاء شريط الحالة")
        if (colorOf(prefs.getString(ArabToolsPrefs.STATUS_COLOR, "default")) != null) parts.add("لون شريط الحالة")
        if (colorOf(prefs.getString(ArabToolsPrefs.NAV_COLOR, "default")) != null) parts.add("لون شريط النظام")
        if (prefs.getBoolean(ArabToolsPrefs.NAV_HIDE_BAR, false)) parts.add("إخفاء شريط التنقل")
        val hidden = ArabToolsPrefs.HIDEABLE_TABS.filter { prefs.getBoolean(it.key, false) }.map { it.label }
        if (hidden.isNotEmpty()) parts.add("إخفاء " + hidden.joinToString("، "))
        return if (parts.isEmpty()) "لا شيء (لم تُفتح أي مفاتيح)" else parts.joinToString("، ")
    }

    // ------------------------------------------------------- دورة حياة الشاشات

    /**
     * الربط الفوري — يُستدعى من [ArabToolsPlugin.load] بعد التسجيل.
     *
     * [activity] هي الشاشة التي جاءت في السياق (تحقّقنا من أنها `MainActivity`).
     * نؤجّل التطبيق قليلاً (350ms) كي لا نلمس الشاشة وهي في منتصف
     * `onCreate`/`onResume`، ثم نربط المراقب ونطبّق.
     */
    fun prime(activity: Activity?) {
        if (activity == null) {
            Log.i(TAG, "prime: لا شاشة في السياق — ننتظر أول دورة حياة")
            return
        }
        Log.i(TAG, "prime: ربط فوري على " + activity.javaClass.simpleName)
        handler.postDelayed({
            safe {
                if (activity.isFinishing || activity.isDestroyed) return@safe
                attachProbe(activity)
                lastApplyAt = 0L
                applyAll(activity)
            }
        }, 350L)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        safe {
            ArabToolsStatus.lastActivity = activity.javaClass.simpleName
            guardCrashLoop(activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        safe {
            ArabToolsStatus.lastActivity = activity.javaClass.simpleName
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
        ArabToolsStatus.registered = false
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

        ArabToolsStatus.applyCount++
        ArabToolsStatus.lastSummary = activeSummary()
        maybeToast(activity)
    }

    /**
     * رسالة تأكيد قصيرة مرة واحدة لكل عملية تشغيل — أهم وسيلة تشخيص للمستخدم
     * الذي يجرّب دون كبل: تُثبت أن المحرّك عمل، وتقول له ماذا فُعِّل فعلاً.
     */
    private fun maybeToast(activity: Activity) {
        if (ArabToolsStatus.toasted) return
        if (!prefs.getBoolean(ArabToolsPrefs.SHOW_TOAST, true)) return
        ArabToolsStatus.toasted = true
        val text = if (enabled()) {
            "أدوات عربية: " + activeSummary()
        } else {
            "أدوات عربية: التعديلات موقوفة (راجع المفتاح الرئيسي أو الوضع الآمن)"
        }
        safe { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
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
        val playerSeen = isPlayerVisible(activity)
        ArabToolsStatus.playerSeen = playerSeen
        val wantsLandscape = on &&
            prefs.getBoolean(ArabToolsPrefs.PLAYER_LANDSCAPE, false) &&
            playerSeen

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

    /**
     * إخفاء/إظهار تبويبات الشريط، وإخفاء الشريط كاملاً.
     *
     * ### قاعدة حماية المستخدم من القفل (أهم إصلاح في هذا الإصدار)
     * التطبيق **لا يملك قائمة جانبية** — تحقّقنا من حزمته: لا `NavigationView`
     * ولا أي درج، وروابط التنقل العميقة (`cloudstreamapp:`) تصل إلى البحث/التحميلات/
     * المشغّل فقط ولا تصل إلى «الإعدادات». فتبويب الإعدادات هو **الطريق الوحيد**
     * إلى إعدادات الإضافة؛ لو أُخفي (أو أُخفي الشريط كله) لَما بقي للمستخدم أي
     * سبيل للرجوع. لذلك: التبويبات المحميّة لا تُخفى أبداً ([ArabToolsPrefs.Tab.canHide])،
     * وإن أخفى المستخدم الشريط كله بقي [updateEscape] منفذاً يعيده بضغط مطوّل.
     */
    private fun applyNavTabs(activity: Activity, on: Boolean) {
        val hideBar = on && prefs.getBoolean(ArabToolsPrefs.NAV_HIDE_BAR, false)
        val hidden = if (on) hiddenTabIds(activity.resources) else emptySet()
        val wantsChange = hideBar || hidden.isNotEmpty()

        // لا نمرّ على شجرة العرض إطلاقاً إن لم يطلب المستخدم أي تغيير ولم نكن قد
        // غيّرنا الشريط سابقاً — فلا نُثقل رسم الشاشة بلا سبب. (نترك العدّاد على
        // «لم يُقس بعد» كي يفرّق التشخيص بين «لم يعمل المحرّك» و«لم يجد الشريط».)
        if (!wantsChange && !navTouched) {
            updateEscape(activity, false)
            return
        }

        // كلا الشريطين من النوع نفسه في Material (`NavigationBarView`): الشريط
        // السفلي في الوضع الرأسي، والـRail في الأفقي — فنعالجهما معاً.
        val bars = findViews(activity.window?.decorView) { it is NavigationBarView }
            .filterIsInstance<NavigationBarView>()
        ArabToolsStatus.navBarsFound = bars.size
        updateEscape(activity, hideBar)
        if (bars.isEmpty()) return

        val signature = hidden.sorted().joinToString(",") + "|bar=" + hideBar
        val changed = signature != lastTabsSignature

        for (bar in bars) {
            val wanted = if (hideBar) View.GONE else View.VISIBLE
            if (bar.visibility != wanted) safe { bar.visibility = wanted }
            if (changed) safe { rebuildMenu(bar, hidden) }
        }
        if (changed) lastTabsSignature = signature
        navTouched = wantsChange
    }

    /** المعرّفات المطلوب إخفاؤها — بلا التبويبات المحميّة (الإعدادات). */
    private fun hiddenTabIds(resources: Resources?): Set<Int> {
        if (resources == null) return emptySet()
        val hidden = HashSet<Int>()
        for (tab in ArabToolsPrefs.TABS) {
            if (!tab.canHide) continue          // محميّ: لا يُخفى كي لا يُقفل المستخدم
            if (!prefs.getBoolean(tab.key, false)) continue
            val id = idOf(resources, tab.resName)
            if (id != 0) hidden.add(id)
        }
        return hidden
    }

    /**
     * يدخل التعديل على قائمة الشريط **بالحد الأدنى**: يُحذف المطلوب فقط بـ
     * `removeItem`، فتبقى أيقونات الباقي كما رتّبها التطبيق. هذا هو **سبب
     * اختفاء الأيقونات** سابقاً: `menu.clear()` كان يمحو كل عناصر القائمة ثم
     * يعيد بناءها، فتضيع أيقونات لم يطلب المستخدم إخفاءها. وعند الإرجاع نُعيد
     * ما نقص من نسخة «الأصل» المحفوظة قبل أول تعديل (عنوان + أيقونة + ترتيب).
     *
     * مؤكَّد من bytecode التطبيق: مستمع تغيّر الوجهة في `MainActivity` يستدعي
     * `menu.findItem(id)?.setChecked(...)` فقط ولا يُعيد نفخ القائمة، فالحذف يثبت.
     */
    private fun rebuildMenu(bar: NavigationBarView, hidden: Set<Int>) {
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
        // لا نُفرّغ الشريط أبداً: لو صار الحذف سيُخلي القائمة كلها نتراجع ونتركها.
        if (wanted.isEmpty()) return

        val present = HashSet<Int>()
        for (i in 0 until menu.size()) present.add(menu.getItem(i)?.itemId ?: 0)
        val target = wanted.map { it.id }.toSet()
        if (present == target) return

        val selected = bar.selectedItemId

        // حذف ما زاد فقط — أيقونات الباقي لا تُلمس.
        present.filter { it != 0 && it !in target }.forEach { safe { menu.removeItem(it) } }

        // إعادة ما نقص من نسخة الأصل (بعنوانه وأيقونته كما كانا).
        wanted.forEachIndexed { index, item ->
            if (menu.findItem(item.id) != null) return@forEachIndexed
            val added = menu.add(0, item.id, index, item.title ?: "")
            added.icon = item.icon
        }
        if (target.contains(selected)) safe { bar.selectedItemId = selected }
    }

    // ------------------------------------------------- منفذ النجاة (ضغط مطوّل)

    private var escapeView: View? = null

    /**
     * إن أخفى المستخدم الشريط كله فلم يبق في التطبيق ما يعيده (لا درج جانبي
     * ولا رابط عميق إلى الإعدادات). فنُسلّح ضغطاً مطوّلاً على جذر الشاشة يعيد
     * الشريط فوراً، ونُظهر تنبيهاً مرة واحدة يشرح ذلك. يُسلّح فقط حين يكون
     * الشريط مخفياً — فلا إزعاج في الاستعمال العادي.
     */
    private fun updateEscape(activity: Activity, armed: Boolean) {
        val decor = activity.window?.decorView ?: return
        if (!armed) {
            if (escapeView === decor) safe { decor.setOnLongClickListener(null) }
            escapeView = null
            return
        }
        if (escapeView === decor) return
        escapeView = decor
        safe {
            decor.isLongClickable = true
            decor.setOnLongClickListener {
                safe {
                    prefs.edit().putBoolean(ArabToolsPrefs.NAV_HIDE_BAR, false).apply()
                    lastTabsSignature = null
                    navTouched = false
                    lastApplyAt = 0L
                    applyAll(activity)
                    Toast.makeText(activity, "أدوات عربية: أُعيد شريط التنقل", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        if (!ArabToolsStatus.escapeHintShown) {
            ArabToolsStatus.escapeHintShown = true
            safe {
                Toast.makeText(
                    activity,
                    "اضغط ضغطاً مطوّلاً في مساحة فارغة لإرجاع شريط التنقل",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // --------------------------------------------- حماية من تكرار توقّف التطبيق

    /**
     * لو كان التطبيق يُفتح ثم يسقط، فكل إقلاع جديد يُسجّل بصمة. أربع بصمات
     * متقاربة **دون أن تستقرّ أي منها** ⇒ المفتاح الرئيسي يُغلق وحده، فلا يمكن
     * أن نحبس المستخدم في حلقة انهيار بسبب إضافة أدوات. تُصفَّر البصمات بعد
     * 6 ثوانٍ من استقرار شاشة، فمن يستعمل التطبيق طبيعياً لا يُلامس الحارس.
     */
    private fun guardCrashLoop(activity: Activity) {
        if (countedThisProcess) return
        countedThisProcess = true

        val now = System.currentTimeMillis()
        val last = prefs.getLong(ArabToolsPrefs.BOOT_TS, 0L)
        val count = if (now - last in 1..45_000L) prefs.getInt(ArabToolsPrefs.BOOT_COUNT, 0) + 1 else 1

        val editor = prefs.edit()
            .putLong(ArabToolsPrefs.BOOT_TS, now)
            .putInt(ArabToolsPrefs.BOOT_COUNT, count)
        if (count >= 4) {
            editor.putBoolean(ArabToolsPrefs.MASTER, false)
                .putInt(ArabToolsPrefs.BOOT_COUNT, 0)
                .putBoolean(ArabToolsPrefs.WAS_DISABLED, true)
            ArabToolsStatus.guardDisabled = true
            Log.w(TAG, "حماية الانهيار: أُغلق المفتاح الرئيسي بعد $count إقلاعات سريعة")
        }
        editor.apply()

        if (prefs.getBoolean(ArabToolsPrefs.WAS_DISABLED, false)) {
            prefs.edit().putBoolean(ArabToolsPrefs.WAS_DISABLED, false).apply()
            safe {
                Toast.makeText(
                    activity,
                    "أدوات عربية: أُوقفت التعديلات تلقائياً بعد تكرار الإقلاع السريع. أعِد تفعيلها من الإعدادات.",
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
        }, 6_000L)
    }

    companion object {
        const val TAG = "ArabTools"
    }
}
