package com.vortexapp

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private var wsClient: VortexWSClient? = null
    private var authToken: String? = null
    private var isConnected = false

    // UI screens
    private lateinit var connectScreen: LinearLayout
    private lateinit var controlScreen: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vortex", Context.MODE_PRIVATE)
        window.statusBarColor = Color.parseColor("#0d0d1a")
        buildUI()
    }

    // ===== BUILD UI =====
    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0d0d1a"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(root)

        connectScreen = buildConnectScreen()
        controlScreen = buildControlScreen()

        root.addView(connectScreen)
        root.addView(controlScreen)
        showScreen("connect")
    }

    private fun showScreen(name: String) {
        runOnUiThread {
            connectScreen.visibility = if (name == "connect") View.VISIBLE else View.GONE
            controlScreen.visibility = if (name == "control") View.VISIBLE else View.GONE
        }
    }

    // ===== CONNECT SCREEN =====
    private fun buildConnectScreen(): LinearLayout {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(layout)

        // Logo
        layout.addView(TextView(this).apply {
            text = "🌀"; textSize = 56f; gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "Vortex"; textSize = 32f; setTextColor(Color.parseColor("#4d9fff"))
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        layout.addView(TextView(this).apply {
            text = "Device Control System"; textSize = 14f
            setTextColor(Color.parseColor("#8888aa")); gravity = Gravity.CENTER
            setPadding(0, 8, 0, 48)
        })

        // URL input
        layout.addView(makeLabel("Server URL (PC এর IP):"))
        val urlInput = makeInput(prefs.getString("last_url", "ws://192.168.0.100:8765") ?: "")
        layout.addView(urlInput)

        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

        // Password input
        layout.addView(makeLabel("Password:"))
        val passInput = makeInput("").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(passInput)

        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 30) })

        // Status
        val statusTv = TextView(this).apply {
            text = ""; textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        layout.addView(statusTv)

        // Connect button
        val connectBtn = makeButton("🔌  Connect করো", "#4d9fff")
        layout.addView(connectBtn)

        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        layout.addView(TextView(this).apply {
            text = "PC তে Vortex Agent চালু থাকতে হবে"
            textSize = 12f; setTextColor(Color.parseColor("#666688")); gravity = Gravity.CENTER
        })

        connectBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val pwd = passInput.text.toString().trim()
            if (url.isEmpty() || pwd.isEmpty()) {
                statusTv.text = "❌ URL ও Password দাও"; statusTv.setTextColor(Color.RED); return@setOnClickListener
            }
            statusTv.text = "🔄 Connecting..."; statusTv.setTextColor(Color.parseColor("#4d9fff"))
            connectBtn.isEnabled = false
            doConnect(url, pwd, statusTv) { connectBtn.isEnabled = true }
        }

        // Wrap in full-height layout
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        wrapper.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))
        return wrapper
    }

    // ===== CONTROL SCREEN =====
    private lateinit var tabControl: View
    private lateinit var tabTerminal: View
    private lateinit var tabFiles: View
    private lateinit var tabClip: View
    private lateinit var contentArea: FrameLayout
    private lateinit var deviceNameTv: TextView

    private fun buildControlScreen(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#16162a"))
            setPadding(30, 30, 30, 30)
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = TextView(this).apply {
            text = "←"; textSize = 22f; setTextColor(Color.WHITE)
            setPadding(0, 0, 30, 0)
        }
        backBtn.setOnClickListener {
            wsClient?.close(); isConnected = false; authToken = null
            showScreen("connect")
        }
        deviceNameTv = TextView(this).apply {
            text = "🖥️ Device"; textSize = 16f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(backBtn); header.addView(deviceNameTv)
        layout.addView(header)

        // Tab bar
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#16162a"))
        }
        val tabs = listOf("⚡ Control", "💻 Terminal", "📁 Files", "📋 Clip")
        val tabViews = tabs.map { name ->
            TextView(this).apply {
                text = name; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#8888aa"))
                setPadding(0, 24, 0, 24)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        tabViews.forEach { tabBar.addView(it) }
        layout.addView(tabBar)

        // Content area
        contentArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        layout.addView(contentArea)

        // Setup tabs
        tabViews[0].setOnClickListener { showTab(0, tabViews) }
        tabViews[1].setOnClickListener { showTab(1, tabViews) }
        tabViews[2].setOnClickListener { showTab(2, tabViews) }
        tabViews[3].setOnClickListener { showTab(3, tabViews) }

        showTab(0, tabViews)
        return layout
    }

    private fun showTab(index: Int, tabViews: List<TextView>) {
        tabViews.forEach { it.setTextColor(Color.parseColor("#8888aa")) }
        tabViews[index].setTextColor(Color.parseColor("#4d9fff"))
        contentArea.removeAllViews()
        val view = when (index) {
            0 -> buildControlTab()
            1 -> buildTerminalTab()
            2 -> buildFilesTab()
            3 -> buildClipboardTab()
            else -> buildControlTab()
        }
        contentArea.addView(view)
    }

    // ===== CONTROL TAB =====
    private fun buildControlTab(): ScrollView {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(30, 20, 30, 30)
        }
        scroll.addView(layout)

        // Power section
        layout.addView(makeSectionTitle("🔴 Power Control"))
        val powerRow = makeButtonRow()
        powerRow.addView(makeActionBtn("⏻", "Shutdown", "#e74c3c") {
            showConfirm("Shutdown", "PC বন্ধ করবে?") { sendCmd("shutdown") }
        })
        powerRow.addView(makeActionBtn("🔄", "Restart", "#f39c12") {
            showConfirm("Restart", "PC restart করবে?") { sendCmd("restart") }
        })
        powerRow.addView(makeActionBtn("😴", "Sleep", "#5b4fc4") { sendCmd("sleep") })
        powerRow.addView(makeActionBtn("❌", "Cancel", "#444444") { sendCmd("cancel_shutdown") })
        layout.addView(powerRow)

        // Volume section
        layout.addView(makeSectionTitle("🔊 Volume"))
        val volRow = makeButtonRow()
        listOf(0 to "🔇", 25 to "🔈", 50 to "🔉", 75 to "🔊", 100 to "📢").forEach { (v, icon) ->
            volRow.addView(makeActionBtn(icon, "$v%", "#1e1e35") {
                sendCmdParams("set_volume", mapOf("level" to v))
            })
        }
        layout.addView(volRow)

        // Info section
        layout.addView(makeSectionTitle("📊 Info & Tools"))
        val infoRow = makeButtonRow()
        infoRow.addView(makeActionBtn("📊", "System", "#1e1e35") { sendCmd("system_info") })
        infoRow.addView(makeActionBtn("📸", "Screenshot", "#1e1e35") { sendCmd("screenshot") })
        infoRow.addView(makeActionBtn("📱", "Apps", "#1e1e35") { sendCmd("get_apps") })
        infoRow.addView(makeActionBtn("📋", "Clipboard", "#1e1e35") { sendCmd("get_clipboard") })
        layout.addView(infoRow)

        return scroll
    }

    // ===== TERMINAL TAB =====
    private fun buildTerminalTab(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(30, 20, 30, 30)
        }

        val cmdInput = makeInput("dir").apply { setSingleLine() }
        val outputTv = TextView(this).apply {
            text = "Output এখানে আসবে..."; textSize = 12f
            setTextColor(Color.parseColor("#33ff88"))
            setBackgroundColor(Color.parseColor("#05050f"))
            setPadding(20, 20, 20, 20); setTextIsSelectable(true)
            minHeight = 500
        }

        layout.addView(makeLabel("Command লেখো:"))
        layout.addView(cmdInput)

        // Quick commands
        val quickScroll = HorizontalScrollView(this)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 12)
        }
        listOf("dir", "ipconfig", "tasklist", "systeminfo", "whoami").forEach { c ->
            val chip = TextView(this).apply {
                text = c; textSize = 12f; setTextColor(Color.parseColor("#4d9fff"))
                setBackgroundColor(Color.parseColor("#1e1e35"))
                setPadding(24, 12, 24, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 12 }
            }
            chip.setOnClickListener {
                cmdInput.setText(c)
                outputTv.text = "⏳ Running: $c"
                sendCmdWithCallback("run_command", mapOf("cmd" to c)) { result ->
                    outputTv.text = result.get("output")?.asString
                        ?: result.get("error")?.asString ?: "No output"
                }
            }
            quickRow.addView(chip)
        }
        quickScroll.addView(quickRow)
        layout.addView(quickScroll)

        val runBtn = makeButton("▶  Run করো", "#2ecc71")
        runBtn.setOnClickListener {
            val c = cmdInput.text.toString().trim()
            if (c.isNotEmpty()) {
                outputTv.text = "⏳ Running: $c"
                sendCmdWithCallback("run_command", mapOf("cmd" to c)) { result ->
                    outputTv.text = result.get("output")?.asString
                        ?: result.get("error")?.asString ?: "No output"
                }
            }
        }
        layout.addView(runBtn)
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })

        val outScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 600
            )
        }
        outScroll.addView(outputTv)
        layout.addView(outScroll)

        return layout
    }

    // ===== FILES TAB =====
    private fun buildFilesTab(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(30, 20, 30, 30)
        }

        val pathInput = makeInput("C:\\")
        val fileListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        layout.addView(makeLabel("Path:"))
        layout.addView(pathInput)

        // Quick paths
        val quickScroll = HorizontalScrollView(this)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 12)
        }
        listOf("C:\\", "C:\\Users", "C:\\Downloads", "D:\\").forEach { p ->
            val chip = TextView(this).apply {
                text = p; textSize = 11f; setTextColor(Color.parseColor("#4d9fff"))
                setBackgroundColor(Color.parseColor("#1e1e35"))
                setPadding(20, 10, 20, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 10 }
            }
            chip.setOnClickListener {
                pathInput.setText(p)
                browseFiles(p, fileListLayout, pathInput)
            }
            quickRow.addView(chip)
        }
        quickScroll.addView(quickRow)
        layout.addView(quickScroll)

        val browseBtn = makeButton("📂  Browse করো", "#4d9fff")
        browseBtn.setOnClickListener {
            browseFiles(pathInput.text.toString().trim(), fileListLayout, pathInput)
        }
        layout.addView(browseBtn)
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        scroll.addView(fileListLayout)
        layout.addView(scroll)

        return layout
    }

    private fun browseFiles(path: String, container: LinearLayout, pathInput: EditText) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = "⏳ Loading..."; setTextColor(Color.parseColor("#8888aa")); textSize = 14f
        })
        sendCmdWithCallback("list_files", mapOf("path" to path)) { result ->
            container.removeAllViews()
            val items = result.getAsJsonArray("items") ?: return@sendCmdWithCallback
            items.forEach { el ->
                val item = el.asJsonObject
                val name = item.get("name").asString
                val isDir = item.get("is_dir").asBoolean
                val size = if (!isDir) item.get("size").asLong else 0L

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 20, 0, 20)
                    setBackgroundColor(Color.TRANSPARENT)
                }
                val divider = View(this).apply {
                    setBackgroundColor(Color.parseColor("#2a2a45"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                }

                val icon = TextView(this).apply {
                    text = if (isDir) "📁" else "📄"; textSize = 20f
                    setPadding(0, 0, 20, 0)
                }
                val nameCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                nameCol.addView(TextView(this).apply {
                    text = name; setTextColor(Color.WHITE); textSize = 14f
                })
                if (!isDir) {
                    nameCol.addView(TextView(this).apply {
                        text = "${size / 1024} KB"
                        setTextColor(Color.parseColor("#8888aa")); textSize = 11f
                    })
                }
                row.addView(icon); row.addView(nameCol)
                if (isDir) {
                    row.addView(TextView(this).apply {
                        text = "›"; setTextColor(Color.parseColor("#8888aa")); textSize = 20f
                    })
                    row.setOnClickListener {
                        val newPath = if (path.endsWith("\\")) "$path$name" else "$path\\$name"
                        pathInput.setText(newPath)
                        browseFiles(newPath, container, pathInput)
                    }
                }
                container.addView(row)
                container.addView(divider)
            }
        }
    }

    // ===== CLIPBOARD TAB =====
    private fun buildClipboardTab(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(30, 20, 30, 30)
        }

        val clipTv = TextView(this).apply {
            text = "PC এর clipboard এখানে আসবে..."
            setTextColor(Color.parseColor("#8888aa")); textSize = 14f
            setBackgroundColor(Color.parseColor("#16162a"))
            setPadding(20, 20, 20, 20); setTextIsSelectable(true)
            minHeight = 200
        }

        val getBtn = makeButton("📋  PC থেকে Clipboard নাও", "#4d9fff")
        getBtn.setOnClickListener {
            sendCmdWithCallback("get_clipboard", emptyMap()) { result ->
                val text = result.get("text")?.asString ?: "Empty"
                clipTv.text = text; clipTv.setTextColor(Color.WHITE)
                // Copy to mobile clipboard
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("vortex", text))
                Toast.makeText(this, "✅ Mobile clipboard এ copy হয়েছে!", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(getBtn)
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })
        layout.addView(clipTv)
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

        layout.addView(makeLabel("Mobile থেকে PC তে পাঠাও:"))
        val sendInput = EditText(this).apply {
            hint = "এখানে লেখো..."; setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666688"))
            setBackgroundColor(Color.parseColor("#1e1e35"))
            setPadding(20, 20, 20, 20); minLines = 4
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(sendInput)
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })

        val sendBtn = makeButton("📤  PC তে পাঠাও", "#2ecc71")
        sendBtn.setOnClickListener {
            val text = sendInput.text.toString()
            if (text.isNotEmpty()) {
                sendCmdParams("set_clipboard", mapOf("text" to text))
                Toast.makeText(this, "✅ PC তে পাঠানো হয়েছে!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(sendBtn)

        return layout
    }

    // ===== WEBSOCKET =====
    private fun doConnect(url: String, password: String, statusTv: TextView, onDone: () -> Unit) {
        Thread {
            try {
                var resolved = false
                wsClient = VortexWSClient(URI(url),
                    onOpen = {
                        sendRaw(mapOf("type" to "auth", "password" to password))
                    },
                    onMessage = { msg ->
                        val data = gson.fromJson(msg, JsonObject::class.java)
                        when (data.get("type")?.asString) {
                            "auth_success" -> {
                                authToken = data.get("token").asString
                                val deviceName = data.get("device_name")?.asString ?: "Device"
                                val os = data.get("os")?.asString ?: "Windows"
                                prefs.edit().putString("last_url", url).apply()
                                resolved = true
                                runOnUiThread {
                                    statusTv.text = "✅ Connected!"
                                    statusTv.setTextColor(Color.parseColor("#2ecc71"))
                                    deviceNameTv.text = "🖥️ $deviceName"
                                    isConnected = true
                                    onDone()
                                    showScreen("control")
                                }
                            }
                            "auth_failed" -> {
                                resolved = true
                                runOnUiThread {
                                    statusTv.text = "❌ Password ভুল!"
                                    statusTv.setTextColor(Color.RED)
                                    onDone()
                                }
                            }
                            "response" -> handleResponse(data)
                        }
                    },
                    onClose = {
                        if (isConnected) runOnUiThread {
                            showAlert("Disconnected", "Connection হারিয়ে গেছে!")
                            isConnected = false; showScreen("connect")
                        }
                    },
                    onError = {
                        if (!resolved) runOnUiThread {
                            statusTv.text = "❌ Connection failed!"
                            statusTv.setTextColor(Color.RED)
                            onDone()
                        }
                    }
                )
                wsClient?.connect()
            } catch (e: Exception) {
                runOnUiThread {
                    statusTv.text = "❌ ${e.message}"; statusTv.setTextColor(Color.RED); onDone()
                }
            }
        }.start()
    }

    private val responseCallbacks = mutableMapOf<String, (JsonObject) -> Unit>()

    private fun handleResponse(data: JsonObject) {
        val cmd = data.get("command")?.asString ?: return
        val result = data.getAsJsonObject("data") ?: JsonObject()

        // Check callbacks first
        responseCallbacks.remove(cmd)?.let { cb ->
            runOnUiThread { cb(result) }
            return
        }

        // Default handlers
        runOnUiThread {
            when (cmd) {
                "system_info" -> showAlert("📊 System Info",
                    "OS: ${result.get("os")?.asString}\n" +
                    "CPU: ${result.get("cpu_percent")?.asString}%\n" +
                    "RAM: ${result.get("ram_percent")?.asString}%\n" +
                    "Disk: ${result.get("disk_percent")?.asString}%\n" +
                    "Battery: ${result.get("battery")?.asString ?: "N/A"}%")
                "get_clipboard" -> showAlert("📋 Clipboard",
                    result.get("text")?.asString ?: "Empty")
                "get_apps" -> {
                    val apps = result.getAsJsonArray("apps")
                    val text = apps?.take(10)?.joinToString("\n") {
                        val a = it.asJsonObject
                        "• ${a.get("name").asString} (${a.get("memory").asFloat.let { "%.1f".format(it) }}%)"
                    } ?: "No apps"
                    showAlert("📱 Running Apps", text)
                }
                "screenshot" -> showAlert("📸 Screenshot", "✅ Screenshot নেওয়া হয়েছে!")
                "shutdown"   -> showAlert("🔴 Shutdown", "✅ PC 5 সেকেন্ডে বন্ধ হবে")
                "restart"    -> showAlert("🔄 Restart",  "✅ PC 5 সেকেন্ডে restart হবে")
                "sleep"      -> showAlert("😴 Sleep",    "✅ PC sleep হচ্ছে")
            }
        }
    }

    fun sendCmd(command: String) = sendCmdParams(command, emptyMap())

    fun sendCmdParams(command: String, params: Map<String, Any>) {
        val data = mutableMapOf<String, Any>("type" to "execute", "command" to command, "params" to params)
        authToken?.let { data["token"] = it }
        sendRaw(data)
    }

    fun sendCmdWithCallback(command: String, params: Map<String, Any>, cb: (JsonObject) -> Unit) {
        responseCallbacks[command] = cb
        sendCmdParams(command, params)
    }

    private fun sendRaw(data: Map<String, Any>) {
        wsClient?.send(gson.toJson(data))
    }

    // ===== HELPERS =====
    private fun makeLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 10); typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun makeSectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Color.parseColor("#8888aa"))
        setPadding(0, 24, 0, 16); letterSpacing = 0.1f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun makeInput(default: String) = EditText(this).apply {
        setText(default); setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#666688"))
        setBackgroundColor(Color.parseColor("#1e1e35"))
        setPadding(24, 20, 24, 20)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun makeButton(text: String, color: String) = Button(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(color))
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 130
        )
    }

    private fun makeButtonRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 }
    }

    private fun makeActionBtn(icon: String, label: String, color: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(color))
            layoutParams = LinearLayout.LayoutParams(0, 200, 1f).apply { marginEnd = 10 }
            setPadding(8, 8, 8, 8)
            addView(TextView(this@MainActivity).apply {
                text = icon; textSize = 24f; gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 10f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            setOnClickListener { onClick() }
        }
    }

    private fun showAlert(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    private fun showConfirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(message)
            .setPositiveButton("হ্যাঁ") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null).show()
    }
}

// ===== WEBSOCKET CLIENT =====
class VortexWSClient(
    uri: URI,
    private val onOpen: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onError: () -> Unit
) : WebSocketClient(uri) {
    override fun onOpen(h: ServerHandshake?) = onOpen()
    override fun onMessage(message: String?) = message?.let { onMessage(it) } ?: Unit
    override fun onClose(code: Int, reason: String?, remote: Boolean) = onClose()
    override fun onError(ex: Exception?) = onError()
}
