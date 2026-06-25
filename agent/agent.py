"""
Vortex Agent — Full Featured with System Tray
"""
import asyncio
import websockets
import json
import subprocess
import os
import sys
import time
import platform
import psutil
import socket
import threading
import tkinter as tk
from pathlib import Path

CONFIG_FILE = Path(__file__).parent / "config.json"

def load_config():
    if CONFIG_FILE.exists():
        with open(CONFIG_FILE) as f:
            return json.load(f)
    return {"password": "vortex123", "port": 8765, "device_name": platform.node()}

def save_config(config):
    with open(CONFIG_FILE, "w") as f:
        json.dump(config, f, indent=2)

CONFIG = load_config()
authenticated_clients = set()
agent_running = True

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except:
        return "127.0.0.1"

def show_notification(msg):
    try:
        subprocess.Popen(
            ['powershell', '-Command',
             f'Add-Type -AssemblyName System.Windows.Forms; '
             f'$n = New-Object System.Windows.Forms.NotifyIcon; '
             f'$n.Icon = [System.Drawing.SystemIcons]::Information; '
             f'$n.Visible = $true; '
             f'$n.ShowBalloonTip(3000, "Vortex Agent", "{msg}", [System.Windows.Forms.ToolTipIcon]::Info); '
             f'Start-Sleep -Seconds 4; $n.Dispose()'],
            shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
    except:
        pass

def is_autostart():
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                          r"Software\Microsoft\Windows\CurrentVersion\Run")
        winreg.QueryValueEx(k, "VortexAgent")
        return True
    except:
        return False

def set_autostart(enable):
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                          r"Software\Microsoft\Windows\CurrentVersion\Run",
                          0, winreg.KEY_SET_VALUE)
        if enable:
            winreg.SetValueEx(k, "VortexAgent", 0, winreg.REG_SZ, sys.executable)
        else:
            try:
                winreg.DeleteValue(k, "VortexAgent")
            except:
                pass
    except:
        pass

