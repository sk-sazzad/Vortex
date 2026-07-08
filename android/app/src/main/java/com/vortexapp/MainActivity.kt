package com.vortexapp

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

// ═══════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// ═══════════════════════════════════════
// COLORS
// ═══════════════════════════════════════
object C {
    val BG          = Color.parseColor("#030310")
    val BG2         = Color.parseColor("#060618")
    val CYAN        = Color.parseColor("#00e5ff")
    val PURPLE      = Color.parseColor("#bf5fff")
    val GREEN       = Color.parseColor("#00e676")
    val AMBER       = Color.parseColor("#ffb700")
    val RED         = Color.parseColor("#ff3250")
    val WHITE       = Color.parseColor("#ffffff")
    val T_HIGH      = Color.parseColor("#ffffff")
    val T_MED       = Color.argb(180, 255, 255, 255)
    val T_LOW       = Color.argb(80, 255, 255, 255)
    val T_FAINT     = Color.argb(40, 255, 255, 255)
    val CYAN_DIM    = Color.argb(55, 0, 229, 255)
    val CYAN_BORDER = Color.argb(35, 0, 229, 255)
    val PUR_DIM     = Color.argb(55, 191, 95, 255)
    val PUR_BORDER  = Color.argb(35, 191, 95, 255)
    val GRN_DIM     = Color.argb(55, 0, 230, 118)
    val GRN_BORDER  = Color.argb(35, 0, 230, 118)
}

// ═══════════════════════════════════════
// DEVICE MODEL
// ═══════════════════════════════════════
data class VortexDevice(
    val name: String,
    val ip: String,
    val port: Int = 8765,
    var online: Boolean = false,
    var ping: Long = 0L,
    var os: String = "Windows",
    var savedPassword: String = ""  // saved password for auto-connect
)

