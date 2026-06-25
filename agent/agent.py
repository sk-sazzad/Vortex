"""
Vortex Agent — Full Featured
Handles all commands from Android app
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
import hashlib
from pathlib import Path

# ══════════════════════════
# CONFIG
# ══════════════════════════
CONFIG_FILE = Path(__file__).parent / "config.json"

def load_config():
    if CONFIG_FILE.exists():
        with open(CONFIG_FILE) as f:
            return json.load(f)
    return {
        "password": "vortex123",
        "port": 8765,
        "device_name": platform.node()
    }

CONFIG = load_config()
CLIENTS = set()

# ══════════════════════════
# SYSTEM INFO
# ══════════════════════════
def get_stats():
    cpu = psutil.cpu_percent(interval=0.1)
    ram = psutil.virtual_memory()
    disk = psutil.disk_usage('/')
    net = psutil.net_io_counters()
    temps = {}
    try:
        t = psutil.sensors_temperatures()
        if t:
            for name, entries in t.items():
                if entries:
                    temps[name] = entries[0].current
    except:
        pass
    gpu_usage = 0
    try:
        import GPUtil
        gpus = GPUtil.getGPUs()
        if gpus:
            gpu_usage = gpus[0].load * 100
    except:
        pass

    return {
        "type": "stats",
        "cpu": round(cpu, 1),
        "ram": round(ram.percent, 1),
        "ram_total": round(ram.total / (1024**3), 1),
        "ram_used": round(ram.used / (1024**3), 1),
        "disk": round(disk.percent, 1),
        "disk_total": round(disk.total / (1024**3), 1),
        "net_sent": round(net.bytes_sent / (1024**2), 1),
        "net_recv": round(net.bytes_recv / (1024**2), 1),
        "gpu": round(gpu_usage, 1),
        "temps": temps
    }

# ══════════════════════════
# COMMAND HANDLERS
# ══════════════════════════
def handle_shell(cmd):
    """Run shell command and return output"""
    try:
        result = subprocess.run(
            cmd, shell=True, capture_output=True, text=True, timeout=30
        )
        return {
            "type": "shell_result",
            "cmd": cmd,
            "stdout": result.stdout[-3000:] if result.stdout else "",
            "stderr": result.stderr[-1000:] if result.stderr else "",
            "returncode": result.returncode
        }
    except subprocess.TimeoutExpired:
        return {"type": "shell_result", "cmd": cmd, "stdout": "", "stderr": "Timeout", "returncode": -1}
    except Exception as e:
        return {"type": "shell_result", "cmd": cmd, "stdout": "", "stderr": str(e), "returncode": -1}

def handle_media(action):
    """Control media playback"""
    import ctypes
    VK_MEDIA_PLAY_PAUSE = 0xB3
    VK_MEDIA_NEXT_TRACK = 0xB0
    VK_MEDIA_PREV_TRACK = 0xB1
    VK_VOLUME_UP = 0xAF
    VK_VOLUME_DOWN = 0xAE
    VK_VOLUME_MUTE = 0xAD

    key_map = {
        "playpause": VK_MEDIA_PLAY_PAUSE,
        "next": VK_MEDIA_NEXT_TRACK,
        "prev": VK_MEDIA_PREV_TRACK,
        "volumeup": VK_VOLUME_UP,
        "volumedown": VK_VOLUME_DOWN,
        "mute": VK_VOLUME_MUTE
    }
    key = key_map.get(action)
    if key:
        ctypes.windll.user32.keybd_event(key, 0, 0, 0)
        ctypes.windll.user32.keybd_event(key, 0, 2, 0)
    return {"type": "media_ok", "action": action}

def handle_power(action):
    """Power management"""
    commands = {
        "shutdown": "shutdown /s /t 10",
        "restart": "shutdown /r /t 10",
        "sleep": "rundll32.exe powrprof.dll,SetSuspendState 0,1,0",
        "cancel": "shutdown /a"
    }
    cmd = commands.get(action)
    if cmd:
        subprocess.Popen(cmd, shell=True)
    return {"type": "power_ok", "action": action}

def handle_volume(level):
    """Set system volume"""
    try:
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
        from comtypes import CLSCTX_ALL
        devices = AudioUtilities.GetSpeakers()
        interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
        volume = interface.QueryInterface(IAudioEndpointVolume)
        volume.SetMasterVolumeLevelScalar(level / 100, None)
        return {"type": "volume_ok", "level": level}
    except Exception as e:
        # Fallback
        subprocess.Popen(f"nircmd.exe setsysvolume {int(level * 655.35)}", shell=True)
        return {"type": "volume_ok", "level": level}

def handle_files(action, path="", dest=""):
    """File operations"""
    import os, glob
    if action == "list":
        try:
            p = Path(path) if path else Path("C:/")
            if not p.exists():
                return {"type": "files_error", "msg": "Path not found"}
            items = []
            for item in p.iterdir():
                try:
                    stat = item.stat()
                    items.append({
                        "name": item.name,
                        "path": str(item),
                        "is_dir": item.is_dir(),
                        "size": stat.st_size if not item.is_dir() else 0,
                        "modified": stat.st_mtime
                    })
                except:
                    pass
            return {"type": "files_list", "path": str(p), "items": sorted(items, key=lambda x: (not x["is_dir"], x["name"].lower()))}
        except Exception as e:
            return {"type": "files_error", "msg": str(e)}

    elif action == "drives":
        drives = []
        for partition in psutil.disk_partitions():
            try:
                usage = psutil.disk_usage(partition.mountpoint)
                drives.append({
                    "device": partition.device,
                    "mountpoint": partition.mountpoint,
                    "total": round(usage.total / (1024**3), 1),
                    "used": round(usage.used / (1024**3), 1),
                    "free": round(usage.free / (1024**3), 1),
                    "percent": usage.percent
                })
            except:
                pass
        return {"type": "drives_list", "drives": drives}

def handle_apps(action, app_name=""):
    """App management via winget"""
    if action == "list":
        result = subprocess.run(
            "winget list", shell=True, capture_output=True, text=True, timeout=30
        )
        return {"type": "apps_list", "output": result.stdout}
    elif action == "install":
        subprocess.Popen(f"winget install {app_name} --accept-package-agreements --accept-source-agreements", shell=True)
        return {"type": "app_installing", "name": app_name}
    elif action == "uninstall":
        subprocess.Popen(f"winget uninstall {app_name}", shell=True)
        return {"type": "app_uninstalling", "name": app_name}

def handle_print(printer_name, file_path):
    """Remote print"""
    try:
        import win32print
        import win32api
        win32api.ShellExecute(0, "print", file_path, None, ".", 0)
        return {"type": "print_ok", "file": file_path}
    except Exception as e:
        return {"type": "print_error", "msg": str(e)}

def get_printers():
    """List available printers"""
    try:
        import win32print
        printers = []
        for p in win32print.EnumPrinters(win32print.PRINTER_ENUM_LOCAL):
            printers.append({"name": p[2], "status": p[0]})
        return {"type": "printers_list", "printers": printers}
    except:
        # Fallback
        result = subprocess.run("wmic printer get name,status", shell=True, capture_output=True, text=True)
        return {"type": "printers_list", "raw": result.stdout}

# ══════════════════════════
# NOTIFICATIONS MONITOR
# ══════════════════════════
class NotificationMonitor:
    def __init__(self, broadcast_fn):
        self.broadcast = broadcast_fn
        self.running = False
        self.last_battery = None
        self.last_net = None
        self.usb_devices = set()
        self.loop = None  # stored at start time

    def start(self):
        self.running = True
        self.loop = asyncio.get_event_loop()  # capture loop from async context
        threading.Thread(target=self._monitor_loop, daemon=True).start()

    def _monitor_loop(self):
        while self.running:
            try:
                self._check_battery()
                self._check_network()
                self._check_usb()
            except:
                pass
            time.sleep(5)

    def _check_battery(self):
        battery = psutil.sensors_battery()
        if battery:
            pct = int(battery.percent)
            if self.last_battery is None:
                self.last_battery = pct
            elif pct <= 20 and self.last_battery > 20:
                asyncio.run_coroutine_threadsafe(
                    self.broadcast({"type": "notification", "event": "battery_low", "message": f"Battery low: {pct}%", "level": pct}),
                    self.loop
                )
            self.last_battery = pct

    def _check_network(self):
        try:
            socket.create_connection(("8.8.8.8", 53), timeout=2)
            net_ok = True
        except:
            net_ok = False
        if self.last_net is None:
            self.last_net = net_ok
        elif net_ok != self.last_net:
            event = "internet_restored" if net_ok else "internet_lost"
            msg = "Internet restored" if net_ok else "Internet connection lost"
            asyncio.run_coroutine_threadsafe(
                self.broadcast({"type": "notification", "event": event, "message": msg}),
                self.loop
            )
        self.last_net = net_ok

    def _check_usb(self):
        current = set()
        try:
            result = subprocess.run("wmic logicaldisk get deviceid,drivetype", shell=True, capture_output=True, text=True)
            for line in result.stdout.splitlines():
                if "2" in line:  # removable drive
                    parts = line.split()
                    if parts:
                        current.add(parts[0])
        except:
            pass
        new_devices = current - self.usb_devices
        removed = self.usb_devices - current
        for dev in new_devices:
            asyncio.run_coroutine_threadsafe(
                self.broadcast({"type": "notification", "event": "usb_connected", "message": f"USB device connected: {dev}"}),
                self.loop
            )
        for dev in removed:
            asyncio.run_coroutine_threadsafe(
                self.broadcast({"type": "notification", "event": "usb_removed", "message": f"USB device removed: {dev}"}),
                self.loop
            )
        self.usb_devices = current

# ══════════════════════════
# WEBSOCKET SERVER
# ══════════════════════════
authenticated_clients = set()

async def broadcast(data):
    if authenticated_clients:
        msg = json.dumps(data)
        await asyncio.gather(
            *[client.send(msg) for client in authenticated_clients],
            return_exceptions=True
        )

async def handle_client(websocket, path=None):
    print(f"[+] Client connected: {websocket.remote_address}")
    authed = False

    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                msg_type = data.get("type", "")

                # AUTH
                if msg_type == "auth":
                    if data.get("password") == CONFIG["password"]:
                        authed = True
                        authenticated_clients.add(websocket)
                        await websocket.send(json.dumps({
                            "type": "auth_ok",
                            "device_name": CONFIG["device_name"],
                            "os": platform.system(),
                            "os_version": platform.version()
                        }))
                        print(f"[✓] Client authenticated")
                    else:
                        await websocket.send(json.dumps({"type": "auth_fail"}))
                    continue

                if not authed:
                    continue

                # COMMANDS
                response = None

                if msg_type == "command":
                    cmd = data.get("cmd", "")

                    # SHELL
                    if cmd.startswith("shell:"):
                        response = handle_shell(cmd[6:])

                    # POWER
                    elif cmd in ["shutdown", "restart", "sleep", "cancel"]:
                        response = handle_power(cmd)

                    # MEDIA
                    elif cmd.startswith("media:"):
                        parts = cmd.split(":")
                        if len(parts) >= 2:
                            response = handle_media(parts[1])

                    # VOLUME
                    elif cmd.startswith("volume:"):
                        level = int(cmd.split(":")[1])
                        response = handle_volume(level)

                    # FILES
                    elif cmd.startswith("files:"):
                        parts = cmd.split(":", 2)
                        action = parts[1] if len(parts) > 1 else ""
                        path = parts[2] if len(parts) > 2 else ""
                        response = handle_files(action, path)

                    # APPS
                    elif cmd.startswith("app:"):
                        parts = cmd.split(":", 2)
                        action = parts[1] if len(parts) > 1 else ""
                        name = parts[2] if len(parts) > 2 else ""
                        response = handle_apps(action, name)

                    # STATS
                    elif cmd == "stats":
                        response = get_stats()

                    # DRIVES
                    elif cmd == "drives":
                        response = handle_files("drives")

                    # PRINTERS
                    elif cmd == "printers":
                        response = get_printers()

                    # CLIPBOARD
                    elif cmd.startswith("clipboard:set:"):
                        text = cmd[14:]
                        subprocess.run(
                            ['powershell', '-Command', f'Set-Clipboard -Value "{text}"'],
                            shell=False
                        )
                        response = {"type": "clipboard_ok"}

                    elif cmd == "clipboard:get":
                        result = subprocess.run('powershell Get-Clipboard', shell=True, capture_output=True, text=True)
                        response = {"type": "clipboard_content", "text": result.stdout.strip()}

                    # MESSAGE
                    elif cmd.startswith("message:"):
                        msg_text = cmd[8:]
                        # Show popup on PC
                        subprocess.Popen(
                            f'msg * "{msg_text}"',
                            shell=True
                        )
                        response = {"type": "message_ok"}

                    # SCREENSHOT
                    elif cmd == "screenshot":
                        try:
                            import pyautogui
                            img = pyautogui.screenshot()
                            import io, base64
                            buf = io.BytesIO()
                            img.save(buf, format='JPEG', quality=50)
                            b64 = base64.b64encode(buf.getvalue()).decode()
                            response = {"type": "screenshot", "data": b64}
                        except Exception as e:
                            response = {"type": "error", "msg": str(e)}

                if response:
                    await websocket.send(json.dumps(response))

                # Auto-send stats every 2 seconds
                elif msg_type == "subscribe_stats":
                    async def send_stats():
                        while websocket in authenticated_clients:
                            stats = get_stats()
                            await websocket.send(json.dumps(stats))
                            await asyncio.sleep(2)
                    asyncio.create_task(send_stats())

            except json.JSONDecodeError:
                pass
            except Exception as e:
                print(f"[!] Error handling message: {e}")

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        authenticated_clients.discard(websocket)
        print(f"[-] Client disconnected")

# ══════════════════════════
# DISCOVERY BROADCAST
# ══════════════════════════
def start_discovery():
    """Broadcast presence on local network"""
    import socket as sock
    udp = sock.socket(sock.AF_INET, sock.SOCK_DGRAM)
    udp.setsockopt(sock.SOL_SOCKET, sock.SO_BROADCAST, 1)

    device_info = json.dumps({
        "type": "vortex_agent",
        "name": CONFIG["device_name"],
        "port": CONFIG["port"],
        "os": platform.system()
    }).encode()

    def broadcast_loop():
        while True:
            try:
                udp.sendto(device_info, ('<broadcast>', 8766))
            except:
                pass
            time.sleep(3)

    threading.Thread(target=broadcast_loop, daemon=True).start()
    print("[→] Discovery broadcast started")

# ══════════════════════════
# STARTUP NOTIFICATION
# ══════════════════════════
async def send_startup_notification():
    """Notify app that PC is online"""
    await asyncio.sleep(1)
    await broadcast({
        "type": "notification",
        "event": "pc_online",
        "message": f"{CONFIG['device_name']} is online"
    })

# ══════════════════════════
# MAIN
# ══════════════════════════
async def main():
    port = CONFIG.get("port", 8765)
    print(f"""
╔══════════════════════════════════╗
║         VORTEX AGENT             ║
║   Device: {CONFIG['device_name']:<22}║
║   Port:   {port:<22}║
╚══════════════════════════════════╝
    """)

    # Start discovery
    start_discovery()

    # Start notification monitor
    monitor = NotificationMonitor(broadcast)
    monitor.start()

    # Start WebSocket server
    print(f"[✓] WebSocket server starting on port {port}...")
    async with websockets.serve(handle_client, "0.0.0.0", port):
        print(f"[✓] Agent running! Waiting for connections...")
        asyncio.create_task(send_startup_notification())
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[!] Agent stopped")
