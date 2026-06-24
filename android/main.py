"""
Vortex Android App
------------------
✅ Auto Scan — IP ছাড়াই WiFi তে Agent খুঁজে বের করো
✅ Manual URL — চাইলে নিজে IP/DDNS দিতে পারো
"""

import asyncio
import json
import threading
from kivy.app import App
from kivy.uix.screenmanager import ScreenManager, Screen, SlideTransition
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.popup import Popup
from kivy.uix.spinner import Spinner
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics import Color, Rectangle, RoundedRectangle
import websockets
from discovery_client import scan_for_agents

# Colors
BG      = (0.08, 0.08, 0.12, 1)
CARD    = (0.14, 0.14, 0.2,  1)
ACCENT  = (0.25, 0.55, 1.0,  1)
GREEN   = (0.2,  0.8,  0.4,  1)
RED     = (0.9,  0.3,  0.3,  1)
YELLOW  = (1.0,  0.7,  0.2,  1)
TEXT    = (1,    1,    1,    1)
SUBTEXT = (0.6,  0.6,  0.7,  1)

websocket_conn = None
auth_token     = None
current_device = None

def run_async(coro):
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(coro)
    loop.close()

async def ws_send(data: dict):
    global websocket_conn, auth_token
    if websocket_conn and auth_token:
        data["token"] = auth_token
        try: await websocket_conn.send(json.dumps(data))
        except: pass

def make_btn(text, color, height=58, font=15):
    btn = Button(
        text=text, size_hint=(1, None), height=height,
        background_color=color, font_size=f'{font}sp',
        background_normal='', bold=True
    )
    return btn