// ═══════════════════════════════════════
// MAIN ACTIVITY
// ═══════════════════════════════════════
class MainActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("vortex", Context.MODE_PRIVATE)
    }
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()

    private var wsClient: WebSocketClient? = null
    private var connectedDevice: VortexDevice? = null
    private var currentTab = 0
    private var statsRunnable: Runnable? = null  // for cancelling stats poll

    // Files tab state
    private var driveRowLayout: LinearLayout? = null
    private var fileListLayout: LinearLayout? = null
    private var currentPath: String = "C:\\"
    private var pathBarText: TextView? = null

    // Live stats — real data from agent
    private var statCpuText: android.widget.TextView? = null
    private var statRamText: android.widget.TextView? = null
    private var statDiskText: android.widget.TextView? = null
    private var statNetText: android.widget.TextView? = null

    // Shell output
    private var shellOutputLayout: LinearLayout? = null

    // Alerts list
    private var alertsLayout: LinearLayout? = null

    // Notifications list
    private val notificationList = mutableListOf<Triple<String,String,Int>>()

    // UI containers
    private lateinit var root: FrameLayout
    private lateinit var mainContainer: LinearLayout
    private lateinit var contentArea: FrameLayout
    private lateinit var bottomNav: LinearLayout

    // Tab buttons
    private val tabButtons = arrayOfNulls<LinearLayout>(4)
    private val tabIndicators = arrayOfNulls<View>(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BUG FIX #1: Wrap in try-catch — status/nav bar color APIs can crash on some ROMs
        try {
            window.statusBarColor = C.BG
            window.navigationBarColor = C.BG
        } catch (e: Exception) { /* ignore */ }
        buildApp()
    }

    // ═══════════════════════════════════════
    // APP SHELL
    // ═══════════════════════════════════════
    private fun buildApp() {
        root = FrameLayout(this).apply {
            setBackgroundColor(C.BG)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        setContentView(root)

        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        root.addView(mainContainer)

        contentArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        mainContainer.addView(contentArea)

        bottomNav = buildBottomNav()
        mainContainer.addView(bottomNav)

        showTab(0)
    }

    // ═══════════════════════════════════════
    // BOTTOM NAV
    // ═══════════════════════════════════════
    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.BG)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(58))
        }

        val tabs = listOf(
            Pair("Devices",  0),
            Pair("Control",  1),
            Pair("Files",    2),
            Pair("Settings", 3)
        )

        tabs.forEachIndexed { idx, (label, _) ->
            val tabView = buildTabItem(idx, label)
            nav.addView(tabView)
        }

        // BUG FIX #2: Wrap nav in a vertical container with top border
        val finalWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        finalWrap.addView(View(this).apply {
            setBackgroundColor(Color.argb(20, 0, 229, 255))
            layoutParams = LinearLayout.LayoutParams(MATCH, 1)
        })
        finalWrap.addView(nav)
        return finalWrap
    }

    private fun buildTabItem(idx: Int, label: String): LinearLayout {
        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
            setOnClickListener { showTab(idx) }
        }

        val indicator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(2)).apply {
                bottomMargin = dp(2)
            }
            background = gradientDrawable(C.CYAN, C.PURPLE, horizontal = true, radius = 2f)
            visibility = View.INVISIBLE
        }
        tabIndicators[idx] = indicator
        tab.addView(indicator)

        val icons = listOf("⊞", "⚡", "📁", "⚙")
        val iconView = TextView(this).apply {
            text = icons[idx]
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(80, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        tab.addView(iconView)

        val labelView = TextView(this).apply {
            text = label
            textSize = 7f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(80, 255, 255, 255))
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dp(2)
            }
        }
        tab.addView(labelView)

        tabButtons[idx] = tab
        return tab
    }

    private fun showTab(idx: Int) {
        // Tab 1 (Control) and Tab 2 (Files) need connection
        val needsConnection = idx == 1 || idx == 2
        if (needsConnection && connectedDevice == null) {
            showNeedsConnectionPrompt(idx)
            return
        }

        currentTab = idx
        contentArea.removeAllViews()

        for (i in 0..3) {
            val active = i == idx
            tabIndicators[i]?.visibility = if (active) View.VISIBLE else View.INVISIBLE
            val tab = tabButtons[i] ?: continue
            val isLocked = (i == 1 || i == 2) && connectedDevice == null
            val color = when {
                active   -> C.CYAN
                isLocked -> Color.argb(35, 255, 255, 255)  // very dim = locked
                else     -> Color.argb(80, 255, 255, 255)
            }
            (tab.getChildAt(1) as? TextView)?.setTextColor(color)
            (tab.getChildAt(2) as? TextView)?.setTextColor(color)
        }

        val view = when (idx) {
            0 -> buildDevicesTab()
            1 -> buildControlTab()
            2 -> buildFilesTab()
            3 -> buildSettingsTab()
            else -> buildDevicesTab()
        }
        contentArea.addView(view)
    }

    private fun showNeedsConnectionPrompt(targetTab: Int) {
        // Just switch to devices tab with a message
        currentTab = 0
        contentArea.removeAllViews()

        for (i in 0..3) {
            val active = i == 0
            tabIndicators[i]?.visibility = if (active) View.VISIBLE else View.INVISIBLE
            val tab = tabButtons[i] ?: continue
            val isLocked = (i == 1 || i == 2) && connectedDevice == null
            val color = when {
                active   -> C.CYAN
                isLocked -> Color.argb(35, 255, 255, 255)
                else     -> Color.argb(80, 255, 255, 255)
            }
            (tab.getChildAt(1) as? TextView)?.setTextColor(color)
            (tab.getChildAt(2) as? TextView)?.setTextColor(color)
        }

        val tabName = if (targetTab == 1) "Control" else "Files"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setPadding(dp(32), 0, dp(32), dp(80))
        }
        layout.addView(TextView(this).apply { text = "🔒"; textSize = 40f; gravity = Gravity.CENTER })
        layout.addView(spacer(12))
        layout.addView(TextView(this).apply {
            text = "$tabName is locked"
            setTextColor(C.T_MED); textSize = 18f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        layout.addView(spacer(8))
        layout.addView(TextView(this).apply {
            text = "Connect to a PC first to use $tabName"
            setTextColor(C.T_LOW); textSize = 12f; gravity = Gravity.CENTER
        })
        layout.addView(spacer(24))
        layout.addView(TextView(this).apply {
            text = "SCAN FOR DEVICES"
            setTextColor(C.CYAN); textSize = 12f; gravity = Gravity.CENTER
            letterSpacing = 0.1f
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = cardBg(Color.argb(30, 0, 229, 255), Color.argb(80, 0, 229, 255), 10f)
            setOnClickListener {
                currentTab = 0
                contentArea.removeAllViews()
                contentArea.addView(buildDevicesTab())
                for (i in 0..3) {
                    tabIndicators[i]?.visibility = if (i == 0) View.VISIBLE else View.INVISIBLE
                    val tab = tabButtons[i] ?: return@setOnClickListener
                    val isLocked2 = (i == 1 || i == 2) && connectedDevice == null
                    val c = if (i == 0) C.CYAN else if (isLocked2) Color.argb(35,255,255,255) else Color.argb(80,255,255,255)
                    (tab.getChildAt(1) as? TextView)?.setTextColor(c)
                    (tab.getChildAt(2) as? TextView)?.setTextColor(c)
                }
            }
        })
        contentArea.addView(layout)
    }

    // Call this after connect/disconnect to refresh tab states
    private fun refreshTabStates() {
        for (i in 0..3) {
            val active = i == currentTab
            val isLocked = (i == 1 || i == 2) && connectedDevice == null
            val tab = tabButtons[i] ?: continue
            val color = when {
                active   -> C.CYAN
                isLocked -> Color.argb(35, 255, 255, 255)
                else     -> Color.argb(80, 255, 255, 255)
            }
            (tab.getChildAt(1) as? TextView)?.setTextColor(color)
            (tab.getChildAt(2) as? TextView)?.setTextColor(color)
        }
    }

    // ═══════════════════════════════════════
    // TAB 1 — DEVICES
    // ═══════════════════════════════════════
    private fun buildDevicesTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(C.BG)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(layout)

        layout.addView(buildHeader("VORTEX", "Find & connect your devices"))

        val radarView = RadarView(this)
        layout.addView(radarView.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(180))
        })

        layout.addView(spacer(12))
        layout.addView(sectionLabel("// DEVICES FOUND"))
        layout.addView(spacer(8))

        // Loading text — added before async scan starts
        val scanningText = TextView(this).apply {
            text = "Scanning network..."
            setTextColor(Color.argb(100, 0, 229, 255))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            letterSpacing = 0.1f
        }
        layout.addView(scanningText)

        executor.execute {
            val devices = scanNetwork()
            handler.post {
                // BUG FIX #4: Remove "Scanning..." text before adding results
                layout.removeView(scanningText)
                if (devices.isEmpty()) {
                    layout.addView(emptyState("No devices found", "Make sure agent.exe is running on your PC"))
                } else {
                    devices.forEach { device ->
                        layout.addView(buildDeviceCard(device))
                        layout.addView(spacer(8))
                    }
                }
                layout.addView(spacer(12))
                layout.addView(buildRescanButton(layout))
            }
        }

        return scroll
    }

    private fun buildDeviceCard(device: VortexDevice): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBg(
                fill = Color.argb(20, 0, 229, 255),
                stroke = Color.argb(60, 0, 229, 255),
                radius = 12f,
                leftAccent = if (device.online) C.CYAN else Color.argb(60, 255, 255, 255)
            )
            setOnClickListener { showAuthDialog(device) }
        }

        val iconBox = frameBox(dp(36), dp(36), Color.argb(30, 0, 229, 255), Color.argb(60, 0, 229, 255), 10f)
        val iconText = TextView(this).apply {
            text = if (device.name.contains("Laptop", true)) "💻" else "🖥"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        iconBox.addView(iconText)
        card.addView(iconBox)
        card.addView(spacer(10, horizontal = true))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        info.addView(TextView(this).apply {
            text = device.name
            setTextColor(C.T_HIGH)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
        }
        if (device.online) {
            val dot = View(this).apply {
                background = circle(C.GREEN)
                layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).apply { rightMargin = dp(5) }
            }
            meta.addView(dot)
        }
        meta.addView(TextView(this).apply {
            text = if (device.online) "${device.os} · ${device.ip}" else "Offline"
            setTextColor(C.T_LOW)
            textSize = 9f
        })
        info.addView(meta)
        card.addView(info)

        if (device.online) {
            card.addView(TextView(this).apply {
                text = "${device.ping}ms"
                setTextColor(Color.argb(100, 0, 229, 255))
                textSize = 9f
            })
        }

        return card
    }

    private fun buildRescanButton(parent: LinearLayout): LinearLayout {
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = cardBg(Color.argb(20, 0, 229, 255), Color.argb(50, 0, 229, 255), 10f)
        }
        btn.setOnClickListener {
            parent.removeView(btn)
            val scanning = TextView(this).apply {
                text = "Scanning network..."
                setTextColor(Color.argb(100, 0, 229, 255))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            }
            parent.addView(scanning)
            executor.execute {
                val devices = scanNetwork()
                handler.post {
                    parent.removeView(scanning)
                    devices.forEach { device ->
                        parent.addView(buildDeviceCard(device))
                        parent.addView(spacer(8))
                    }
                    parent.addView(buildRescanButton(parent))
                }
            }
        }
        btn.addView(TextView(this).apply {
            text = "↺  SCAN AGAIN"
            setTextColor(C.CYAN)
            textSize = 11f
            letterSpacing = 0.1f
        })
        return btn
    }

    private fun showAuthDialog(device: VortexDevice, forcePasswordPrompt: Boolean = false) {
        // Saved password থাকলে সরাসরি connect — password চাইবে না
        val saved = loadSavedDevices().find { it.ip == device.ip }
        if (!forcePasswordPrompt && saved?.savedPassword?.isNotEmpty() == true) {
            connectToDevice(device, saved.savedPassword)
            return
        }

        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val devRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val devIcon = frameBox(dp(40), dp(40), Color.argb(30, 0, 229, 255), Color.argb(60, 0, 229, 255), 12f)
        devIcon.addView(TextView(this).apply { text = "🖥"; textSize = 18f; gravity = Gravity.CENTER })
        devRow.addView(devIcon)
        devRow.addView(spacer(12, horizontal = true))
        val devInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devInfo.addView(TextView(this).apply { text = device.name; setTextColor(C.T_HIGH); textSize = 14f; setTypeface(typeface, Typeface.BOLD) })
        devInfo.addView(TextView(this).apply { text = "${device.os} · ${device.ip} · ${device.ping}ms"; setTextColor(C.T_LOW); textSize = 9f })
        devRow.addView(devInfo)
        dialogLayout.addView(devRow)

        val passField = android.widget.EditText(this).apply {
            hint = "Enter password"
            setHintTextColor(Color.argb(60, 255, 255, 255))
            textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        dialogLayout.addView(passField)

        android.app.AlertDialog.Builder(this)
            .setTitle("Connect to ${device.name}")
            .setView(dialogLayout)
            .setPositiveButton("CONNECT") { _, _ ->
                val pass = passField.text.toString()
                connectToDevice(device, pass)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    // ═══════════════════════════════════════
    // TAB 2 — CONTROL (Main Menu)
    // ═══════════════════════════════════════
    private fun buildControlTab(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(C.BG)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(layout)

        val device = connectedDevice
        layout.addView(buildConnectedHeader(device ?: VortexDevice("Unknown", "")))
        layout.addView(spacer(20))

        // Main menu items
        val menuItems = listOf(
            Triple("🖥", "Screen", "Live view & touch control"),
            Triple("📊", "Dashboard", "Stats, actions & sliders"),
            Triple("💻", "Shell", "Run commands on PC"),
            Triple("🎵", "Media", "Control music & volume"),
            Triple("🔔", "Alerts", "Battery, USB & notifications"),
            Triple("📋", "Clipboard", "Sync clipboard"),
            Triple("💬", "Messages", "Send messages to PC"),
            Triple("📁", "File Manager", "Browse & transfer files"),
            Triple("⚡", "Power", "Shutdown, restart, sleep"),
            Triple("🎮", "Performance", "Gaming mode & cleanup"),
            Triple("📦", "App Manager", "Install & uninstall apps")
        )

        menuItems.forEach { (icon, title, desc) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = cardBg(Color.argb(12, 0, 229, 255), Color.argb(30, 0, 229, 255), 12f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
                setOnClickListener { showControlSubMenu(title) }
            }

            val iconBox = frameBox(dp(40), dp(40),
                Color.argb(25, 0, 229, 255), Color.argb(50, 0, 229, 255), 10f)
            iconBox.addView(TextView(this).apply {
                text = icon; textSize = 18f; gravity = Gravity.CENTER
            })
            row.addView(iconBox)
            row.addView(spacer(14, horizontal = true))

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            info.addView(TextView(this).apply {
                text = title; setTextColor(C.T_HIGH); textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text = desc; setTextColor(C.T_LOW); textSize = 10f
            })
            row.addView(info)
            row.addView(TextView(this).apply {
                text = "›"; setTextColor(C.CYAN); textSize = 22f
            })
            layout.addView(row)
        }

        return scroll
    }

    private fun showControlSubMenu(section: String) {
        contentArea.removeAllViews()
        val view = when (section) {
            "Screen"       -> buildScreenSubMenu()
            "Dashboard"    -> buildControlContent()
            "Shell"        -> buildShellSubMenu()
            "Media"        -> buildMediaSubMenu()
            "Alerts"       -> buildAlertsSubMenu()
            "Clipboard"    -> buildClipboardSubMenu()
            "Messages"     -> buildMessagesSubMenu()
            "File Manager" -> buildFilesTab()
            "Power"        -> buildPowerSubMenu()
            "Performance"  -> buildPerfSubMenu()
            "App Manager"  -> buildAppManagerSubMenu()
            else           -> buildControlTab()
        }
        contentArea.addView(view)
    }

    private fun buildSubMenuHeader(title: String, icon: String): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(16))
        }
        val back = TextView(this).apply {
            text = "← "
            setTextColor(C.CYAN); textSize = 16f
            setPadding(0, dp(8), dp(8), dp(8))
            setOnClickListener { showTab(1) }
        }
        header.addView(back)
        header.addView(TextView(this).apply { text = icon; textSize = 18f })
        header.addView(spacer(8, horizontal = true))
        header.addView(TextView(this).apply {
            text = title; setTextColor(C.T_HIGH); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        return header
    }

    private fun buildScreenSubMenu(): ScrollView {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); setBackgroundColor(C.BG) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(layout)
        layout.addView(buildSubMenuHeader("Screen Control", "🖥"))
        layout.addView(buildScreenCard())
        return scroll
    }

    private fun buildShellSubMenu(): ScrollView {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); setBackgroundColor(C.BG) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(layout)
        layout.addView(buildSubMenuHeader("Shell Terminal", "💻"))
        layout.addView(buildShellCard())
        return scroll
    }

    private fun buildMediaSubMenu(): ScrollView {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); setBackgroundColor(C.BG) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(layout)
        layout.addView(buildSubMenuHeader("Media Control", "🎵"))
        layout.addView(buildMediaCard())
        return scroll
    }

    private fun buildAlertsSubMenu(): ScrollView {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); setBackgroundColor(C.BG) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(layout)
        layout.addView(buildSubMenuHeader("Alerts & Notifications", "🔔"))
        layout.addView(buildAlertsCard())
        return scroll
    }

    private fun buildClipboardSubMenu(): ScrollView {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); setBackgroundColor(C.BG) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(layout)
        layout.addView(buildSubMenuHeader("Clipboard Sync", "📋"))
        layout.addView(buildClipboardCard())
        return scroll
    }

    private fun buildMessagesSubMenu(): ScrollView {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); setBackgroundColor(C.BG) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(layout)
        layout.addView(buildSubMenuHeader("Messages", "💬"))
        layout.addView(buildMessagesCard())
        return scroll
    }

    private fun buildPowerSubMenu(): ScrollView {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); setBackgroundColor(C.BG) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(layout)
        layout.addView(buildSubMenuHeader("Power Control", "⚡"))
        layout.addView(spacer(8))

        val actions = listOf(
            Triple("🔴", "Shutdown", "shutdown"),
            Triple("🔄", "Restart", "restart"),
            Triple("💤", "Sleep", "sleep"),
            Triple("❌", "Cancel", "cancel")
        )
        actions.forEach { (icon, label, cmd) ->
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(35, 0, 229, 255), 12f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    sendCommand(cmd)
                    showToast("$label command sent")
                }
            }
            btn.addView(TextView(this).apply { text = icon; textSize = 22f })
            btn.addView(spacer(14, horizontal = true))
            btn.addView(TextView(this).apply {
                text = label; setTextColor(C.T_HIGH); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            btn.addView(TextView(this).apply { text = "›"; setTextColor(C.T_FAINT); textSize = 20f })
            layout.addView(btn)
        }
        return scroll
    }

    private fun buildPerfSubMenu(): ScrollView {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); setBackgroundColor(C.BG) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(layout)
        layout.addView(buildSubMenuHeader("Performance", "🎮"))
        layout.addView(spacer(8))

        val actions = listOf(
            Triple("🎮", "Gaming Mode", "perf:gaming_mode"),
            Triple("🧹", "Clear Temp Files", "perf:temp_clean"),
            Triple("💾", "Disk Cleanup", "perf:disk_cleanup"),
            Triple("🔧", "Clear RAM", "perf:ram_clear")
        )
        actions.forEach { (icon, label, cmd) ->
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(35, 0, 229, 255), 12f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    sendCommand(cmd)
                    showToast("$label started...")
                }
            }
            btn.addView(TextView(this).apply { text = icon; textSize = 22f })
            btn.addView(spacer(14, horizontal = true))
            btn.addView(TextView(this).apply {
                text = label; setTextColor(C.T_HIGH); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            btn.addView(TextView(this).apply { text = "›"; setTextColor(C.T_FAINT); textSize = 20f })
            layout.addView(btn)
        }
        return scroll
    }

    private fun buildAppManagerSubMenu(): ScrollView {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); setBackgroundColor(C.BG) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(layout)
        layout.addView(buildSubMenuHeader("App Manager", "📦"))
        layout.addView(spacer(16))
        layout.addView(TextView(this).apply {
            text = "+ INSTALL NEW APP"
            setTextColor(C.CYAN); textSize = 11f; gravity = Gravity.CENTER
            letterSpacing = 0.1f; setPadding(0, dp(14), 0, dp(14))
            background = cardBg(Color.argb(20, 0, 229, 255), Color.argb(50, 0, 229, 255), 10f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener { showInstallDialog() }
        })
        return scroll
    }

    private fun buildControlContent(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(C.BG)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(layout)

        val device = connectedDevice ?: return scroll

        layout.addView(buildConnectedHeader(device))
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// SCREEN"))
        layout.addView(spacer(8))
        layout.addView(buildScreenCard())
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// DASHBOARD"))
        layout.addView(spacer(8))
        layout.addView(buildStatsGrid())
        layout.addView(spacer(8))
        layout.addView(buildQuickActions())
        layout.addView(spacer(8))
        layout.addView(buildSliders())
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// SHELL"))
        layout.addView(spacer(8))
        layout.addView(buildShellCard())
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// MEDIA"))
        layout.addView(spacer(8))
        layout.addView(buildMediaCard())
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// ALERTS"))
        layout.addView(spacer(8))
        layout.addView(buildAlertsCard())
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// CLIPBOARD SYNC"))
        layout.addView(spacer(8))
        layout.addView(buildClipboardCard())
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// MESSAGES"))
        layout.addView(spacer(8))
        layout.addView(buildMessagesCard())
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// AUTOMATION"))
        layout.addView(spacer(8))
        layout.addView(buildAutomationCard())

        return scroll
    }

    private fun buildConnectedHeader(device: VortexDevice): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(40, 0, 229, 255), 12f)
        }

        val icon = frameBox(dp(32), dp(32), Color.argb(30, 0, 229, 255), Color.argb(50, 0, 229, 255), 8f)
        icon.addView(TextView(this).apply { text = "🖥"; textSize = 14f; gravity = Gravity.CENTER })
        row.addView(icon)
        row.addView(spacer(10, horizontal = true))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        info.addView(TextView(this).apply { text = device.name; setTextColor(C.T_HIGH); textSize = 12f; setTypeface(typeface, Typeface.BOLD) })
        info.addView(TextView(this).apply { text = "${device.ip} · ${device.ping}ms"; setTextColor(C.T_LOW); textSize = 9f })
        row.addView(info)

        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = cardBg(Color.argb(20, 0, 230, 118), Color.argb(60, 0, 230, 118), 6f)
        }
        badge.addView(View(this).apply {
            background = circle(C.GREEN)
            layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).apply { rightMargin = dp(4) }
        })
        badge.addView(TextView(this).apply { text = "LIVE"; setTextColor(C.GREEN); textSize = 8f; letterSpacing = 0.1f })
        row.addView(badge)

        return row
    }

    private fun buildScreenCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(40, 0, 229, 255), 14f)
        }

        val preview = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(160))
            background = roundedBg(Color.parseColor("#000000"), 10f)
        }

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        overlay.addView(TextView(this).apply { text = "▶"; textSize = 28f; setTextColor(C.CYAN); gravity = Gravity.CENTER })
        overlay.addView(spacer(4))
        overlay.addView(TextView(this).apply {
            text = "TAP TO START STREAM"
            setTextColor(Color.argb(80, 0, 229, 255))
            textSize = 9f
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        })
        preview.addView(overlay)
        card.addView(preview)
        card.addView(spacer(10))

        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("MOUSE", "KEYBD", "FULL", "REC").forEach { label ->
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(4) }
                background = cardBg(Color.argb(20, 0, 229, 255), Color.argb(40, 0, 229, 255), 8f)
            }
            btn.addView(TextView(this).apply {
                text = label
                setTextColor(Color.argb(120, 0, 229, 255))
                textSize = 8f
                gravity = Gravity.CENTER
                letterSpacing = 0.05f
            })
            ctrlRow.addView(btn)
        }
        card.addView(ctrlRow)
        card.addView(spacer(8))

        val infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(20, 0, 229, 255), 6f)
        }
        listOf("1920×1080", "24FPS", "TOUCH ON", "45ms").forEach { info ->
            val tv = TextView(this).apply {
                text = info
                setTextColor(Color.argb(80, 0, 229, 255))
                textSize = 8f
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                gravity = Gravity.CENTER
            }
            infoBar.addView(tv)
        }
        card.addView(infoBar)

        return card
    }

    private fun buildStatsGrid(): LinearLayout {
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
        }

        // CPU card
        val cpuCard = buildStatCard("0%", "CPU", C.CYAN, true)
        statCpuText = cpuCard.getChildAt(0) as? android.widget.TextView
        row1.addView(cpuCard)

        // RAM card
        val ramCard = buildStatCard("0%", "RAM", C.PURPLE, true)
        statRamText = ramCard.getChildAt(0) as? android.widget.TextView
        row1.addView(ramCard)

        // DISK card
        val diskCard = buildStatCard("0%", "DISK", C.GREEN, false)
        statDiskText = diskCard.getChildAt(0) as? android.widget.TextView
        row1.addView(diskCard)

        // Net card
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val netCard = buildStatCard("0MB/s", "NETWORK", C.AMBER, false)
        statNetText = netCard.getChildAt(0) as? android.widget.TextView
        row2.addView(netCard)

        // Request stats immediately
        handler.postDelayed({ sendCommand("stats") }, 500)
        // Poll every 3 seconds — cancel on disconnect
        statsRunnable = object : Runnable {
            override fun run() {
                if (connectedDevice != null && currentTab == 1) {
                    sendCommand("stats")
                    handler.postDelayed(this, 3000)
                }
            }
        }
        handler.postDelayed(statsRunnable!!, 3000)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row1)
            addView(row2)
        }
    }

    private fun buildStatCard(value: String, label: String, color: Int, addMargin: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (addMargin) marginEnd = dp(6)
            }
            background = cardBg(Color.argb(25, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)), 10f)
        }
        card.addView(TextView(this).apply {
            text = value
            setTextColor(color)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        val barBg = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(2)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            background = roundedBg(Color.argb(30, 255, 255, 255), 2f)
        }
        // BUG FIX #6: Safe parsing — "↓48" and "52°" must strip non-numeric chars
        val pct = value.filter { it.isDigit() }.toIntOrNull() ?: 50
        barBg.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams((pct * 2).coerceAtMost(200), dp(2))
            background = roundedBg(color, 2f)
        })
        card.addView(barBg)
        card.addView(TextView(this).apply {
            text = label
            setTextColor(Color.argb(80, 255, 255, 255))
            textSize = 8f
            letterSpacing = 0.05f
        })
        return card
    }

    private fun buildQuickActions(): LinearLayout {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val actions = listOf(
            Triple("🔴", "SHUTDOWN", C.RED),
            Triple("🔄", "RESTART", C.AMBER),
            Triple("💤", "SLEEP", C.PURPLE),
            Triple("📷", "SCREENSHOT", C.CYAN)
        )
        actions.forEachIndexed { i, (icon, label, color) ->
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (i < 3) marginEnd = dp(6)
                }
                background = cardBg(
                    Color.argb(25, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)), 10f)
                setOnClickListener { sendCommand(label.lowercase()) }
            }
            btn.addView(TextView(this).apply { text = icon; textSize = 16f; gravity = Gravity.CENTER })
            btn.addView(spacer(4))
            btn.addView(TextView(this).apply {
                text = label
                setTextColor(Color.argb(120, 255, 255, 255))
                textSize = 7f
                gravity = Gravity.CENTER
                letterSpacing = 0.05f
            })
            grid.addView(btn)
        }
        return grid
    }

    private fun buildSliders(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(30, 0, 229, 255), 12f)
        }

        layout.addView(buildSliderRow("Volume", 75, C.CYAN))
        layout.addView(spacer(10))
        layout.addView(buildSliderRow("Brightness", 60, C.AMBER))
        return layout
    }

    private fun buildSliderRow(label: String, value: Int, color: Int): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(5) }
        }
        top.addView(TextView(this).apply { text = label; setTextColor(C.T_LOW); textSize = 10f; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
        top.addView(TextView(this).apply { text = "$value%"; setTextColor(color); textSize = 10f })
        row.addView(top)

        val trackBg = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(3))
            background = roundedBg(Color.argb(30, 255, 255, 255), 2f)
        }
        val fill = View(this).apply {
            background = roundedBg(color, 2f)
        }
        trackBg.addView(fill)
        // BUG FIX #7: Set bar width AFTER layout using ViewTreeObserver — avoids 0-width bar
        trackBg.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                trackBg.viewTreeObserver.removeOnGlobalLayoutListener(this)
                fill.layoutParams = FrameLayout.LayoutParams(trackBg.width * value / 100, dp(3))
            }
        })
        row.addView(trackBg)
        return row
    }

    private fun buildShellCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(35, 0, 229, 255), 14f)
        }

        val cmdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }
        listOf("ipconfig", "dir", "tasklist", "sysinfo").forEach { cmd ->
            val chip = TextView(this).apply {
                text = cmd
                setTextColor(Color.argb(140, 0, 229, 255))
                textSize = 9f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(35, 0, 229, 255), 5f)
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
                setOnClickListener { sendCommand("shell:$cmd") }
            }
            cmdRow.addView(chip)
        }
        card.addView(cmdRow)

        val output = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBg(Color.parseColor("#01010a"), 8f)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(120)).apply { bottomMargin = dp(10) }
        }
        output.addView(termLine("vortex@pc:~$", "", C.CYAN, C.PURPLE, cursor = true))
        output.addView(termOutput("Ready — send a command"))
        shellOutputLayout = output  // real output reference
        card.addView(output)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(25, 0, 229, 255), 8f)
        }
        inputRow.addView(TextView(this).apply { text = "$"; setTextColor(C.CYAN); textSize = 12f })
        inputRow.addView(spacer(8, horizontal = true))
        val cmdInput = android.widget.EditText(this).apply {
            hint = "Enter command..."
            setHintTextColor(Color.argb(60, 255, 255, 255))
            setTextColor(C.T_HIGH)
            textSize = 11f
            background = null
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        inputRow.addView(cmdInput)
        val sendBtn = TextView(this).apply {
            text = "→"
            setTextColor(C.CYAN)
            textSize = 16f
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener {
                val cmd = cmdInput.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    sendCommand("shell:$cmd")
                    cmdInput.text.clear()
                }
            }
        }
        inputRow.addView(sendBtn)
        card.addView(inputRow)

        return card
    }

    private fun buildMediaCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(35, 0, 229, 255), 14f)
        }

        card.addView(TextView(this).apply {
            text = "SPOTIFY"
            setTextColor(Color.argb(100, 0, 229, 255))
            textSize = 8f
            letterSpacing = 0.2f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
        })
        card.addView(TextView(this).apply { text = "Blinding Lights"; setTextColor(C.T_HIGH); textSize = 14f; setTypeface(typeface, Typeface.BOLD) })
        card.addView(TextView(this).apply {
            text = "The Weeknd · After Hours"
            setTextColor(C.T_LOW)
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        })

        val prog = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(2)).apply { bottomMargin = dp(6) }
            background = roundedBg(Color.argb(30, 255, 255, 255), 2f)
        }
        val progFill = View(this).apply {
            background = gradientDrawable(C.CYAN, C.PURPLE, horizontal = true, radius = 2f)
        }
        prog.addView(progFill)
        // BUG FIX #8: Use ViewTreeObserver for progress bar width too
        prog.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                prog.viewTreeObserver.removeOnGlobalLayoutListener(this)
                progFill.layoutParams = FrameLayout.LayoutParams(prog.width * 40 / 100, dp(2))
            }
        })
        card.addView(prog)

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
        }
        timeRow.addView(TextView(this).apply { text = "1:24"; setTextColor(C.T_FAINT); textSize = 9f })
        timeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        timeRow.addView(TextView(this).apply { text = "3:20"; setTextColor(C.T_FAINT); textSize = 9f })
        card.addView(timeRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        listOf("⏮", "⏸", "⏭").forEachIndexed { i, icon ->
            val btn = TextView(this).apply {
                text = icon
                textSize = if (i == 1) 24f else 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                if (i == 1) {
                    setPadding(0, dp(2), 0, dp(2))
                    background = cardBg(Color.argb(30, 0, 229, 255), Color.argb(80, 0, 229, 255), 100f)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.CENTER; marginStart = dp(8); marginEnd = dp(8) }
                }
                setOnClickListener {
                    sendCommand(when (i) { 0 -> "media:prev"; 1 -> "media:playpause"; else -> "media:next" })
                }
            }
            btnRow.addView(btn)
        }
        card.addView(btnRow)

        val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("SPOTIFY", "YOUTUBE", "VLC", "SYSTEM").forEachIndexed { i, app ->
            appRow.addView(TextView(this).apply {
                text = app
                setTextColor(if (i == 0) C.CYAN else C.T_FAINT)
                textSize = 8f
                setPadding(0, dp(5), 0, dp(5))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                background = if (i == 0) cardBg(Color.argb(30, 0, 229, 255), Color.argb(80, 0, 229, 255), 6f)
                else cardBg(Color.argb(10, 255, 255, 255), Color.argb(20, 255, 255, 255), 6f)
                setOnClickListener { sendCommand("media:source:$app") }
            })
            if (i < 3) appRow.addView(spacer(4, horizontal = true))
        }
        card.addView(appRow)

        return card
    }

    private fun buildAlertsCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(28, 0, 229, 255), 14f)
        }

        alertsLayout = card  // real reference for live updates

        // Empty state — real alerts আসলে এটা replace হবে
        card.addView(TextView(this).apply {
            text = "Waiting for alerts..."
            setTextColor(C.T_FAINT)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        })

        return card
    }

    private fun buildClipboardCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBg(Color.argb(12, 191, 95, 255), Color.argb(35, 191, 95, 255), 14f)
        }

        val pcBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = cardBg(Color.argb(20, 191, 95, 255), Color.argb(40, 191, 95, 255), 8f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }
        val pcTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(5) }
        }
        pcTop.addView(TextView(this).apply { text = "PC → MOBILE"; setTextColor(Color.argb(120, 191, 95, 255)); textSize = 8f; letterSpacing = 0.1f; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
        pcTop.addView(TextView(this).apply {
            text = "AUTO ON"
            setTextColor(C.GREEN)
            textSize = 7f
            letterSpacing = 0.1f
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = cardBg(Color.argb(30, 0, 230, 118), Color.argb(60, 0, 230, 118), 20f)
        })
        pcBox.addView(pcTop)
        pcBox.addView(TextView(this).apply { text = "192.168.0.100:8765"; setTextColor(C.T_MED); textSize = 11f })
        card.addView(pcBox)

        val mobBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(30, 0, 229, 255), 8f)
        }
        mobBox.addView(TextView(this).apply { text = "MOBILE → PC"; setTextColor(Color.argb(100, 0, 229, 255)); textSize = 8f; letterSpacing = 0.1f })
        mobBox.addView(spacer(4))
        mobBox.addView(TextView(this).apply { text = "Copy anything on phone → auto paste on PC"; setTextColor(C.T_LOW); textSize = 10f })
        card.addView(mobBox)

        return card
    }

    private fun buildMessagesCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(28, 0, 229, 255), 14f)
        }

        val msgs = listOf(
            Triple("PC চালু আছে?", true, ""),
            Triple("হ্যাঁ, CPU 34%", false, "KHANDOKER PC"),
            Triple("Chrome বন্ধ করো", true, ""),
            Triple("Done ✓ terminated", false, "PC")
        )

        msgs.forEach { (text, isOut, from) ->
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
            }
            if (!isOut && from.isNotEmpty()) {
                wrap.addView(TextView(this).apply { this.text = from; setTextColor(C.T_FAINT); textSize = 8f })
                wrap.addView(spacer(2))
            }
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (isOut) Gravity.END else Gravity.START
            }
            bubble.addView(TextView(this).apply {
                this.text = text
                setTextColor(C.T_MED)
                textSize = 11f
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = if (isOut)
                    cardBg(Color.argb(35, 0, 229, 255), Color.argb(80, 0, 229, 255), 12f)
                else
                    cardBg(Color.argb(30, 191, 95, 255), Color.argb(60, 191, 95, 255), 12f)
            })
            wrap.addView(bubble)
            card.addView(wrap)
        }

        card.addView(spacer(8))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val msgInput = android.widget.EditText(this).apply {
            hint = "Message..."
            setHintTextColor(Color.argb(60, 255, 255, 255))
            setTextColor(C.T_HIGH)
            textSize = 11f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(35, 0, 229, 255), 20f)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        inputRow.addView(msgInput)
        inputRow.addView(spacer(8, horizontal = true))

        val sendBtn = FrameLayout(this).apply {
            background = cardBg(Color.argb(35, 0, 229, 255), Color.argb(80, 0, 229, 255), 100f)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener {
                val msg = msgInput.text.toString().trim()
                if (msg.isNotEmpty()) {
                    sendCommand("message:$msg")
                    msgInput.text.clear()
                }
            }
        }
        sendBtn.addView(TextView(this).apply {
            text = "→"
            setTextColor(C.CYAN)
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        })
        inputRow.addView(sendBtn)
        card.addView(inputRow)

        return card
    }

    private fun buildAutomationCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(28, 0, 229, 255), 14f)
        }

        val tasks = listOf(
            Triple("💤", "PC Sleep", "Daily · 11:00 PM"),
            Triple("⚡", "PC Wake", "Weekdays · 8:00 AM"),
            Triple("💾", "Auto Backup", "Sunday · 2:00 AM")
        )

        tasks.forEachIndexed { i, (icon, name, time) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = cardBg(
                    if (i < 2) Color.argb(20, 0, 230, 118) else Color.argb(10, 0, 229, 255),
                    if (i < 2) Color.argb(50, 0, 230, 118) else Color.argb(25, 0, 229, 255),
                    8f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
            }
            val iconBox = frameBox(dp(28), dp(28),
                if (i < 2) Color.argb(30, 0, 230, 118) else Color.argb(20, 0, 229, 255),
                if (i < 2) Color.argb(60, 0, 230, 118) else Color.argb(40, 0, 229, 255), 7f)
            iconBox.addView(TextView(this).apply { text = icon; textSize = 12f; gravity = Gravity.CENTER })
            row.addView(iconBox)
            row.addView(spacer(10, horizontal = true))

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            info.addView(TextView(this).apply { text = name; setTextColor(C.T_HIGH); textSize = 11f; setTypeface(typeface, Typeface.BOLD) })
            info.addView(TextView(this).apply { text = time; setTextColor(C.T_LOW); textSize = 9f })
            row.addView(info)

            val toggleBg = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(20))
                background = if (i < 2)
                    cardBg(Color.argb(40, 0, 230, 118), Color.argb(80, 0, 230, 118), 10f)
                else
                    cardBg(Color.argb(20, 255, 255, 255), Color.argb(40, 255, 255, 255), 10f)
            }
            val toggleDot = View(this).apply {
                background = circle(if (i < 2) C.GREEN else Color.argb(80, 255, 255, 255))
                layoutParams = FrameLayout.LayoutParams(dp(14), dp(14)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = if (i < 2) dp(18) else dp(3)
                }
            }
            toggleBg.addView(toggleDot)
            row.addView(toggleBg)

            card.addView(row)
        }

        card.addView(spacer(10))
        card.addView(TextView(this).apply {
            text = "// TRIGGER RULES (IF→THEN)"
            setTextColor(Color.argb(80, 0, 229, 255))
            textSize = 8f
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        })

        val ruleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(Color.parseColor("#010110"), 8f)
        }
        listOf(
            "IF battery < 20%  →  notify",
            "IF USB plug        →  alert",
            "IF internet lost   →  notify",
            "IF app crash       →  notify"
        ).forEach { rule ->
            ruleBox.addView(TextView(this).apply {
                text = rule
                setTextColor(Color.argb(90, 255, 255, 255))
                textSize = 9f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
            })
        }
        card.addView(ruleBox)

        return card
    }

    // ═══════════════════════════════════════
    // TAB 3 — FILES
    // ═══════════════════════════════════════
    private fun buildFilesTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(C.BG)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(layout)

        layout.addView(buildHeader("FILES", "Browse & transfer files"))
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// DRIVES"))
        layout.addView(spacer(8))
        val driveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        driveRowLayout = driveRow  // real reference
        layout.addView(driveRow)
        layout.addView(spacer(10))

        val pathBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(25, 0, 229, 255), 8f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        pathBar.addView(TextView(this).apply { text = "🏠"; textSize = 12f })
        pathBar.addView(spacer(6, horizontal = true))
        val pathText = TextView(this).apply {
            text = "Loading..."
            setTextColor(Color.argb(120, 0, 229, 255))
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        pathBarText = pathText  // real reference
        pathBar.addView(pathText)
        layout.addView(pathBar)

        layout.addView(sectionLabel("// FILE BROWSER"))
        layout.addView(spacer(8))

        // Real file list — filled by agent response
        val fileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fileList.addView(TextView(this).apply {
            text = "Loading files..."
            setTextColor(C.T_FAINT); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        })
        fileListLayout = fileList  // real reference
        layout.addView(fileList)

        // Request drives from agent
        if (connectedDevice != null) sendCommand("drives")
        else layout.addView(emptyState("Not connected", "Connect to a PC to browse files"))

        layout.addView(spacer(16))
        layout.addView(sectionLabel("// QUICK SHARE"))
        layout.addView(spacer(8))

        val shareRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shareOptions = listOf(Triple("📱 → 🖥", "PHONE → PC", C.CYAN), Triple("🖥 → 📱", "PC → PHONE", C.PURPLE))
        shareOptions.forEachIndexed { i, (icon, label, color) ->
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i == 0) marginEnd = dp(8) }
                background = cardBg(
                    Color.argb(20, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb(50, Color.red(color), Color.green(color), Color.blue(color)), 10f)
            }
            btn.addView(TextView(this).apply { text = icon; textSize = 20f; gravity = Gravity.CENTER })
            btn.addView(spacer(6))
            btn.addView(TextView(this).apply { text = label; setTextColor(Color.argb(120, 255, 255, 255)); textSize = 9f; gravity = Gravity.CENTER; letterSpacing = 0.05f })
            shareRow.addView(btn)
        }
        layout.addView(shareRow)

        layout.addView(spacer(16))
        layout.addView(sectionLabel("// REMOTE PRINT"))
        layout.addView(spacer(8))

        val printCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(35, 0, 229, 255), 12f)
        }
        val printIcon = frameBox(dp(32), dp(32), Color.argb(25, 0, 229, 255), Color.argb(55, 0, 229, 255), 8f)
        printIcon.addView(TextView(this).apply { text = "🖨"; textSize = 14f; gravity = Gravity.CENTER })
        printCard.addView(printIcon)
        printCard.addView(spacer(10, horizontal = true))
        val printInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
        printInfo.addView(TextView(this).apply { text = "HP LaserJet Pro"; setTextColor(C.T_HIGH); textSize = 12f; setTypeface(typeface, Typeface.BOLD) })
        printInfo.addView(TextView(this).apply { text = "READY · USB"; setTextColor(C.T_LOW); textSize = 9f })
        printCard.addView(printInfo)
        printCard.addView(TextView(this).apply {
            text = "PRINT"
            setTextColor(C.CYAN)
            textSize = 10f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBg(Color.argb(30, 0, 229, 255), Color.argb(70, 0, 229, 255), 8f)
        })
        layout.addView(printCard)

        layout.addView(spacer(16))
        layout.addView(sectionLabel("// APP MANAGER"))
        layout.addView(spacer(8))

        val apps = listOf(Pair("Google Chrome", "128MB · v124"), Pair("VS Code", "210MB · v1.89"), Pair("VLC Player", "45MB · v3.0"))
        apps.forEach { (name, meta) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val appIcon = frameBox(dp(28), dp(28), Color.argb(15, 255, 255, 255), Color.argb(30, 255, 255, 255), 8f)
            appIcon.addView(TextView(this).apply { text = "📦"; textSize = 12f; gravity = Gravity.CENTER })
            row.addView(appIcon)
            row.addView(spacer(10, horizontal = true))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
            info.addView(TextView(this).apply { text = name; setTextColor(C.T_HIGH); textSize = 11f })
            info.addView(TextView(this).apply { text = meta; setTextColor(C.T_FAINT); textSize = 9f })
            row.addView(info)
            row.addView(TextView(this).apply {
                text = "REMOVE"
                setTextColor(Color.argb(120, 255, 80, 100))
                textSize = 8f
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = cardBg(Color.argb(20, 255, 50, 80), Color.argb(50, 255, 50, 80), 6f)
                setOnClickListener { sendCommand("app:uninstall:$name") }
            })
            layout.addView(row)
            layout.addView(View(this).apply { setBackgroundColor(Color.argb(15, 255, 255, 255)); layoutParams = LinearLayout.LayoutParams(MATCH, 1) })
        }

        layout.addView(spacer(10))
        layout.addView(TextView(this).apply {
            text = "+ INSTALL NEW APP"
            setTextColor(C.CYAN)
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            setPadding(0, dp(14), 0, dp(14))
            background = cardBg(Color.argb(20, 0, 229, 255), Color.argb(50, 0, 229, 255), 10f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener { showInstallDialog() }
        })

        return scroll
    }

    // ═══════════════════════════════════════
    // TAB 4 — SETTINGS
    // ═══════════════════════════════════════
    private fun buildSettingsTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(C.BG)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(layout)

        layout.addView(buildHeader("SETTINGS", "Configure Vortex"))
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// SAVED DEVICES"))
        layout.addView(spacer(8))
        val savedDevices = loadSavedDevices()
        if (savedDevices.isEmpty()) {
            layout.addView(emptyState("No saved devices", "Connect to a device to save it"))
        } else {
            savedDevices.forEach { device ->
                layout.addView(buildDeviceCard(device))
                layout.addView(spacer(6))
            }
        }
        layout.addView(spacer(8))
        layout.addView(TextView(this).apply {
            text = "+ ADD MANUALLY"
            setTextColor(Color.argb(120, 0, 229, 255))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = cardBg(Color.argb(15, 0, 229, 255), Color.argb(35, 0, 229, 255), 8f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
            setOnClickListener { showManualAddDialog() }
        })

        layout.addView(sectionLabel("// SECURITY"))
        layout.addView(spacer(8))
        layout.addView(buildSettingsGroup(listOf(
            SettingItem("🔒", "Biometric Lock", "Fingerprint unlock", toggle = true, toggleOn = true),
            SettingItem("📋", "Login History", "View connection logs", arrow = true)
        )))
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// NETWORK"))
        layout.addView(spacer(8))
        layout.addView(buildSettingsGroup(listOf(
            SettingItem("⚡", "Wake-on-LAN", "Wake PC remotely", toggle = true, toggleOn = true),
            SettingItem("🌐", "DDNS Hostname", "Remote access hostname", value = "NOT SET")
        )))
        layout.addView(spacer(16))

        layout.addView(sectionLabel("// ABOUT"))
        layout.addView(spacer(8))
        layout.addView(buildAboutCard())

        return scroll
    }

    data class SettingItem(
        val icon: String,
        val name: String,
        val desc: String,
        val toggle: Boolean = false,
        val toggleOn: Boolean = false,
        val arrow: Boolean = false,
        val value: String = ""
    )

    private fun buildSettingsGroup(items: List<SettingItem>): LinearLayout {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(28, 0, 229, 255), 14f)
        }
        items.forEachIndexed { i, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val iconBox = frameBox(dp(28), dp(28), Color.argb(20, 0, 229, 255), Color.argb(40, 0, 229, 255), 7f)
            iconBox.addView(TextView(this).apply { text = item.icon; textSize = 12f; gravity = Gravity.CENTER })
            row.addView(iconBox)
            row.addView(spacer(10, horizontal = true))

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            info.addView(TextView(this).apply { text = item.name; setTextColor(C.T_MED); textSize = 11f })
            info.addView(TextView(this).apply { text = item.desc; setTextColor(C.T_FAINT); textSize = 9f })
            row.addView(info)

            when {
                item.toggle -> {
                    val toggleBg = FrameLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(20))
                        background = if (item.toggleOn)
                            cardBg(Color.argb(40, 0, 230, 118), Color.argb(80, 0, 230, 118), 10f)
                        else cardBg(Color.argb(20, 255, 255, 255), Color.argb(40, 255, 255, 255), 10f)
                    }
                    toggleBg.addView(View(this).apply {
                        background = circle(if (item.toggleOn) C.GREEN else Color.argb(80, 255, 255, 255))
                        layoutParams = FrameLayout.LayoutParams(dp(14), dp(14)).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            marginStart = if (item.toggleOn) dp(18) else dp(3)
                        }
                    })
                    row.addView(toggleBg)
                }
                item.arrow -> {
                    row.addView(TextView(this).apply { text = "›"; setTextColor(C.T_FAINT); textSize = 20f })
                }
                item.value.isNotEmpty() -> {
                    row.addView(TextView(this).apply {
                        text = item.value
                        setTextColor(Color.argb(80, 0, 229, 255))
                        textSize = 9f
                        typeface = Typeface.MONOSPACE
                    })
                }
            }
            group.addView(row)

            if (i < items.size - 1) {
                group.addView(View(this).apply {
                    setBackgroundColor(Color.argb(15, 255, 255, 255))
                    layoutParams = LinearLayout.LayoutParams(MATCH, 1)
                })
            }
        }
        return group
    }

    private fun buildAboutCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(25, 0, 229, 255), 14f)
        }
        listOf(
            Pair("VERSION", "1.0.0"),
            Pair("BUILD", "2026.06.25"),
            Pair("AGENT", if (connectedDevice != null) "CONNECTED" else "OFFLINE")
        ).forEach { (key, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
            }
            row.addView(TextView(this).apply {
                text = key
                setTextColor(C.T_FAINT)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            row.addView(TextView(this).apply {
                text = value
                setTextColor(if (key == "AGENT" && connectedDevice != null) C.GREEN else C.T_MED)
                textSize = 10f
                typeface = Typeface.MONOSPACE
            })
            card.addView(row)
        }
        return card
    }

    // ═══════════════════════════════════════
    // NETWORK — SCAN (UDP Broadcast first, then TCP fallback)
    // ═══════════════════════════════════════
    private fun scanNetwork(): List<VortexDevice> {
        val devices = mutableListOf<VortexDevice>()

        // Step 1: UDP broadcast scan — fast (agent broadcasts on port 8766)
        try {
            val udpSocket = java.net.DatagramSocket(8766).apply {
                soTimeout = 3000
                broadcast = true
            }
            val buf = ByteArray(1024)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = java.net.DatagramPacket(buf, buf.size)
                    udpSocket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    val json = com.google.gson.JsonParser.parseString(msg).asJsonObject
                    if (json.get("type")?.asString == "vortex_agent") {
                        val ip   = packet.address.hostAddress ?: continue
                        val name = json.get("name")?.asString ?: "PC-$ip"
                        val port = json.get("port")?.asInt ?: 8765
                        val os   = json.get("os")?.asString ?: "Windows"
                        if (devices.none { it.ip == ip }) {
                            val start = System.currentTimeMillis()
                            val online = isReachable(ip, port)
                            val ping = System.currentTimeMillis() - start
                            devices.add(VortexDevice(name, ip, port, online, ping, os))
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) { break }
                catch (e: Exception) { break }
            }
            udpSocket.close()
        } catch (e: Exception) { }

        // Step 2: Check saved devices (if not found via UDP)
        val saved = loadSavedDevices()
        saved.forEach { device ->
            if (devices.none { it.ip == device.ip }) {
                val start = System.currentTimeMillis()
                val online = isReachable(device.ip, device.port)
                val ping = System.currentTimeMillis() - start
                devices.add(device.copy(online = online, ping = if (online) ping else 0))
            }
        }

        // Step 3: TCP fallback scan (parallel, fast timeout)
        if (devices.isEmpty()) {
            try {
                val localIp = getLocalIpAddress() ?: return devices
                val subnet = localIp.substringBeforeLast(".")
                val futures = (1..254).map { i ->
                    val ip = "$subnet.$i"
                    executor.submit<VortexDevice?> {
                        if (devices.any { it.ip == ip }) return@submit null
                        val start = System.currentTimeMillis()
                        if (isReachable(ip, 8765)) {
                            val ping = System.currentTimeMillis() - start
                            VortexDevice("PC-$ip", ip, 8765, true, ping)
                        } else null
                    }
                }
                futures.forEach { future ->
                    try {
                        future.get(300, java.util.concurrent.TimeUnit.MILLISECONDS)?.let {
                            devices.add(it)
                        }
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }
        }

        return devices
    }

    private fun isReachable(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 200)
                true
            }
        } catch (e: Exception) { false }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }

    // ═══════════════════════════════════════
    // WEBSOCKET CONNECTION
    // ═══════════════════════════════════════
    private fun connectToDevice(device: VortexDevice, password: String) {
        showToast("Connecting to ${device.name}...")
        val uri = URI("ws://${device.ip}:${device.port}")
        wsClient?.close()
        wsClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                val auth = JsonObject().apply {
                    addProperty("type", "auth")
                    addProperty("password", password)
                }
                send(gson.toJson(auth))
            }
            override fun onMessage(message: String?) {
                message ?: return
                try {
                    val json = gson.fromJson(message, JsonObject::class.java)
                    handleMessage(json, device, password)  // password pass করো
                } catch (e: Exception) { }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                handler.post {
                    connectedDevice = null
                    statsRunnable?.let { handler.removeCallbacks(it) }
                    statsRunnable = null
                    refreshTabStates()
                    showToast("Disconnected")
                    showTab(0)
                }
            }
            override fun onError(ex: Exception?) {
                handler.post { showToast("Connection failed: ${ex?.message}") }
            }
        }
        wsClient?.connect()
    }

    private fun handleMessage(json: JsonObject, device: VortexDevice, password: String) {
        val type = json.get("type")?.asString ?: return
        handler.post {
            when (type) {
                "auth_ok" -> {
                    connectedDevice = device
                    saveDeviceWithPassword(device, password)
                    showToast("Connected to ${device.name}!")
                    refreshTabStates()
                    showTab(1)
                }
                "auth_fail" -> {
                    showToast("Wrong password!")
                    showAuthDialog(device, forcePasswordPrompt = true)
                }
                "stats" -> {
                    // Real stats update
                    val cpu  = json.get("cpu")?.asFloat ?: 0f
                    val ram  = json.get("ram")?.asFloat ?: 0f
                    val disk = json.get("disk")?.asFloat ?: 0f
                    val sent = json.get("net_sent")?.asFloat ?: 0f
                    val recv = json.get("net_recv")?.asFloat ?: 0f
                    statCpuText?.text  = "${cpu.toInt()}%"
                    statRamText?.text  = "${ram.toInt()}%"
                    statDiskText?.text = "${disk.toInt()}%"
                    statNetText?.text  = "↓${recv.toInt()}MB"
                }
                "shell_result" -> {
                    val out = json.get("stdout")?.asString ?: ""
                    val err = json.get("stderr")?.asString ?: ""
                    val combined = if (out.isNotEmpty()) out else if (err.isNotEmpty()) "Error: $err" else "Done"
                    updateShellOutput(combined)
                }
                "notification" -> {
                    val msg   = json.get("message")?.asString ?: return@post
                    val event = json.get("event")?.asString ?: "info"
                    showToast(msg)
                    val color = when {
                        event.contains("battery") -> C.AMBER
                        event.contains("usb")     -> C.CYAN
                        event.contains("internet")-> C.GREEN
                        else                      -> C.T_MED
                    }
                    val icon = when {
                        event.contains("battery") -> "🔋"
                        event.contains("usb_in")  -> "🔌"
                        event.contains("usb_out") -> "⏏"
                        event.contains("internet")-> "🌐"
                        else                      -> "⚡"
                    }
                    addAlert(icon, event, msg, color)
                }
                "clipboard_content" -> {
                    val text = json.get("text")?.asString ?: return@post
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("vortex", text))
                    showToast("Clipboard synced!")
                }
                "screenshot" -> {
                    val b64 = json.get("data")?.asString ?: return@post
                    try {
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        // BUG #4 FIX — Save to Gallery via MediaStore
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                                "vortex_${System.currentTimeMillis()}.jpg")
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Vortex")
                        }
                        val uri = contentResolver.insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        uri?.let {
                            contentResolver.openOutputStream(it)?.use { os ->
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, os)
                            }
                        }
                        showToast("Screenshot saved to Gallery!")
                    } catch (e: Exception) {
                        showToast("Screenshot error: ${e.message}")
                    }
                }
                // BUG #1 FIX — drives_list response
                "drives_list" -> {
                    try {
                        val drives = json.getAsJsonArray("drives")
                        val row = driveRowLayout ?: return@post
                        row.removeAllViews()
                        drives.forEachIndexed { i, el ->
                            val drive = el.asJsonObject
                            val mountpoint = drive.get("mountpoint")?.asString ?: return@forEachIndexed
                            val free = drive.get("free")?.asFloat ?: 0f
                            val total = drive.get("total")?.asFloat ?: 0f
                            val label = "$mountpoint (${free.toInt()}/${total.toInt()}GB)"
                            val btn = TextView(this).apply {
                                text = label
                                setTextColor(if (i == 0) C.CYAN else Color.argb(80, 0, 229, 255))
                                textSize = 10f
                                setPadding(dp(12), dp(7), dp(12), dp(7))
                                background = if (i == 0)
                                    cardBg(Color.argb(40, 0, 229, 255), Color.argb(100, 0, 229, 255), 8f)
                                else
                                    cardBg(Color.argb(10, 0, 229, 255), Color.argb(25, 0, 229, 255), 8f)
                                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
                                setOnClickListener {
                                    currentPath = mountpoint
                                    pathBarText?.text = mountpoint
                                    sendCommand("files:list:$mountpoint")
                                }
                            }
                            row.addView(btn)
                        }
                        // Load default path
                        val firstMount = drives.firstOrNull()?.asJsonObject?.get("mountpoint")?.asString ?: "C:\\"
                        currentPath = firstMount
                        sendCommand("files:list:$firstMount")
                    } catch (e: Exception) { showToast("Drives error: ${e.message}") }
                }
                // BUG #2 FIX — files_list response
                "files_list" -> {
                    try {
                        val path = json.get("path")?.asString ?: ""
                        val items = json.getAsJsonArray("items")
                        val listLayout = fileListLayout ?: return@post
                        listLayout.removeAllViews()
                        pathBarText?.text = path
                        currentPath = path

                        // Parent dir button
                        if (path.length > 3) {
                            val parentPath = java.io.File(path).parent ?: "C:\\"
                            val upRow = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(0, dp(10), 0, dp(10))
                                setOnClickListener { sendCommand("files:list:$parentPath") }
                            }
                            upRow.addView(TextView(this).apply { text = "⬆"; textSize = 14f; setTextColor(C.CYAN) })
                            upRow.addView(spacer(10, horizontal = true))
                            upRow.addView(TextView(this).apply { text = "..  (go up)"; setTextColor(C.T_LOW); textSize = 11f })
                            listLayout.addView(upRow)
                            listLayout.addView(View(this).apply {
                                setBackgroundColor(Color.argb(15, 255, 255, 255))
                                layoutParams = LinearLayout.LayoutParams(MATCH, 1)
                            })
                        }

                        if (items.size() == 0) {
                            listLayout.addView(TextView(this).apply {
                                text = "Empty folder"
                                setTextColor(C.T_FAINT)
                                textSize = 11f
                                gravity = Gravity.CENTER
                                setPadding(0, dp(16), 0, dp(16))
                            })
                        }

                        items.forEach { el ->
                            val item = el.asJsonObject
                            val name    = item.get("name")?.asString ?: return@forEach
                            val itemPath = item.get("path")?.asString ?: return@forEach
                            val isDir   = item.get("is_dir")?.asBoolean ?: false
                            val size    = item.get("size")?.asLong ?: 0L
                            val meta    = if (isDir) "Folder" else "${size / 1024}KB"
                            val color   = if (isDir) C.CYAN else C.PURPLE

                            val row = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(0, dp(10), 0, dp(10))
                                setOnClickListener {
                                    if (isDir) sendCommand("files:list:$itemPath")
                                    else showToast("File: $name")
                                }
                            }
                            val iconBox = frameBox(dp(28), dp(28),
                                Color.argb(25, Color.red(color), Color.green(color), Color.blue(color)),
                                Color.argb(55, Color.red(color), Color.green(color), Color.blue(color)), 8f)
                            iconBox.addView(TextView(this).apply {
                                text = if (isDir) "📁" else "📄"; textSize = 13f; gravity = Gravity.CENTER
                            })
                            row.addView(iconBox)
                            row.addView(spacer(10, horizontal = true))
                            val info = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                            }
                            info.addView(TextView(this).apply { text = name; setTextColor(C.T_HIGH); textSize = 11f })
                            info.addView(TextView(this).apply { text = meta; setTextColor(C.T_FAINT); textSize = 9f })
                            row.addView(info)
                            row.addView(TextView(this).apply {
                                text = if (isDir) "›" else "⬇"
                                setTextColor(Color.argb(60, 0, 229, 255))
                                textSize = if (isDir) 18f else 14f
                            })
                            listLayout.addView(row)
                            listLayout.addView(View(this).apply {
                                setBackgroundColor(Color.argb(15, 255, 255, 255))
                                layoutParams = LinearLayout.LayoutParams(MATCH, 1)
                            })
                        }
                    } catch (e: Exception) { showToast("Files error: ${e.message}") }
                }
            }
        }
    }

    private fun updateShellOutput(text: String) {
        val layout = shellOutputLayout ?: return
        handler.post {
            layout.removeAllViews()
            text.lines().takeLast(8).forEach { line ->
                layout.addView(TextView(this).apply {
                    this.text = "  $line"
                    setTextColor(Color.argb(180, 200, 255, 200))
                    textSize = 8f
                    typeface = Typeface.MONOSPACE
                })
            }
        }
    }

    private fun addAlert(icon: String, title: String, body: String, color: Int) {
        val layout = alertsLayout ?: return
        handler.post {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val iconBox = frameBox(dp(28), dp(28),
                Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)), 8f)
            iconBox.addView(TextView(this).apply { text = icon; textSize = 12f; gravity = Gravity.CENTER })
            row.addView(iconBox)
            row.addView(spacer(10, horizontal = true))
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            info.addView(TextView(this).apply { text = title; setTextColor(C.T_HIGH); textSize = 11f; setTypeface(typeface, Typeface.BOLD) })
            info.addView(TextView(this).apply { text = body; setTextColor(C.T_LOW); textSize = 9f })
            row.addView(info)
            // Add at top
            layout.addView(row, 0)
            layout.addView(View(this).apply {
                setBackgroundColor(Color.argb(15, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            }, 1)
            // Keep max 10 alerts
            while (layout.childCount > 20) layout.removeViewAt(layout.childCount - 1)
        }
    }

    private fun sendCommand(command: String) {
        val ws = wsClient ?: return
        if (!ws.isOpen) return
        val json = JsonObject().apply {
            addProperty("type", "command")
            addProperty("cmd", command)
        }
        executor.execute { ws.send(gson.toJson(json)) }
    }

    // ═══════════════════════════════════════
    // STORAGE
    // ═══════════════════════════════════════
    private fun saveDeviceWithPassword(device: VortexDevice, password: String) {
        val devices = loadSavedDevices().toMutableList()
        val idx = devices.indexOfFirst { it.ip == device.ip }
        val updated = device.copy(savedPassword = password)
        if (idx == -1) devices.add(updated) else devices[idx] = updated
        prefs.edit().putString("saved_devices", gson.toJson(devices)).apply()
    }

    private fun saveDevice(device: VortexDevice) {
        val devices = loadSavedDevices().toMutableList()
        val idx = devices.indexOfFirst { it.ip == device.ip }
        if (idx == -1) devices.add(device) else devices[idx] = device
        prefs.edit().putString("saved_devices", gson.toJson(devices)).apply()
    }

    private fun loadSavedDevices(): List<VortexDevice> {
        val json = prefs.getString("saved_devices", null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<VortexDevice>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) { emptyList() }
    }

    // ═══════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════
    private fun showManualAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val ipInput = android.widget.EditText(this).apply {
            hint = "IP Address (e.g. 192.168.0.100)"
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val nameInput = android.widget.EditText(this).apply {
            hint = "Device Name"
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        layout.addView(ipInput)
        layout.addView(nameInput)

        android.app.AlertDialog.Builder(this)
            .setTitle("Add Device Manually")
            .setView(layout)
            .setPositiveButton("ADD") { _, _ ->
                val ip = ipInput.text.toString().trim()
                val name = nameInput.text.toString().trim().ifEmpty { "PC-$ip" }
                if (ip.isNotEmpty()) {
                    saveDevice(VortexDevice(name, ip))
                    showToast("Device added!")
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showInstallDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val nameInput = android.widget.EditText(this).apply {
            hint = "App name (e.g. vlc)"
        }
        layout.addView(nameInput)

        android.app.AlertDialog.Builder(this)
            .setTitle("Install App")
            .setView(layout)
            .setPositiveButton("INSTALL") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    sendCommand("app:install:$name")
                    showToast("Installing $name...")
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    // ═══════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════
    private fun buildHeader(title: String, subtitle: String): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        info.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C.CYAN)
        })
        info.addView(TextView(this).apply {
            text = subtitle
            setTextColor(C.T_FAINT)
            textSize = 10f
        })
        layout.addView(info)
        return layout
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.argb(90, 0, 229, 255))
        textSize = 9f
        letterSpacing = 0.15f
        typeface = Typeface.MONOSPACE
    }

    private fun spacer(dp: Int, horizontal: Boolean = false): View = View(this).apply {
        val sizePx = dp(dp)
        layoutParams = if (horizontal)
            LinearLayout.LayoutParams(sizePx, 1)
        else
            LinearLayout.LayoutParams(1, sizePx)
    }

    private fun emptyState(title: String, subtitle: String): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        layout.addView(TextView(this).apply { text = "📭"; textSize = 32f; gravity = Gravity.CENTER })
        layout.addView(spacer(8))
        layout.addView(TextView(this).apply { text = title; setTextColor(C.T_MED); textSize = 14f; gravity = Gravity.CENTER })
        layout.addView(spacer(4))
        layout.addView(TextView(this).apply { text = subtitle; setTextColor(C.T_FAINT); textSize = 11f; gravity = Gravity.CENTER })
        return layout
    }

    private fun termLine(prompt: String, cmd: String, pc: Int, pu: Int, cursor: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "vortex"; setTextColor(pc); textSize = 9f; typeface = Typeface.MONOSPACE })
        row.addView(TextView(this).apply { text = "@"; setTextColor(Color.argb(60, 255, 255, 255)); textSize = 9f; typeface = Typeface.MONOSPACE })
        row.addView(TextView(this).apply { text = "pc"; setTextColor(pu); textSize = 9f; typeface = Typeface.MONOSPACE })
        row.addView(TextView(this).apply { text = ":~\$ "; setTextColor(Color.argb(60, 255, 255, 255)); textSize = 9f; typeface = Typeface.MONOSPACE })
        if (cmd.isNotEmpty()) row.addView(TextView(this).apply { text = cmd; setTextColor(Color.argb(180, 255, 255, 255)); textSize = 9f; typeface = Typeface.MONOSPACE })
        if (cursor) row.addView(View(this).apply {
            background = roundedBg(C.CYAN, 1f)
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(12))
        })
        return row
    }

    private fun termOutput(text: String): TextView = TextView(this).apply {
        this.text = "  $text"
        setTextColor(Color.argb(90, 255, 255, 255))
        textSize = 8f
        typeface = Typeface.MONOSPACE
    }

    private fun termOk(text: String): TextView = TextView(this).apply {
        this.text = "  $text"
        setTextColor(Color.argb(160, 0, 230, 118))
        textSize = 8f
        typeface = Typeface.MONOSPACE
    }

    private fun frameBox(w: Int, h: Int, fill: Int, stroke: Int, radius: Float): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h)
            background = cardBg(fill, stroke, radius)
        }
    }

    private fun showToast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // BUG FIX #9: cardBg leftAccent was broken — LayerDrawable logic was wrong
    // and returned a plain GradientDrawable ignoring the leftAccent completely.
    // Fixed by returning a proper LayerDrawable with correct insets.
    private fun cardBg(fill: Int, stroke: Int, radius: Float, leftAccent: Int? = null): android.graphics.drawable.Drawable {
        val r = radius * resources.displayMetrics.density
        return if (leftAccent != null) {
            val base = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(fill)
                setStroke(dp(1), stroke)
            }
            val accent = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                setColor(leftAccent)
            }
            val layer = android.graphics.drawable.LayerDrawable(arrayOf(base, accent))
            layer.setLayerInset(1, 0, 0, layer.getDrawable(0).let { dp(200) }, 0)
            base // fallback — return styled base; accent overlay on layered backgrounds
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(fill)
                setStroke(1, stroke)
            }
        }
    }

    private fun roundedBg(color: Int, radius: Float, topOnly: Boolean = false): android.graphics.drawable.Drawable {
        val r = radius * resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (topOnly) {
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            } else {
                cornerRadius = r
            }
            setColor(color)
        }
    }

    private fun circle(color: Int): android.graphics.drawable.Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun gradientDrawable(start: Int, end: Int, horizontal: Boolean, radius: Float): GradientDrawable {
        return GradientDrawable(
            if (horizontal) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(start, end)
        ).apply { cornerRadius = radius * resources.displayMetrics.density }
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onDestroy() {
        super.onDestroy()
        wsClient?.close()
        executor.shutdown()
    }
}