# ══════════════════════════
# SYSTEM TRAY
# ══════════════════════════
class VortexTray:
    def __init__(self):
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.protocol("WM_DELETE_WINDOW", lambda: None)
        self._build_tray_window()
        self._build_menus()
        self.root.after(100, self._tick)

    def _build_tray_window(self):
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        self.tray = tk.Toplevel(self.root)
        self.tray.overrideredirect(True)
        self.tray.attributes("-topmost", True)
        self.tray.attributes("-alpha", 0.95)
        self.tray.configure(bg="#030310")
        self.tray.geometry(f"200x36+{sw-208}+{sh-80}")
        self.tray.protocol("WM_DELETE_WINDOW", lambda: None)

        f = tk.Frame(self.tray, bg="#030310",
                    highlightbackground="#00e5ff",
                    highlightthickness=1)
        f.pack(fill="both", expand=True)

        self.dot = tk.Label(f, text="●", fg="#00e5ff", bg="#030310",
                           font=("Consolas", 10))
        self.dot.pack(side="left", padx=(6, 2))

        tk.Label(f, text="VORTEX", fg="#00e5ff", bg="#030310",
                font=("Consolas", 9, "bold")).pack(side="left")

        self.info_lbl = tk.Label(f, text="RUNNING", fg="#333355",
                                bg="#030310", font=("Consolas", 8))
        self.info_lbl.pack(side="left", padx=4)

        tk.Button(f, text="⋮", command=self._show_menu,
                 bg="#030310", fg="#00e5ff", relief="flat",
                 font=("Consolas", 12), cursor="hand2",
                 activebackground="#030310", activeforeground="#ffffff",
                 bd=0).pack(side="right", padx=4)

        f.bind("<Button-1>", self.show_status)
        self.dot.bind("<Button-1>", self.show_status)
        self.info_lbl.bind("<Button-1>", self.show_status)

    def _build_menus(self):
        self.menu = tk.Menu(self.root, tearoff=0,
                           bg="#0a0a20", fg="#ffffff",
                           activebackground="#00e5ff",
                           activeforeground="#000000",
                           font=("Consolas", 9))
        self.menu.add_command(label="  📊  Status", command=self.show_status)
        self.menu.add_command(label="  ⚙️   Settings", command=self.show_settings)
        self.menu.add_command(label="  📋  Copy IP", command=self.copy_ip)
        self.menu.add_separator()
        self.menu.add_command(label="  ❌  Exit", command=self.exit_app)

    def _show_menu(self):
        try:
            x = self.tray.winfo_x() + self.tray.winfo_width() - 10
            y = self.tray.winfo_y() - 100
            self.menu.tk_popup(x, y)
        except:
            pass

    def _tick(self):
        count = len(authenticated_clients)
        if count > 0:
            self.info_lbl.config(text=f"{count} CONNECTED", fg="#00e676")
            self.dot.config(fg="#00e676")
        else:
            self.info_lbl.config(text="WAITING...", fg="#333355")
            self.dot.config(fg="#00e5ff")
        self.root.after(2000, self._tick)

    def show_status(self, *a):
        w = tk.Toplevel(self.root)
        w.title("Vortex — Status")
        w.configure(bg="#030310")
        w.resizable(False, False)
        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        w.geometry(f"360x280+{(sw-360)//2}+{(sh-280)//2}")

        f = tk.Frame(w, bg="#030310", padx=24, pady=20)
        f.pack(fill="both", expand=True)

        tk.Label(f, text="⚡ VORTEX AGENT", fg="#00e5ff", bg="#030310",
                font=("Consolas", 13, "bold")).pack(pady=(0, 14))

        def row(k, v, vc="#ffffff"):
            r = tk.Frame(f, bg="#030310")
            r.pack(fill="x", pady=2)
            tk.Label(r, text=k, fg="#333355", bg="#030310",
                    font=("Consolas", 9), width=14, anchor="w").pack(side="left")
            tk.Label(r, text=v, fg=vc, bg="#030310",
                    font=("Consolas", 9, "bold")).pack(side="left")

        row("Device:", CONFIG.get("device_name", platform.node()), "#00e5ff")
        row("IP:", get_local_ip(), "#00e5ff")
        row("Port:", str(CONFIG.get("port", 8765)), "#00e5ff")
        row("Status:", "RUNNING ✓", "#00e676")
        row("Connected:", f"{len(authenticated_clients)} phone(s)",
            "#00e676" if authenticated_clients else "#333355")

        try:
            cpu = psutil.cpu_percent(interval=0.1)
            ram = psutil.virtual_memory()
            tk.Frame(f, bg="#1a1a2e", height=1).pack(fill="x", pady=8)
            row("CPU:", f"{cpu}%", "#00e5ff")
            row("RAM:", f"{ram.percent}% used", "#bf5fff")
        except:
            pass

        tk.Button(f, text="[ CLOSE ]", command=w.destroy,
                 bg="#030310", fg="#00e5ff", relief="flat",
                 font=("Consolas", 10), cursor="hand2",
                 activebackground="#030310").pack(pady=(14, 0))

    def show_settings(self, *a):
        w = tk.Toplevel(self.root)
        w.title("Vortex — Settings")
        w.configure(bg="#030310")
        w.resizable(False, False)
        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        w.geometry(f"400x380+{(sw-400)//2}+{(sh-380)//2}")

        f = tk.Frame(w, bg="#030310", padx=28, pady=20)
        f.pack(fill="both", expand=True)

        tk.Label(f, text="⚙️  SETTINGS", fg="#00e5ff", bg="#030310",
                font=("Consolas", 12, "bold")).pack(pady=(0, 18))

        def field(lbl, val):
            tk.Label(f, text=lbl, fg="#333355", bg="#030310",
                    font=("Consolas", 8), anchor="w").pack(fill="x", pady=(0, 2))
            e = tk.Entry(f, bg="#080818", fg="#ffffff",
                        insertbackground="#00e5ff",
                        font=("Consolas", 11), relief="flat", bd=0,
                        highlightthickness=1,
                        highlightbackground="#1a1a2e",
                        highlightcolor="#00e5ff")
            e.pack(fill="x", ipady=7, pady=(0, 10))
            e.insert(0, val)
            return e

        name_e = field("Device Name", CONFIG.get("device_name", platform.node()))
        pass_e = field("Password", CONFIG.get("password", "vortex123"))
        port_e = field("Port", str(CONFIG.get("port", 8765)))

        auto_v = tk.BooleanVar(value=is_autostart())
        cb = tk.Checkbutton(f, text="  Start with Windows",
                           variable=auto_v, bg="#030310",
                           fg="#888899", selectcolor="#030310",
                           activebackground="#030310",
                           activeforeground="#00e5ff",
                           font=("Consolas", 9))
        cb.pack(anchor="w", pady=(0, 14))

        def save():
            CONFIG["device_name"] = name_e.get().strip() or platform.node()
            CONFIG["password"] = pass_e.get().strip() or "vortex123"
            try:
                CONFIG["port"] = int(port_e.get().strip())
            except:
                CONFIG["port"] = 8765
            save_config(CONFIG)
            set_autostart(auto_v.get())
            w.destroy()
            show_notification("Settings saved!")

        tk.Button(f, text="[ SAVE & APPLY ]", command=save,
                 bg="#001a1f", fg="#00e5ff",
                 activebackground="#002a35", activeforeground="#00e5ff",
                 font=("Consolas", 11, "bold"), relief="flat",
                 pady=9, cursor="hand2").pack(fill="x")

    def copy_ip(self, *a):
        ip = get_local_ip()
        self.root.clipboard_clear()
        self.root.clipboard_append(ip)
        show_notification(f"IP copied: {ip}")

    def exit_app(self, *a):
        global agent_running
        agent_running = False
        self.root.quit()
        os._exit(0)

    def run(self):
        self.root.mainloop()