# ===== CONNECT SCREEN =====
class ConnectScreen(Screen):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.found_agents = []
        self.build_ui()

    def build_ui(self):
        root = BoxLayout(orientation='vertical', padding=[20, 40, 20, 20], spacing=15)
        with root.canvas.before:
            Color(*BG); Rectangle(size=Window.size, pos=(0,0))

        # Logo
        root.add_widget(Label(text="🌀 Vortex", font_size='36sp', bold=True,
                               color=ACCENT, size_hint=(1, 0.12)))
        root.add_widget(Label(text="Device Control System", font_size='14sp',
                               color=SUBTEXT, size_hint=(1, 0.06)))

        # ── AUTO SCAN ──
        root.add_widget(Label(text="📡  Same WiFi তে খোঁজো", font_size='13sp',
                               color=SUBTEXT, size_hint=(1, 0.05), halign='left'))

        scan_btn = make_btn("🔍  Scan করো", GREEN, height=54)
        scan_btn.bind(on_press=self.start_scan)
        root.add_widget(scan_btn)

        self.scan_status = Label(text="", color=SUBTEXT, font_size='13sp', size_hint=(1, 0.05))
        root.add_widget(self.scan_status)

        # Found agents list
        self.agent_scroll = ScrollView(size_hint=(1, 0.22))
        self.agent_grid   = GridLayout(cols=1, spacing=6, size_hint_y=None)
        self.agent_grid.bind(minimum_height=self.agent_grid.setter('height'))
        self.agent_scroll.add_widget(self.agent_grid)
        root.add_widget(self.agent_scroll)

        # ── MANUAL ──
        root.add_widget(Label(text="🔗  অথবা নিজে দাও (IP বা DDNS hostname)",
                               font_size='12sp', color=SUBTEXT, size_hint=(1, 0.05), halign='left'))

        self.url_input = TextInput(
            text="ws://192.168.1.X:8765", multiline=False,
            size_hint=(1, None), height=46,
            background_color=(0.18, 0.18, 0.25, 1), foreground_color=TEXT, font_size='13sp'
        )
        root.add_widget(self.url_input)

        self.pass_input = TextInput(
            hint_text="Password", password=True, multiline=False,
            size_hint=(1, None), height=46,
            background_color=(0.18, 0.18, 0.25, 1), foreground_color=TEXT, font_size='13sp'
        )
        root.add_widget(self.pass_input)

        manual_btn = make_btn("🔌  Manual Connect", ACCENT, height=52)
        manual_btn.bind(on_press=self.manual_connect)
        root.add_widget(manual_btn)

        self.status = Label(text="", color=SUBTEXT, font_size='13sp', size_hint=(1, 0.07))
        root.add_widget(self.status)

        self.add_widget(root)

    # ── SCAN ──
    def start_scan(self, *_):
        self.agent_grid.clear_widgets()
        self.scan_status.text = "🔄 Scanning..."
        threading.Thread(target=self._do_scan, daemon=True).start()

    def _do_scan(self):
        agents = scan_for_agents(timeout=4)
        self.found_agents = agents
        Clock.schedule_once(lambda dt: self._show_agents(agents), 0)

    def _show_agents(self, agents):
        self.agent_grid.clear_widgets()
        if not agents:
            self.scan_status.text = "❌ কোনো device পাওয়া যায়নি"
            return
        self.scan_status.text = f"✅ {len(agents)} টা device পাওয়া গেছে!"
        for a in agents:
            btn = make_btn(f"🖥️  {a['name']}   [{a['os']}]  •  {a['ip']}", CARD, height=52, font=13)
            btn.color = TEXT
            btn.bind(on_press=lambda x, ag=a: self._connect_to_agent(ag))
            self.agent_grid.add_widget(btn)

    def _connect_to_agent(self, agent):
        self.url_input.text = agent["url"]
        pwd = self.pass_input.text.strip()
        if not pwd:
            self.status.text = "🔑 Password দাও তারপর connect করো"
            return
        self._do_connect(agent["url"], pwd)

    # ── MANUAL ──
    def manual_connect(self, *_):
        url = self.url_input.text.strip()
        pwd = self.pass_input.text.strip()
        if not url or not pwd:
            self.status.text = "❌ URL ও Password দাও"
            return
        self._do_connect(url, pwd)

    def _do_connect(self, url, password):
        self.status.text = "🔄 Connecting..."
        threading.Thread(target=run_async, args=(self._async_connect(url, password),), daemon=True).start()

    async def _async_connect(self, url, password):
        global websocket_conn, auth_token
        try:
            websocket_conn = await websockets.connect(url, ping_interval=20, ping_timeout=10)

            # Auth
            await websocket_conn.send(json.dumps({"type": "auth", "password": password}))
            resp = json.loads(await websocket_conn.recv())

            if resp.get("type") == "auth_success":
                auth_token = resp["token"]
                device_info = {
                    "name": resp.get("device_name", "Device"),
                    "id":   resp.get("device_id", ""),
                    "os":   resp.get("os", "Windows"),
                    "url":  url
                }
                Clock.schedule_once(lambda dt: self._on_connected(device_info), 0)
                await self._listen()
            else:
                Clock.schedule_once(lambda dt: setattr(self.status, 'text', "❌ Password ভুল!"), 0)

        except Exception as e:
            Clock.schedule_once(lambda dt: setattr(self.status, 'text', f"❌ {str(e)[:60]}"), 0)

    async def _listen(self):
        try:
            async for msg in websocket_conn:
                data = json.loads(msg)
                app  = App.get_running_app()
                t    = data.get("type")
                if t == "response":
                    Clock.schedule_once(lambda dt, d=data: app.on_response(d), 0)
                elif t == "screen_frame":
                    Clock.schedule_once(lambda dt, d=data: app.on_frame(d), 0)
        except: pass

    def _on_connected(self, device_info):
        global current_device
        current_device = device_info
        self.status.text = "✅ Connected!"
        app = App.get_running_app()
        app.root.get_screen('control').load_device(device_info)
        app.root.transition = SlideTransition(direction='left')
        app.root.current = 'control'


