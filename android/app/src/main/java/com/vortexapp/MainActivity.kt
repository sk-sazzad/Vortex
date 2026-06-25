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
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.InetAddress
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
    var os: String = "Windows"
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
    private var currentTab = 0 // 0=devices, 1=control, 2=files, 3=settings

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
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
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

        // top border
        val border = View(this).apply {
            setBackgroundColor(Color.argb(25, 0, 229, 255))
            layoutParams = LinearLayout.LayoutParams(MATCH, 1)
        }

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(58))
        }
        wrap.addView(border)
        wrap.addView(nav)

        val tabs = listOf(
            Pair("Devices", R.drawable.ic_devices_placeholder),
            Pair("Control", R.drawable.ic_control_placeholder),
            Pair("Files",   R.drawable.ic_files_placeholder),
            Pair("Settings",R.drawable.ic_settings_placeholder)
        )

        tabs.forEachIndexed { idx, (label, _) ->
            val tabView = buildTabItem(idx, label)
            nav.addView(tabView)
        }

        // Return wrapper so border shows
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

        // Icon (text emoji as placeholder)
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
        currentTab = idx
        contentArea.removeAllViews()

        // Update nav colors
        for (i in 0..3) {
            val active = i == idx
            tabIndicators[i]?.visibility = if (active) View.VISIBLE else View.INVISIBLE
            val tab = tabButtons[i] ?: continue
            val color = if (active) C.CYAN else Color.argb(80, 255, 255, 255)
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

        // Header
        layout.addView(buildHeader("VORTEX", "Find & connect your devices"))

        // Radar scan animation view
        val radarView = RadarView(this)
        layout.addView(radarView.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(180))
        })

        layout.addView(spacer(12))
        layout.addView(sectionLabel("// DEVICES FOUND"))
        layout.addView(spacer(8))

        // Scan for devices
        executor.execute {
            val devices = scanNetwork()
            handler.post {
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

        // Loading indicator while scanning
        val scanningText = TextView(this).apply {
            text = "Scanning network..."
            setTextColor(Color.argb(100, 0, 229, 255))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            letterSpacing = 0.1f
        }
        layout.addView(scanningText)

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

        // Icon
        val iconBox = frameBox(dp(36), dp(36), Color.argb(30, 0, 229, 255), Color.argb(60, 0, 229, 255), 10f)
        val iconText = TextView(this).apply {
            text = if (device.name.contains("Laptop", true)) "💻" else "🖥"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        iconBox.addView(iconText)
        card.addView(iconBox)
        card.addView(spacer(10, horizontal = true))

        // Info
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

        // Ping
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
            setOnClickListener {
                parent.removeView(this)
                executor.execute {
                    val devices = scanNetwork()
                    handler.post {
                        devices.forEach { device ->
                            parent.addView(buildDeviceCard(device))
                            parent.addView(spacer(8))
                        }
                        parent.addView(buildRescanButton(parent))
                    }
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

    private fun showAuthDialog(device: VortexDevice) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(30))
            background = roundedBg(C.BG2, 24f, topOnly = true)
        }

        // Handle
        sheet.addView(View(this).apply {
            background = circle(Color.argb(60, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(Color.argb(60, 255, 255, 255))
            }
        })

        // Device info
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
        sheet.addView(devRow)

        // Divider
        sheet.addView(View(this).apply { setBackgroundColor(Color.argb(20, 0, 229, 255)); layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { bottomMargin = dp(16) } })

        // Password label
        sheet.addView(TextView(this).apply {
            text = "// PASSWORD"
            setTextColor(Color.argb(80, 191, 95, 255))
            textSize = 9f
            letterSpacing = 0.2f
        })
        sheet.addView(spacer(6))

        // Password field
        val passField = android.widget.EditText(this).apply {
            hint = "Enter password"
            setHintTextColor(Color.argb(60, 255, 255, 255))
            setTextColor(C.T_HIGH)
            textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBg(Color.argb(20, 191, 95, 255), Color.argb(50, 191, 95, 255), 10f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
        }
        sheet.addView(passField)

        // Connect button
        val connectBtn = TextView(this).apply {
            text = "[ CONNECT → ]"
            setTextColor(C.CYAN)
            textSize = 12f
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
            background = cardBg(Color.argb(30, 0, 229, 255), Color.argb(80, 0, 229, 255), 10f)
            setOnClickListener {
                val pass = passField.text.toString()
                dialog.dismiss()
                connectToDevice(device, pass)
            }
        }
        sheet.addView(connectBtn)

        layout.addView(sheet)
        layout.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(layout)
        dialog.show()
    }

    // ═══════════════════════════════════════
    // TAB 2 — CONTROL
    // ═══════════════════════════════════════
    private fun buildControlTab(): View {
        if (connectedDevice == null) {
            return buildNotConnectedView()
        }
        return buildControlContent()
    }

    private fun buildNotConnectedView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setPadding(dp(32), 0, dp(32), 0)
        }
        layout.addView(TextView(this).apply {
            text = "⚡"
            textSize = 48f
            gravity = Gravity.CENTER
        })
        layout.addView(spacer(16))
        layout.addView(TextView(this).apply {
            text = "Not Connected"
            setTextColor(C.T_MED)
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        layout.addView(spacer(8))
        layout.addView(TextView(this).apply {
            text = "Go to Devices tab and connect to a PC first"
            setTextColor(C.T_LOW)
            textSize = 12f
            gravity = Gravity.CENTER
        })
        layout.addView(spacer(24))
        layout.addView(TextView(this).apply {
            text = "GO TO DEVICES"
            setTextColor(C.CYAN)
            textSize = 12f
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = cardBg(Color.argb(30, 0, 229, 255), Color.argb(80, 0, 229, 255), 10f)
            setOnClickListener { showTab(0) }
        })
        return layout
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

        val device = connectedDevice!!

        // Connected header
        layout.addView(buildConnectedHeader(device))
        layout.addView(spacer(16))

        // ── SCREEN ──
        layout.addView(sectionLabel("// SCREEN"))
        layout.addView(spacer(8))
        layout.addView(buildScreenCard())
        layout.addView(spacer(16))

        // ── DASHBOARD ──
        layout.addView(sectionLabel("// DASHBOARD"))
        layout.addView(spacer(8))
        layout.addView(buildStatsGrid())
        layout.addView(spacer(8))
        layout.addView(buildQuickActions())
        layout.addView(spacer(8))
        layout.addView(buildSliders())
        layout.addView(spacer(16))

        // ── SHELL ──
        layout.addView(sectionLabel("// SHELL"))
        layout.addView(spacer(8))
        layout.addView(buildShellCard())
        layout.addView(spacer(16))

        // ── MEDIA ──
        layout.addView(sectionLabel("// MEDIA"))
        layout.addView(spacer(8))
        layout.addView(buildMediaCard())
        layout.addView(spacer(16))

        // ── ALERTS ──
        layout.addView(sectionLabel("// ALERTS"))
        layout.addView(spacer(8))
        layout.addView(buildAlertsCard())
        layout.addView(spacer(16))

        // ── CLIPBOARD ──
        layout.addView(sectionLabel("// CLIPBOARD SYNC"))
        layout.addView(spacer(8))
        layout.addView(buildClipboardCard())
        layout.addView(spacer(16))

        // ── MESSAGES ──
        layout.addView(sectionLabel("// MESSAGES"))
        layout.addView(spacer(8))
        layout.addView(buildMessagesCard())
        layout.addView(spacer(16))

        // ── AUTOMATION ──
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

        // Live badge
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

        // Screen preview
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

        // Controls
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

        // Info bar
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
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val stats1 = listOf(
            Triple("34%", "CPU", C.CYAN),
            Triple("61%", "RAM", C.PURPLE),
            Triple("48%", "DISK", C.GREEN)
        )
        val stats2 = listOf(
            Triple("28%", "GPU", C.AMBER),
            Triple("52°", "TEMP", Color.parseColor("#ff5080")),
            Triple("↓48", "MB/s", C.AMBER)
        )

        stats1.forEachIndexed { i, (val_, lbl, clr) ->
            row1.addView(buildStatCard(val_, lbl, clr, i < 2))
        }
        stats2.forEachIndexed { i, (val_, lbl, clr) ->
            row2.addView(buildStatCard(val_, lbl, clr, i < 2))
        }

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

        // Progress bar
        val barBg = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(2)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            background = roundedBg(Color.argb(30, 255, 255, 255), 2f)
        }
        val pct = value.replace("%", "").replace("°", "").replace("↓", "").toIntOrNull() ?: 50
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
        trackBg.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, dp(3)).also {
                it.width = 0 // will be set after layout
            }
            background = roundedBg(color, 2f)
            post {
                layoutParams = FrameLayout.LayoutParams((parent as FrameLayout).width * value / 100, dp(3))
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

        // Quick commands
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

        // Terminal output
        val output = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBg(Color.parseColor("#01010a"), 8f)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(120)).apply { bottomMargin = dp(10) }
        }
        output.addView(termLine("vortex@pc:~$", "ipconfig", C.CYAN, C.PURPLE))
        output.addView(termOutput("IPv4: 192.168.0.100"))
        output.addView(termOk("✓ Done in 0.2s"))
        output.addView(spacer(4))
        output.addView(termLine("vortex@pc:~$", "", C.CYAN, C.PURPLE, cursor = true))
        card.addView(output)

        // Input
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

        // Progress bar
        val prog = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(2)).apply { bottomMargin = dp(6) }
            background = roundedBg(Color.argb(30, 255, 255, 255), 2f)
        }
        prog.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, dp(2))
            background = gradientDrawable(C.CYAN, C.PURPLE, horizontal = true, radius = 2f)
            post { layoutParams = FrameLayout.LayoutParams((parent as FrameLayout).width * 40 / 100, dp(2)) }
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

        // Buttons
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

        // App selector
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

        val alerts = listOf(
            Quadruple("⚡", "PC Online", "Khandoker PC started", C.GREEN),
            Quadruple("🔌", "USB Connected", "USB Drive 32GB detected", C.AMBER),
            Quadruple("⚠", "App Crashed", "chrome.exe stopped working", C.RED),
            Quadruple("🌐", "Internet Restored", "Connection back online", C.CYAN)
        )

        alerts.forEach { (icon, title, body, color) ->
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

            card.addView(row)
            card.addView(View(this).apply {
                setBackgroundColor(Color.argb(15, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            })
        }

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

        // Messages
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

        // Input
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

        // Scheduled tasks
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

            // Toggle
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

        // Drive selector
        layout.addView(sectionLabel("// DRIVES"))
        layout.addView(spacer(8))
        val driveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("C:\\", "D:\\", "E:\\").forEachIndexed { i, drive ->
            driveRow.addView(TextView(this).apply {
                text = drive
                setTextColor(if (i == 0) C.CYAN else Color.argb(80, 0, 229, 255))
                textSize = 11f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = if (i == 0)
                    cardBg(Color.argb(40, 0, 229, 255), Color.argb(100, 0, 229, 255), 8f)
                else
                    cardBg(Color.argb(10, 0, 229, 255), Color.argb(25, 0, 229, 255), 8f)
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
            })
        }
        layout.addView(driveRow)
        layout.addView(spacer(10))

        // Path bar
        val pathBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBg(Color.argb(10, 0, 229, 255), Color.argb(25, 0, 229, 255), 8f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        pathBar.addView(TextView(this).apply { text = "🏠"; textSize = 12f })
        pathBar.addView(spacer(6, horizontal = true))
        pathBar.addView(TextView(this).apply {
            text = "C:\\Users\\Khandoker"
            setTextColor(Color.argb(120, 0, 229, 255))
            textSize = 10f
            typeface = Typeface.MONOSPACE
        })
        layout.addView(pathBar)

        layout.addView(sectionLabel("// FILE BROWSER"))
        layout.addView(spacer(8))

        val files = listOf(
            Quadruple("📁", "Desktop", "24 items", true),
            Quadruple("📁", "Downloads", "142 items", true),
            Quadruple("📁", "Documents", "38 items", true),
            Quadruple("📄", "report_2026.pdf", "PDF · 2.4 MB", false),
            Quadruple("📄", "notes.txt", "TXT · 14 KB", false)
        )

        files.forEach { (icon, name, meta, isFolder) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }

            val iconBox = frameBox(dp(28), dp(28),
                if (isFolder) Color.argb(25, 0, 229, 255) else Color.argb(25, 191, 95, 255),
                if (isFolder) Color.argb(55, 0, 229, 255) else Color.argb(55, 191, 95, 255), 8f)
            iconBox.addView(TextView(this).apply { text = icon; textSize = 13f; gravity = Gravity.CENTER })
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
                text = if (isFolder) "›" else "⬇"
                setTextColor(Color.argb(60, 0, 229, 255))
                textSize = if (isFolder) 18f else 14f
            })

            layout.addView(row)
            layout.addView(View(this).apply {
                setBackgroundColor(Color.argb(15, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            })
        }

        layout.addView(spacer(16))
        layout.addView(sectionLabel("// QUICK SHARE"))
        layout.addView(spacer(8))

        val shareRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
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

        // Saved devices
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

        // Security
        layout.addView(sectionLabel("// SECURITY"))
        layout.addView(spacer(8))
        layout.addView(buildSettingsGroup(listOf(
            SettingItem("🔒", "Biometric Lock", "Fingerprint unlock", toggle = true, toggleOn = true),
            SettingItem("📋", "Login History", "View connection logs", arrow = true)
        )))
        layout.addView(spacer(16))

        // Network
        layout.addView(sectionLabel("// NETWORK"))
        layout.addView(spacer(8))
        layout.addView(buildSettingsGroup(listOf(
            SettingItem("⚡", "Wake-on-LAN", "Wake PC remotely", toggle = true, toggleOn = true),
            SettingItem("🌐", "DDNS Hostname", "Remote access hostname", value = "NOT SET")
        )))
        layout.addView(spacer(16))

        // About
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
    // NETWORK — SCAN
    // ═══════════════════════════════════════
    private fun scanNetwork(): List<VortexDevice> {
        val devices = mutableListOf<VortexDevice>()
        // First check saved devices
        val saved = loadSavedDevices()
        saved.forEach { device ->
            val start = System.currentTimeMillis()
            if (isReachable(device.ip, device.port)) {
                devices.add(device.copy(online = true, ping = System.currentTimeMillis() - start))
            } else {
                devices.add(device.copy(online = false))
            }
        }
        // Auto scan local subnet
        try {
            val localIp = getLocalIpAddress() ?: return devices
            val subnet = localIp.substringBeforeLast(".")
            for (i in 1..254) {
                val ip = "$subnet.$i"
                if (devices.any { it.ip == ip }) continue
                val start = System.currentTimeMillis()
                if (isReachable(ip, 8765)) {
                    val ping = System.currentTimeMillis() - start
                    devices.add(VortexDevice("PC-$ip", ip, 8765, true, ping))
                }
            }
        } catch (e: Exception) { }
        return devices
    }

    private fun isReachable(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 500)
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
                // Authenticate
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
                    handleMessage(json, device)
                } catch (e: Exception) { }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                handler.post {
                    connectedDevice = null
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

    private fun handleMessage(json: JsonObject, device: VortexDevice) {
        val type = json.get("type")?.asString ?: return
        handler.post {
            when (type) {
                "auth_ok" -> {
                    connectedDevice = device
                    saveDevice(device)
                    showToast("Connected to ${device.name}!")
                    showTab(1)
                }
                "auth_fail" -> showToast("Wrong password!")
                "stats" -> { /* update stats */ }
                "notification" -> showToast(json.get("message")?.asString ?: "")
            }
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
    private fun saveDevice(device: VortexDevice) {
        val devices = loadSavedDevices().toMutableList()
        if (devices.none { it.ip == device.ip }) {
            devices.add(device)
            prefs.edit().putString("saved_devices", gson.toJson(devices)).apply()
        }
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
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Add Device Manually")
            .create()
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
        dialog.setView(layout)
        dialog.setButton(android.app.AlertDialog.BUTTON_POSITIVE, "ADD") { _, _ ->
            val ip = ipInput.text.toString().trim()
            val name = nameInput.text.toString().trim().ifEmpty { "PC-$ip" }
            if (ip.isNotEmpty()) {
                saveDevice(VortexDevice(name, ip))
                showToast("Device added!")
            }
        }
        dialog.setButton(android.app.AlertDialog.BUTTON_NEGATIVE, "CANCEL") { d, _ -> d.dismiss() }
        dialog.show()
    }

    private fun showInstallDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Install App")
            .create()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val nameInput = android.widget.EditText(this).apply {
            hint = "App name (e.g. vlc)"
        }
        layout.addView(nameInput)
        dialog.setView(layout)
        dialog.setButton(android.app.AlertDialog.BUTTON_POSITIVE, "INSTALL") { _, _ ->
            val name = nameInput.text.toString().trim()
            if (name.isNotEmpty()) {
                sendCommand("app:install:$name")
                showToast("Installing $name...")
            }
        }
        dialog.setButton(android.app.AlertDialog.BUTTON_NEGATIVE, "CANCEL") { d, _ -> d.dismiss() }
        dialog.show()
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
            background = null
            // Gradient text simulation
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

    private fun cardBg(fill: Int, stroke: Int, radius: Float, leftAccent: Int? = null): android.graphics.drawable.Drawable {
        return if (leftAccent != null) {
            val layer = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius * resources.displayMetrics.density
                    setColor(fill)
                    setStroke(dp(1), stroke)
                },
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = floatArrayOf(radius * resources.displayMetrics.density, radius * resources.displayMetrics.density, 0f, 0f, 0f, 0f, radius * resources.displayMetrics.density, radius * resources.displayMetrics.density)
                    setColor(leftAccent)
                }
            ))
            layer.setLayerInset(1, 0, 0, (layer.numberOfLayers - 1) * 100, 0)
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius * resources.displayMetrics.density
                setColor(fill)
                setStroke(1, stroke)
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius * resources.displayMetrics.density
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
        start()
    }

    private val cyan = Color.parseColor("#00e5ff")
    private val dots = listOf(
        Pair(0.35f, 0.25f), Pair(0.7f, 0.55f), Pair(0.25f, 0.72f)
    )

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(cx, cy) * 0.85f

        // Rings
        for (i in 1..4) {
            paint.apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = Color.argb((15 + i * 10), 0, 229, 255)
            }
            canvas.drawCircle(cx, cy, maxR * i / 4f, paint)
        }

        // Sweep gradient
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

        // Center dot
        paint.apply { style = Paint.Style.FILL; color = Color.argb(80, 0, 229, 255) }
        canvas.drawCircle(cx, cy, dp(4).toFloat(), paint)

        // Device dots
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
