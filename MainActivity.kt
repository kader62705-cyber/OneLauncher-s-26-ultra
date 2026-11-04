package com.example.onelauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

private const val M = ViewGroup.LayoutParams.MATCH_PARENT
private const val W = ViewGroup.LayoutParams.WRAP_CONTENT
private const val APPWIDGET_HOST_ID = 2048

data class App(val label: String, val pkg: String, val intent: Intent, val icon: Bitmap)
enum class Kind { HOME, DRAWER, DOCK }
class PageVH(v: View) : RecyclerView.ViewHolder(v)

/** One UI style squircle (superellipse) */
fun squircle(s: Float): Path {
    val p = Path()
    val r = s / 2f
    val n = 5.0
    for (i in 0..120) {
        val t = 2.0 * PI * i / 120
        val c = cos(t)
        val sn = sin(t)
        val x = r + r * sign(c) * abs(c).pow(2.0 / n)
        val y = r + r * sign(sn) * abs(sn).pow(2.0 / n)
        if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
    }
    p.close()
    return p
}

/** Takes the app's OWN icon and cuts it into a squircle */
fun renderIcon(d: Drawable, s: Int): Bitmap {
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
        val o = (s * 0.25f).toInt()
        d.background?.apply { setBounds(-o, -o, s + o, s + o); draw(c) }
        d.foreground?.apply { setBounds(-o, -o, s + o, s + o); draw(c) }
    } else {
        c.drawColor(Color.WHITE)
        val i = (s * 0.17f).toInt()
        d.setBounds(i, i, s - i, s - i)
        d.draw(c)
    }
    val mask = squircle(s.toFloat()).apply { fillType = Path.FillType.INVERSE_WINDING }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    c.drawPath(mask, paint)
    return bmp
}

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private var apps = listOf<App>()
    private var home = mutableListOf<String>()
    private var dock = mutableListOf<String>()
    private lateinit var root: FrameLayout
    private lateinit var homeLayer: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var dockBar: LinearLayout
    private lateinit var widgetContainer: LinearLayout
    private lateinit var appWidgetHost: AppWidgetHost
    private var selectedIconPack = ""
    private lateinit var drawer: FrameLayout
    private lateinit var drawerPanel: LinearLayout
    private lateinit var search: EditText
    private lateinit var rv: RecyclerView
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var detector: GestureDetector
    private var drawerOpen = false
    private var pageCount = 1
    private var topInset = 0
    private var bottomInset = 0

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { loadApps() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = r.toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        selectedIconPack = prefs.getString("icon_pack", "") ?: ""
        appWidgetHost = AppWidgetHost(this, APPWIDGET_HOST_ID)
        appWidgetHost.startListening()
        buildUi()
        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dy) < abs(dx)) return false
                if (!drawerOpen && vy < -900 && dy < -dp(80)) { setDrawer(true); return true }
                if (drawerOpen && vy > 900 && !rv.canScrollVertically(-1)) { setDrawer(false); return true }
                return false
            }
        })
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(pkgReceiver, f)
        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        try { appWidgetHost.stopListening() } catch (_: Exception) {}
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            setDrawer(false)
            pager.setCurrentItem(0, true)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (drawerOpen) setDrawer(false) else pager.setCurrentItem(0, true)
    }

    // ---------------- UI ----------------
    private fun buildUi() {
        root = FrameLayout(this)
        setContentView(root)

        homeLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pager = ViewPager2(this)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { buildDots(pageCount, position) }
        })
        homeLayer.addView(pager, LinearLayout.LayoutParams(M, 0, 1f))
        widgetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        homeLayer.addView(widgetContainer, LinearLayout.LayoutParams(M, W))
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        homeLayer.addView(dots, LinearLayout.LayoutParams(M, dp(20)))
        dockBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rounded(0x33FFFFFF, dp(34))
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        homeLayer.addView(dockBar, LinearLayout.LayoutParams(M, W).apply {
            setMargins(dp(14), dp(4), dp(14), 0)
        })
        root.addView(homeLayer, FrameLayout.LayoutParams(M, M))
        drawer = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            isClickable = true
            setBackgroundColor(0x99101014.toInt())
        }
        drawerPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(0xAAFFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
            background = rounded(0x33FFFFFF, dp(26))
            setPadding(dp(20), 0, dp(20), 0)
        }
        drawerPanel.addView(search, LinearLayout.LayoutParams(M, dp(52)).apply {
            setMargins(dp(14), 0, dp(14), dp(12))
        })
        drawerAdapter = DrawerAdapter()
        rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, prefs.getInt("drawer_columns", 4).coerceIn(3, 6))
            adapter = drawerAdapter
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(24))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        drawerPanel.addView(rv, LinearLayout.LayoutParams(M, 0, 1f))
        drawer.addView(drawerPanel, FrameLayout.LayoutParams(M, M))
        root.addView(drawer, FrameLayout.LayoutParams(M, M))
        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(0x55000000, dp(24))
            elevation = dp(8).toFloat()
            setOnClickListener { showSettings() }
            contentDescription = "Launcher settings"
        }
        root.addView(settingsButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(38), dp(16), 0)
        })
        search.doAfterTextChanged { drawerAdapter.filter(it?.toString() ?: "") }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = b.top
            bottomInset = b.bottom
            homeLayer.setPadding(0, topInset, 0, bottomInset + dp(12))
            drawerPanel.setPadding(0, topInset + dp(12), 0, bottomInset)
            ins
        }
    }

    private fun buildDots(n: Int, sel: Int) {
        dots.removeAllViews()
        if (n < 2) return
        for (i in 0 until n) {
            dots.addView(View(this).apply {
                background = rounded(if (i == sel) 0xFFFFFFFF.toInt() else 0x66FFFFFF, dp(4))
                layoutParams = LinearLayout.LayoutParams(dp(if (i == sel) 16 else 7), dp(7)).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
            })
        }
    }

    private fun clockWidget(): View {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            addView(TextClock(ctx).apply {
                format12Hour = "h:mm"; format24Hour = "H:mm"
                textSize = 64f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setShadowLayer(8f, 0f, 2f, 0x55000000)
            })
            addView(TextClock(ctx).apply {
                format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
                textSize = 16f
                setTextColor(Color.WHITE)
                setShadowLayer(6f, 0f, 1f, 0x55000000)
            })
        }
    }

    // ---------------- Drawer open/close ----------------
    private fun setDrawer(open: Boolean) {
        if (open == drawerOpen) return
        drawerOpen = open
        val ip = DecelerateInterpolator(2f)
        if (open) {
            drawer.visibility = View.VISIBLE
            drawer.alpha = 0f
            drawerPanel.scaleX = 1.08f; drawerPanel.scaleY = 1.08f
            drawerPanel.translationY = dp(40).toFloat()
            drawer.animate().alpha(1f).setDuration(320).setInterpolator(ip).start()
            drawerPanel.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(320).setInterpolator(ip).start()
            homeLayer.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(300).setInterpolator(ip).start()
            animateBlur(0, 60)
        } else {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(search.windowToken, 0)
            search.setText("")
            drawer.animate().alpha(0f).setDuration(220).withEndAction {
                if (!drawerOpen) drawer.visibility = View.GONE
            }.start()
            drawerPanel.animate().scaleX(0.96f).scaleY(0.96f).setDuration(220).start()
            homeLayer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(OvershootInterpolator(1.2f)).start()
            animateBlur(60, 0)
        }
    }

    private fun animateBlur(from: Int, to: Int) {
        if (Build.VERSION.SDK_INT < 31) return
        ValueAnimator.ofInt(from, to).apply {
            duration = 300
            addUpdateListener {
                try { window.setBackgroundBlurRadius(it.animatedValue as Int) } catch (_: Throwable) {}
            }
            start()
        }
    }

    // ---------------- Data ----------------
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val size = dp(prefs.getInt("icon_dp", 58))
            val list = pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { ri ->
                    val ai = ri.activityInfo
                    val intent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ai.packageName, ai.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    App(ri.loadLabel(pm).toString(), ai.packageName, intent, renderIcon(loadThemedIcon(ai.packageName, ri.loadIcon(pm)), size))
                }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { apps = list; initDefaults(); refreshAll() }
        }.start()
    }

    private fun loadList(k: String) =
        (prefs.getString(k, "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()

    private fun saveLists() {
        prefs.edit().putString("home", home.joinToString(",")).putString("dock", dock.joinToString(",")).apply()
    }

    private fun initDefaults() {
        val pm = packageManager
        if (!prefs.contains("dock")) {
            val intents = listOf(
                Intent(Intent.ACTION_DIAL),
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            )
            dock = intents.mapNotNull {
                pm.resolveActivity(it, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            }.filter { p -> apps.any { it.pkg == p } }.distinct().toMutableList()
            prefs.edit().putString("dock", dock.joinToString(",")).apply()
        }
        if (!prefs.contains("home")) {
            val d = loadList("dock")
            home = apps.filter { it.pkg !in d }.take(12).map { it.pkg }.toMutableList()
            prefs.edit().putString("home", home.joinToString(",")).apply()
        }
    }

    private fun refreshAll() {
        val byPkg = apps.associateBy { it.pkg }
        home = loadList("home").filter { it in byPkg }.toMutableList()
        dock = loadList("dock").filter { it in byPkg }.toMutableList()
        val homeApps = home.mapNotNull { byPkg[it] }
        val pages = mutableListOf<List<App>>()
        var i = 0
        var first = true
        while (i < homeApps.size || first) {
            val cap = if (first) 16 else 20
            pages.add(homeApps.drop(i).take(cap))
            i += cap
            first = false
        }
        val cur = pager.currentItem
        pageCount = pages.size
        pager.adapter = PageAdapter(pages)
        pager.setCurrentItem(min(cur, pages.size - 1), false)
        buildDots(pageCount, pager.currentItem)

        dockBar.removeAllViews()
        dock.mapNotNull { byPkg[it] }.forEach { app ->
            val cell = Cell(this, true)
            cell.bind(app, Kind.DOCK)
            dockBar.addView(cell, LinearLayout.LayoutParams(0, W, 1f))
        }
        dockBar.visibility = if (dock.isEmpty()) View.GONE else View.VISIBLE
        drawerAdapter.submit(apps)
    }


    // ---------------- Launcher settings ----------------
    private fun showSettings() {
        val items = arrayOf(
            "Icon Pack",
            "Home Screen Grid",
            "Drawer Grid",
            "Icon Size",
            "Widgets",
            "Wallpaper",
            "Lock Screen Settings",
            "Appearance",
            "Show / Hide App Labels",
            "Backup & Restore",
            "About OneLauncher"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("OneLauncher Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseIconPack()
                    1 -> chooseGrid("home_columns", "Home Screen Grid")
                    2 -> chooseGrid("drawer_columns", "Drawer Grid")
                    3 -> chooseIconSize()
                    4 -> pickWidget()
                    5 -> openWallpaperPicker()
                    6 -> openLockSettings()
                    7 -> chooseAppearance()
                    8 -> {
                        val show = !prefs.getBoolean("show_labels", true)
                        prefs.edit().putBoolean("show_labels", show).apply()
                        Toast.makeText(this, if (show) "App labels enabled" else "App labels hidden", Toast.LENGTH_SHORT).show()
                        refreshAll()
                    }
                    9 -> showBackupInfo()
                    10 -> android.app.AlertDialog.Builder(this).setTitle("OneLauncher")
                        .setMessage("OneLauncher • Galaxy-inspired Android Home\nVersion 2.1\nEnglish interface")
                        .setPositiveButton("OK", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun chooseGrid(key: String, title: String) {
        val choices = arrayOf("3 columns", "4 columns", "5 columns", "6 columns")
        val current = (prefs.getInt(key, 4) - 3).coerceIn(0, 3)
        android.app.AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(choices, current) { d, which ->
                prefs.edit().putInt(key, which + 3).apply()
                d.dismiss()
                refreshAll()
                if (key == "drawer_columns") rv.layoutManager =
                    GridLayoutManager(this, which + 3)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseIconSize() {
        val values = arrayOf("Small", "Default", "Large", "Extra Large")
        val labels = arrayOf(48, 58, 66, 74)
        val current = prefs.getInt("icon_dp", 58)
        val index = labels.indices.minByOrNull { kotlin.math.abs(labels[it] - current) } ?: 1
        android.app.AlertDialog.Builder(this).setTitle("Icon Size")
            .setSingleChoiceItems(values, index) { d, which ->
                prefs.edit().putInt("icon_dp", labels[which]).apply()
                d.dismiss()
                loadApps()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseAppearance() {
        val values = arrayOf("System Default", "Light", "Dark")
        val current = prefs.getInt("appearance", 0)
        android.app.AlertDialog.Builder(this).setTitle("Appearance")
            .setSingleChoiceItems(values, current) { d, which ->
                prefs.edit().putInt("appearance", which).apply()
                d.dismiss()
                Toast.makeText(this, "Appearance saved. Reopen launcher to apply fully.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseIconPack() {
        val packs = packageManager.queryIntentActivities(
            Intent("org.adw.launcher.THEMES"), 0
        ).map { it.activityInfo.packageName }.distinct().toMutableList()
        val nova = packageManager.queryIntentActivities(
            Intent("com.novalauncher.THEME"), 0
        ).map { it.activityInfo.packageName }
        packs.addAll(nova)
        val unique = packs.distinct()
        val labels = mutableListOf("Use app icons")
        labels.addAll(unique.map { pkg ->
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (_: Exception) { pkg }
        })
        android.app.AlertDialog.Builder(this).setTitle("Choose Icon Pack")
            .setItems(labels.toTypedArray()) { _, which ->
                selectedIconPack = if (which == 0) "" else unique[which - 1]
                prefs.edit().putString("icon_pack", selectedIconPack).apply()
                loadApps()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun loadThemedIcon(appPackage: String, fallback: Drawable): Drawable {
        if (selectedIconPack.isBlank()) return fallback
        return try {
            val packContext = createPackageContext(selectedIconPack, CONTEXT_IGNORE_SECURITY)
            val xmlId = packContext.resources.getIdentifier("appfilter", "xml", selectedIconPack)
            if (xmlId == 0) return fallback
            val parser = packContext.resources.getXml(xmlId)
            var drawableName: String? = null
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component") ?: ""
                    if (component.contains("{$appPackage/")) {
                        drawableName = parser.getAttributeValue(null, "drawable")
                        break
                    }
                }
                parser.next()
            }
            parser.close()
            val id = drawableName?.let { packContext.resources.getIdentifier(it, "drawable", selectedIconPack) } ?: 0
            if (id != 0) packContext.resources.getDrawable(id) else fallback
        } catch (_: Exception) { fallback }
    }

    private fun openWallpaperPicker() {
        try { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
        catch (_: Exception) { Toast.makeText(this, "Wallpaper picker unavailable", Toast.LENGTH_SHORT).show() }
    }

    private fun openLockSettings() {
        try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun showBackupInfo() {
        val data = "Home: ${home.joinToString(",")}\nDock: ${dock.joinToString(",")}"
        android.app.AlertDialog.Builder(this).setTitle("Backup & Restore")
            .setMessage("Your layout and launcher preferences are stored on this device.\n\n$data")
            .setPositiveButton("OK", null).show()
    }

    private fun pickWidget() {
        try {
            val id = appWidgetHost.allocateAppWidgetId()
            val pick = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            startActivityForResult(pick, 710)
        } catch (_: Exception) {
            Toast.makeText(this, "Widget picker unavailable on this device", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated by Android; retained for Android 5 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 710) {
            val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            if (resultCode == Activity.RESULT_OK && id > 0) {
                try {
                    val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
                    if (info != null) {
                        val widget = appWidgetHost.createView(this, id, info)
                        widget.setAppWidget(id, info)
                        widgetContainer.addView(widget, LinearLayout.LayoutParams(M, dp(180)).apply {
                            bottomMargin = dp(8)
                        })
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to add widget", Toast.LENGTH_SHORT).show()
                }
            } else if (id > 0) {
                try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
            }
        }
    }

    // ---------------- Actions ----------------
    private fun launch(a: App, v: View) {
        try {
            val o = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height)
            startActivity(Intent(a.intent), o.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(anchor: View, a: App, kind: Kind) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF5FFFFFF.toInt(), dp(24))
            setPadding(0, dp(8), 0, dp(8))
        }
        val pw = PopupWindow(card, W, W, true)
        pw.elevation = dp(16).toFloat()
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        fun item(t: String, act: () -> Unit) {
            card.addView(TextView(this).apply {
                text = t
                textSize = 15f
                setTextColor(0xFF1B1B1F.toInt())
                setPadding(dp(22), dp(13), dp(34), dp(13))
                setOnClickListener { pw.dismiss(); act() }
            })
        }
        when (kind) {
            Kind.HOME -> item("Remove from Home") { home.remove(a.pkg); saveLists(); refreshAll() }
            Kind.DRAWER -> {
                if (a.pkg in home) item("Remove from Home") { home.remove(a.pkg); saveLists(); refreshAll() }
                else item("Add to Home") { home.add(a.pkg); saveLists(); refreshAll() }
                if (a.pkg !in dock && dock.size < 5) item("Add to Dock") { dock.add(a.pkg); saveLists(); refreshAll() }
            }
            Kind.DOCK -> item("Remove from Dock") { dock.remove(a.pkg); saveLists(); refreshAll() }
        }
        item("App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
        }
        item("Uninstall") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
        }
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val dm = resources.displayMetrics
        val x = (loc[0]).coerceIn(dp(12), dm.widthPixels - card.measuredWidth - dp(12))
        val y = if (kind == Kind.DOCK) loc[1] - card.measuredHeight - dp(8)
        else min(loc[1] + anchor.height, dm.heightPixels - card.measuredHeight - dp(24))
        pw.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
    }

    // ---------------- Views ----------------
    inner class Cell(ctx: Context, private val dockMode: Boolean) : LinearLayout(ctx) {
        private val img = ImageView(ctx)
        private val tv = TextView(ctx)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            val iconSize = dp(prefs.getInt("icon_dp", 58))
            addView(img, LinearLayout.LayoutParams(iconSize, iconSize))
            tv.apply {
                setTextColor(Color.WHITE)
                textSize = 11.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 1f, 0x88000000.toInt())
            }
            addView(tv, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(6) })
            if (dockMode || !prefs.getBoolean("show_labels", true)) tv.visibility = View.GONE
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(120).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(OvershootInterpolator(3f)).start()
                }
                false
            }
        }

        fun bind(a: App, kind: Kind) {
            img.setImageBitmap(a.icon)
            tv.text = a.label
            setOnClickListener { launch(a, img) }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showMenu(this, a, kind)
                true
            }
        }
    }

    inner class PageAdapter(private val pages: List<List<App>>) : RecyclerView.Adapter<PageVH>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int): PageVH {
            val c = LinearLayout(p.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(M, M)
                setPadding(dp(8), dp(8), dp(8), 0)
            }
            return PageVH(c)
        }

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(h: PageVH, pos: Int) {
            val c = h.itemView as LinearLayout
            c.removeAllViews()
            if (pos == 0) c.addView(clockWidget())
            val grid = GridLayout(c.context).apply { columnCount = prefs.getInt("home_columns", 4).coerceIn(3, 6) }
            pages[pos].forEach { app ->
                val cell = Cell(c.context, false)
                cell.bind(app, Kind.HOME)
                val lp = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply { width = 0; height = W; bottomMargin = dp(14) }
                grid.addView(cell, lp)
            }
            c.addView(grid, LinearLayout.LayoutParams(M, W))
        }
    }

    inner class DrawerAdapter : RecyclerView.Adapter<PageVH>() {
        private var all = listOf<App>()
        private var shown = listOf<App>()

        fun submit(l: List<App>) { all = l; filter(search.text.toString()) }

        fun filter(q: String) {
            shown = if (q.isBlank()) all else all.filter { it.label.contains(q.trim(), true) }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): PageVH {
            val cell = Cell(p.context, false)
            cell.layoutParams = RecyclerView.LayoutParams(M, W)
            return PageVH(cell)
        }

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: PageVH, pos: Int) {
            (h.itemView as Cell).bind(shown[pos], Kind.DRAWER)
        }
    }
}
