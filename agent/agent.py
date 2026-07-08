"""
Vortex Agent — Silent Background Service with System Tray
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

def show_notification(title, msg):
    try:
        subprocess.Popen(
            ['powershell', '-WindowStyle', 'Hidden', '-Command',
             f'Add-Type -AssemblyName System.Windows.Forms;'
             f'$n=New-Object System.Windows.Forms.NotifyIcon;'
             f'$n.Icon=[System.Drawing.SystemIcons]::Application;'
             f'$n.Visible=$true;'
             f'$n.ShowBalloonTip(3000,"{title}","{msg}",[System.Windows.Forms.ToolTipIcon]::None);'
             f'Start-Sleep 4;$n.Dispose()'],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
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
            exe = sys.executable if not getattr(sys, 'frozen', False) else sys.executable
            winreg.SetValueEx(k, "VortexAgent", 0, winreg.REG_SZ, f'"{exe}"')
        else:
            try:
                winreg.DeleteValue(k, "VortexAgent")
            except:
                pass
    except:
        pass

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
        return {"type": "media_ok", "action": action}
    except Exception as e:
        return {"type": "error", "msg": str(e)}

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
        "net_sent": round(net.bytes_sent / (1024**2), 1),
        "net_recv": round(net.bytes_recv / (1024**2), 1),
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
            "powercfg /setactive 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c", shell=True)
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
                    fresh = load_config()
                    if data.get("password") == fresh.get("password", CONFIG["password"]):
                        CONFIG.update(fresh)  # config sync করো
                        authed = True
                        authenticated_clients.add(websocket)
                        show_notification("Vortex", "Phone connected!")
                        await websocket.send(json.dumps({
                            "type": "auth_ok",
                            "device_name": CONFIG["device_name"],
                            "os": platform.system()
                        }))
                    else:
                        await websocket.send(json.dumps({"type": "auth_fail"}))
                    continue

                if not authed:
                    continue

                resp = None
                cmd = data.get("cmd", "")

                if t == "command":
                    if cmd.startswith("shell:"):
                        resp = handle_shell(cmd[6:])
                    elif cmd in ["shutdown", "restart", "sleep", "cancel"]:
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
                        text = cmd[14:]
                        subprocess.run(
                            ['powershell', '-Command', f'Set-Clipboard -Value "{text}"'],
                            shell=False
                        )
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
                pass

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        authenticated_clients.discard(websocket)

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
            await asyncio.Future()

    loop.run_until_complete(serve())

# ══════════════════════════
# MAIN
# ══════════════════════════
def main():
    """
    Open the Vortex UI window.
    """
    from config import VortexApp

    # If previously set to active, start server in background immediately

    VortexApp()

if __name__ == "__main__":
    main()