# ══════════════════════════
# COMMAND HANDLERS
# ══════════════════════════
def handle_shell(cmd):
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True,
                               text=True, timeout=30)
        return {
            "type": "shell_result", "cmd": cmd,
            "stdout": result.stdout[-3000:],
            "stderr": result.stderr[-1000:],
            "returncode": result.returncode
        }
    except Exception as e:
        return {"type": "shell_result", "cmd": cmd,
                "stdout": "", "stderr": str(e), "returncode": -1}

def handle_power(action):
    cmds = {
        "shutdown": "shutdown /s /t 10",
        "restart": "shutdown /r /t 10",
        "sleep": "rundll32.exe powrprof.dll,SetSuspendState 0,1,0",
        "cancel": "shutdown /a"
    }
    if action in cmds:
        subprocess.Popen(cmds[action], shell=True)
    return {"type": "power_ok", "action": action}

def handle_media(action):
    try:
        import ctypes
        keys = {"playpause": 0xB3, "next": 0xB0, "prev": 0xB1,
                "volumeup": 0xAF, "volumedown": 0xAE, "mute": 0xAD}
        key = keys.get(action)
        if key:
            ctypes.windll.user32.keybd_event(key, 0, 0, 0)
            ctypes.windll.user32.keybd_event(key, 0, 2, 0)
    except Exception as e:
        return {"type": "error", "msg": str(e)}
    return {"type": "media_ok", "action": action}

def get_stats():
    cpu = psutil.cpu_percent(interval=0.1)
    ram = psutil.virtual_memory()
    disk = psutil.disk_usage('C:\\')
    net = psutil.net_io_counters()
    return {
        "type": "stats",
        "cpu": round(cpu, 1),
        "ram": round(ram.percent, 1),
        "disk": round(disk.percent, 1),
        "net_sent": round(net.bytes_sent/(1024**2), 1),
        "net_recv": round(net.bytes_recv/(1024**2), 1),
    }

def handle_files(action, path=""):
    if action == "list":
        try:
            p = Path(path) if path else Path("C:/")
            items = []
            for item in p.iterdir():
                try:
                    stat = item.stat()
                    items.append({
                        "name": item.name, "path": str(item),
                        "is_dir": item.is_dir(),
                        "size": stat.st_size if not item.is_dir() else 0
                    })
                except:
                    pass
            return {"type": "files_list", "path": str(p),
                   "items": sorted(items, key=lambda x: (not x["is_dir"], x["name"].lower()))}
        except Exception as e:
            return {"type": "files_error", "msg": str(e)}
    elif action == "drives":
        drives = []
        for p in psutil.disk_partitions():
            try:
                u = psutil.disk_usage(p.mountpoint)
                drives.append({"device": p.device, "mountpoint": p.mountpoint,
                              "total": round(u.total/(1024**3), 1),
                              "free": round(u.free/(1024**3), 1),
                              "percent": u.percent})
            except:
                pass
        return {"type": "drives_list", "drives": drives}