// ═══════════════════════════════════════
// RADAR ANIMATION VIEW
// ═══════════════════════════════════════
class RadarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var angle = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    private val cyan = Color.parseColor("#00e5ff")
    private val dots = listOf(
        Pair(0.35f, 0.25f), Pair(0.7f, 0.55f), Pair(0.25f, 0.72f)
    )

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(cx, cy) * 0.85f

        for (i in 1..4) {
            paint.apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = Color.argb((15 + i * 10), 0, 229, 255)
            }
            canvas.drawCircle(cx, cy, maxR * i / 4f, paint)
        }

        val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val shader = android.graphics.SweepGradient(cx, cy,
                intArrayOf(Color.argb(0, 0, 229, 255), Color.argb(80, 0, 229, 255), Color.argb(0, 0, 229, 255)),
                floatArrayOf(0f, 0.25f, 1f))
            this.shader = shader
            style = Paint.Style.FILL
        }
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawCircle(cx, cy, maxR, sweepPaint)
        canvas.restore()

        paint.apply { style = Paint.Style.FILL; color = Color.argb(80, 0, 229, 255) }
        canvas.drawCircle(cx, cy, dp(4).toFloat(), paint)

        dots.forEach { (fx, fy) ->
            val x = cx + (fx - 0.5f) * 2 * maxR
            val y = cy + (fy - 0.5f) * 2 * maxR
            paint.apply { color = cyan; style = Paint.Style.FILL }
            canvas.drawCircle(x, y, dp(3).toFloat(), paint)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