# ===== CONTROL SCREEN =====
class ControlScreen(Screen):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.build_ui()

    def build_ui(self):
        self.root_layout = BoxLayout(orientation='vertical', padding=[15,15,15,10], spacing=8)
        with self.root_layout.canvas.before:
            Color(*BG); Rectangle(size=Window.size, pos=(0,0))

        # Header
        header = BoxLayout(size_hint=(1, None), height=50, spacing=10)
        back   = make_btn("←", (0.25,0.25,0.3,1), height=50, font=16)
        back.size_hint = (None, 1); back.width = 50
        back.bind(on_press=self.go_back)
        self.title_lbl = Label(text="Device", font_size='18sp', bold=True, color=ACCENT)
        header.add_widget(back)
        header.add_widget(self.title_lbl)
        self.root_layout.add_widget(header)

        # Tab bar
        tabs = BoxLayout(size_hint=(1, None), height=44, spacing=4)
        for name in ["⚡ Control", "🖥️ Screen", "💻 Terminal", "📁 Files"]:
            b = Button(text=name, background_color=(0.18,0.18,0.25,1),
                       background_normal='', font_size='12sp')
            b.bind(on_press=lambda x, n=name: self.switch_tab(n))
            tabs.add_widget(b)
        self.root_layout.add_widget(tabs)

        # Content
        self.scroll  = ScrollView(size_hint=(1,1))
        self.content = GridLayout(cols=2, spacing=8, padding=4, size_hint_y=None)
        self.content.bind(minimum_height=self.content.setter('height'))
        self.scroll.add_widget(self.content)
        self.root_layout.add_widget(self.scroll)

        self.add_widget(self.root_layout)

    def load_device(self, info):
        self.title_lbl.text = f"🖥️  {info['name']}"
        self.switch_tab("⚡ Control")

    def switch_tab(self, tab):
        self.content.clear_widgets()
        self.content.cols = 2
        if   "Control"  in tab: self._tab_control()
        elif "Screen"   in tab: self._tab_screen()
        elif "Terminal" in tab: self._tab_terminal()
        elif "Files"    in tab: self._tab_files()

    # ── CONTROL TAB ──
    def _tab_control(self):
        items = [
            ("🔴 Shutdown",      RED,    "shutdown",        {}),
            ("🔄 Restart",       YELLOW, "restart",         {}),
            ("😴 Sleep",         ACCENT, "sleep",           {}),
            ("❌ Cancel Shutdown",(0.4,0.4,0.5,1),"cancel_shutdown",{}),
            ("🔊 Volume 80%",    ACCENT, "set_volume",      {"level":80}),
            ("🔈 Volume 20%",    ACCENT, "set_volume",      {"level":20}),
            ("📸 Screenshot",    GREEN,  "screenshot",      {}),
            ("📋 Clipboard",     ACCENT, "get_clipboard",   {}),
            ("📊 System Info",   ACCENT, "system_info",     {}),
            ("📱 Running Apps",  ACCENT, "get_apps",        {}),
        ]
        for text, color, cmd, params in items:
            b = make_btn(text, color, height=56, font=13)
            b.bind(on_press=lambda x, c=cmd, p=params: self.send(c, p))
            self.content.add_widget(b)

    # ── SCREEN TAB ──
    def _tab_screen(self):
        self.content.cols = 1
        for text, color, cmd in [
            ("▶️  Live Screen শুরু", GREEN,  "start_stream"),
            ("⏹️  Live Screen বন্ধ",  RED,   "stop_stream"),
            ("📸 Screenshot",         ACCENT, "screenshot"),
        ]:
            b = make_btn(text, color, height=60)
            b.bind(on_press=lambda x, c=cmd: self.send(c, {}))
            self.content.add_widget(b)

    # ── TERMINAL TAB ──
    def _tab_terminal(self):
        self.content.cols = 1
        self.content.add_widget(Label(text="Command:", color=TEXT, size_hint=(1,None), height=28, font_size='13sp'))
        self.cmd_in = TextInput(hint_text="dir, ipconfig, tasklist...", multiline=False,
                                 size_hint=(1,None), height=48,
                                 background_color=(0.18,0.18,0.25,1), foreground_color=TEXT, font_size='13sp')
        self.content.add_widget(self.cmd_in)
        run = make_btn("▶️  Run", GREEN, height=50)
        run.bind(on_press=self._run_cmd)
        self.content.add_widget(run)
        self.term_out = TextInput(text="Output এখানে আসবে...", readonly=True, multiline=True,
                                   size_hint=(1,None), height=320,
                                   background_color=(0.04,0.04,0.08,1),
                                   foreground_color=(0.3,1,0.3,1), font_size='12sp')
        self.content.add_widget(self.term_out)

    def _run_cmd(self, *_):
        cmd = self.cmd_in.text.strip()
        if cmd:
            self.term_out.text = f"⏳ Running: {cmd}"
            self.send("run_command", {"cmd": cmd})

    # ── FILES TAB ──
    def _tab_files(self):
        self.content.cols = 1
        self.content.add_widget(Label(text="Path:", color=TEXT, size_hint=(1,None), height=28, font_size='13sp'))
        self.path_in = TextInput(text="C:\\", multiline=False, size_hint=(1,None), height=48,
                                  background_color=(0.18,0.18,0.25,1), foreground_color=TEXT, font_size='13sp')
        self.content.add_widget(self.path_in)
        b = make_btn("📂 Browse", ACCENT, height=50)
        b.bind(on_press=lambda x: self.send("list_files", {"path": self.path_in.text.strip()}))
        self.content.add_widget(b)
        self.file_out = TextInput(text="Files এখানে দেখাবে...", readonly=True, multiline=True,
                                   size_hint=(1,None), height=400,
                                   background_color=(0.1,0.1,0.15,1), foreground_color=TEXT, font_size='12sp')
        self.content.add_widget(self.file_out)

    # ── SEND ──
    def send(self, command, params):
        threading.Thread(target=run_async, args=(ws_send({
            "type": "execute", "command": command, "params": params
        }),), daemon=True).start()

    # ── RESPONSE ──
    def on_response(self, data):
        cmd    = data.get("command")
        result = data.get("data", {})

        if cmd == "run_command" and hasattr(self, 'term_out'):
            self.term_out.text = result.get("output") or result.get("error") or "No output"

        elif cmd == "list_files" and hasattr(self, 'file_out'):
            items = result.get("items", [])
            text  = f"📂 {result.get('path','')}\n\n"
            for i in items:
                text += f"{'📁' if i['is_dir'] else '📄'} {i['name']}\n"
            self.file_out.text = text

        elif cmd in ("system_info", "get_clipboard", "get_apps", "screenshot"):
            self._popup(cmd, result)

    def _popup(self, cmd, result):
        if cmd == "system_info":
            body = (f"OS: {result.get('os','')}\n"
                    f"CPU: {result.get('cpu_percent','?')}%\n"
                    f"RAM: {result.get('ram_percent','?')}%\n"
                    f"Disk: {result.get('disk_percent','?')}%\n"
                    f"Battery: {result.get('battery','N/A')}%")
        elif cmd == "get_clipboard":
            body = result.get("text", "Empty")
        elif cmd == "get_apps":
            apps = result.get("apps", [])
            body = "\n".join(f"• {a['name']} ({a['memory']:.1f}%)" for a in apps[:10])
        elif cmd == "screenshot":
            body = "✅ Screenshot নেওয়া হয়েছে!"
        else:
            body = str(result)

        layout = BoxLayout(orientation='vertical', padding=15, spacing=10)
        layout.add_widget(Label(text=body, color=TEXT, halign='left', font_size='13sp'))
        close = make_btn("Close", ACCENT, height=46)
        layout.add_widget(close)
        p = Popup(title=cmd.replace("_"," ").title(), content=layout,
                  size_hint=(0.92, 0.65), background_color=CARD)
        close.bind(on_press=p.dismiss)
        p.open()

    def go_back(self, *_):
        app = App.get_running_app()
        app.root.transition = SlideTransition(direction='right')
        app.root.current = 'connect'


# ===== APP =====
class VortexApp(App):
    def build(self):
        Window.clearcolor = BG
        sm = ScreenManager()
        sm.add_widget(ConnectScreen(name='connect'))
        sm.add_widget(ControlScreen(name='control'))
        return sm

    def on_response(self, data):
        self.root.get_screen('control').on_response(data)

    def on_frame(self, data):
        pass  # Screen streaming — পরের phase

if __name__ == "__main__":
    VortexApp().run()