def handle_performance(action):
    if action == "ram_clear":
        subprocess.Popen(
            'powershell -Command "Clear-RecycleBin -Force -ErrorAction SilentlyContinue"',
            shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        return {"type": "perf_ok", "action": "RAM clear started"}
    elif action == "temp_clean":
        temp = os.environ.get("TEMP", "C:\\Windows\\Temp")
        subprocess.Popen(f'del /q /f /s "{temp}\\*.*"', shell=True,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return {"type": "perf_ok", "action": "Temp files cleaning"}
    elif action == "disk_cleanup":
        subprocess.Popen("cleanmgr /sagerun:1", shell=True)
        return {"type": "perf_ok", "action": "Disk cleanup started"}
    elif action == "gaming_mode":
        for proc in ["OneDrive.exe", "SearchIndexer.exe"]:
            subprocess.Popen(f"taskkill /f /im {proc}", shell=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.Popen(
            "powercfg /setactive 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c",
            shell=True
        )
        return {"type": "perf_ok", "action": "Gaming mode ON"}
    return {"type": "perf_ok", "action": action}

# ══════════════════════════
# NOTIFICATION MONITOR
# ══════════════════════════
class Monitor:
    def __init__(self, bc, loop):
        self.bc = bc
        self.loop = loop
        self.last_bat = None
        self.last_net = None
        self.usbs = set()

    def start(self):
        threading.Thread(target=self._run, daemon=True).start()

    def _notify(self, data):
        asyncio.run_coroutine_threadsafe(self.bc(data), self.loop)

    def _run(self):
        while agent_running:
            try:
                self._bat()
                self._net()
                self._usb()
            except:
                pass
            time.sleep(5)

    def _bat(self):
        b = psutil.sensors_battery()
        if b:
            pct = int(b.percent)
            if self.last_bat and pct <= 20 and self.last_bat > 20:
                self._notify({"type": "notification", "event": "battery_low",
                             "message": f"Battery low: {pct}%"})
            self.last_bat = pct

    def _net(self):
        try:
            socket.create_connection(("8.8.8.8", 53), timeout=2)
            ok = True
        except:
            ok = False
        if self.last_net is not None and ok != self.last_net:
            self._notify({"type": "notification", "event": "internet",
                         "message": "Internet restored" if ok else "Internet lost"})
        self.last_net = ok

    def _usb(self):
        try:
            r = subprocess.run("wmic logicaldisk get deviceid,drivetype",
                             shell=True, capture_output=True, text=True)
            cur = set()
            for line in r.stdout.splitlines():
                if "2" in line:
                    parts = line.split()
                    if parts:
                        cur.add(parts[0])
            for d in cur - self.usbs:
                self._notify({"type": "notification", "event": "usb_in",
                             "message": f"USB connected: {d}"})
            for d in self.usbs - cur:
                self._notify({"type": "notification", "event": "usb_out",
                             "message": f"USB removed: {d}"})
            self.usbs = cur
        except:
            pass

# ══════════════════════════
# WEBSOCKET SERVER
# ══════════════════════════
async def broadcast(data):
    if authenticated_clients:
        msg = json.dumps(data)
        await asyncio.gather(*[c.send(msg) for c in authenticated_clients],
                            return_exceptions=True)

async def handle_client(websocket, path=None):
    authed = False
    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                t = data.get("type", "")

                if t == "auth":
                    if data.get("password") == CONFIG["password"]:
                        authed = True
                        authenticated_clients.add(websocket)
                        show_notification("📱 Phone connected!")
                        await websocket.send(json.dumps({
                            "type": "auth_ok",
                            "device_name": CONFIG["device_name"],
                            "os": platform.system()
                        }))
                    else:
                        show_notification("⚠️ Wrong password!")
                        await websocket.send(json.dumps({"type": "auth_fail"}))
                    continue

                if not authed:
                    continue

                resp = None
                cmd = data.get("cmd", "")

                if t == "command":
                    if cmd.startswith("shell:"):
                        resp = handle_shell(cmd[6:])
                    elif cmd in ["shutdown","restart","sleep","cancel"]:
                        resp = handle_power(cmd)
                    elif cmd.startswith("media:"):
                        resp = handle_media(cmd.split(":")[1])
                    elif cmd == "stats":
                        resp = get_stats()
                    elif cmd == "drives":
                        resp = handle_files("drives")
                    elif cmd.startswith("files:"):
                        parts = cmd.split(":", 2)
                        p = parts[2] if len(parts) > 2 else ""
                        resp = handle_files(parts[1], p)
                    elif cmd.startswith("perf:"):
                        resp = handle_performance(cmd.split(":")[1])
                    elif cmd.startswith("clipboard:set:"):
                        subprocess.run(f'echo {cmd[14:]}| clip', shell=True)
                        resp = {"type": "clipboard_ok"}
                    elif cmd == "clipboard:get":
                        r = subprocess.run('powershell Get-Clipboard',
                                         shell=True, capture_output=True, text=True)
                        resp = {"type": "clipboard_content", "text": r.stdout.strip()}
                    elif cmd.startswith("message:"):
                        subprocess.Popen(f'msg * "{cmd[8:]}"', shell=True)
                        resp = {"type": "message_ok"}
                    elif cmd == "screenshot":
                        try:
                            import pyautogui, io, base64
                            img = pyautogui.screenshot()
                            buf = io.BytesIO()
                            img.save(buf, format='JPEG', quality=50)
                            b64 = base64.b64encode(buf.getvalue()).decode()
                            resp = {"type": "screenshot", "data": b64}
                        except Exception as e:
                            resp = {"type": "error", "msg": str(e)}
                    elif cmd.startswith("app:install:"):
                        subprocess.Popen(
                            f"winget install {cmd[12:]} --accept-package-agreements",
                            shell=True)
                        resp = {"type": "app_ok", "action": f"Installing {cmd[12:]}"}
                    elif cmd.startswith("app:uninstall:"):
                        subprocess.Popen(f"winget uninstall {cmd[14:]}", shell=True)
                        resp = {"type": "app_ok", "action": f"Uninstalling {cmd[14:]}"}

                if resp:
                    await websocket.send(json.dumps(resp))

            except json.JSONDecodeError:
                pass
            except Exception as e:
                print(f"Error: {e}")

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        authenticated_clients.discard(websocket)
        if authed:
            show_notification("📱 Phone disconnected")

# ══════════════════════════
# DISCOVERY
# ══════════════════════════
def start_discovery():
    def loop():
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        info = json.dumps({
            "type": "vortex_agent",
            "name": CONFIG["device_name"],
            "port": CONFIG["port"],
            "os": platform.system()
        }).encode()
        while agent_running:
            try:
                udp.sendto(info, ('<broadcast>', 8766))
            except:
                pass
            time.sleep(3)
    threading.Thread(target=loop, daemon=True).start()

def run_server():
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    monitor = Monitor(broadcast, loop)
    monitor.start()
    start_discovery()
    port = CONFIG.get("port", 8765)

    async def serve():
        async with websockets.serve(handle_client, "0.0.0.0", port):
            print(f"[✓] Server running on port {port}")
            await asyncio.Future()

    loop.run_until_complete(serve())

# ══════════════════════════
# MAIN
# ══════════════════════════
def main():
    if not CONFIG_FILE.exists():
        from config import show_setup
        show_setup()
        global CONFIG
        CONFIG = load_config()

    print(f"[✓] Vortex Agent | {CONFIG['device_name']} | {get_local_ip()}:{CONFIG['port']}")
    show_notification(f"Vortex started — {get_local_ip()}")

    threading.Thread(target=run_server, daemon=True).start()

    tray = VortexTray()
    tray.run()

if __name__ == "__main__":
    main()
