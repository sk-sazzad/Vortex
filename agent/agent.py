"""
Vortex Agent
------------
PC & Laptop এ background এ চলবে।
✅ Auto Discovery — IP ছাড়াই Android App connect হবে
✅ Dynamic DNS — বাইরে থেকেও access (optional)
"""

import asyncio
import json
import platform
import base64
import subprocess
import threading
from datetime import datetime
import websockets

# Local modules
from config import load_config, save_config, setup_first_time, get_password_hash
from discovery import DiscoveryServer

# ===== LOAD CONFIG =====
config = load_config()
if not config.get("password"):
    config = setup_first_time(config)

PASSWORD_HASH = get_password_hash(config)
SERVER_PORT   = config.get("server_port", 8765)
DEVICE_NAME   = config.get("device_name", platform.node())
DEVICE_ID     = config.get("device_id", "unknown")
# =======================

# Optional imports
try:
    import pyautogui; HAS_PYAUTOGUI = True
except: HAS_PYAUTOGUI = False

try:
    import mss; HAS_MSS = True
except: HAS_MSS = False

try:
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
    from ctypes import cast, POINTER
    from comtypes import CLSCTX_ALL
    HAS_AUDIO = True
except: HAS_AUDIO = False

try:
    import psutil; HAS_PSUTIL = True
except: HAS_PSUTIL = False

streaming_screen = False

# ===== COMMAND HANDLERS =====

def get_system_info():
    info = {
        "os": platform.system(),
        "os_version": platform.version(),
        "machine": platform.machine(),
    }
    if HAS_PSUTIL:
        info["cpu_percent"] = psutil.cpu_percent(interval=1)
        info["ram_percent"] = psutil.virtual_memory().percent
        info["ram_total"]   = psutil.virtual_memory().total // (1024**3)
        info["disk_percent"]= psutil.disk_usage('/').percent
        battery = psutil.sensors_battery()
        if battery:
            info["battery"] = battery.percent
            info["plugged"]  = battery.power_plugged
    return info

def shutdown_pc():
    if platform.system() == "Windows":
        subprocess.run(["shutdown", "/s", "/t", "5"])
    else:
        subprocess.run(["shutdown", "-h", "now"])
    return {"status": "shutting_down"}

def restart_pc():
    if platform.system() == "Windows":
        subprocess.run(["shutdown", "/r", "/t", "5"])
    else:
        subprocess.run(["reboot"])
    return {"status": "restarting"}

def sleep_pc():
    if platform.system() == "Windows":
        subprocess.run(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"])
    return {"status": "sleeping"}

def cancel_shutdown():
    if platform.system() == "Windows":
        subprocess.run(["shutdown", "/a"])
    return {"status": "cancelled"}

def set_volume(level: int):
    if HAS_AUDIO and platform.system() == "Windows":
        try:
            devices = AudioUtilities.GetSpeakers()
            interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            volume = cast(interface, POINTER(IAudioEndpointVolume))
            volume.SetMasterVolumeLevelScalar(level / 100, None)
            return {"status": "ok", "volume": level}
        except Exception as e:
            return {"status": "error", "message": str(e)}
    return {"status": "unavailable"}

def get_volume():
    if HAS_AUDIO and platform.system() == "Windows":
        try:
            devices = AudioUtilities.GetSpeakers()
            interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            volume = cast(interface, POINTER(IAudioEndpointVolume))
            return {"status": "ok", "volume": int(volume.GetMasterVolumeLevelScalar() * 100)}
        except: pass
    return {"status": "unavailable", "volume": 0}

def take_screenshot():
    if HAS_MSS:
        import io
        from PIL import Image
        with mss.mss() as sct:
            monitor = sct.monitors[1]
            img = sct.grab(monitor)
            pil_img = Image.frombytes("RGB", img.size, img.bgra, "raw", "BGRX")
            pil_img.thumbnail((1280, 720))
            buffer = io.BytesIO()
            pil_img.save(buffer, format="JPEG", quality=85)
            return {"status": "ok", "image": base64.b64encode(buffer.getvalue()).decode()}
    return {"status": "unavailable"}

def run_command(cmd: str):
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=30)
        return {"status": "ok", "output": result.stdout, "error": result.stderr, "returncode": result.returncode}
    except subprocess.TimeoutExpired:
        return {"status": "timeout"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

def list_files(path: str = "C:\\"):
    import os
    try:
        items = []
        for item in os.scandir(path):
            items.append({"name": item.name, "is_dir": item.is_dir(), "size": item.stat().st_size if not item.is_dir() else 0})
        return {"status": "ok", "path": path, "items": items[:100]}
    except Exception as e:
        return {"status": "error", "message": str(e)}

def get_running_apps():
    if HAS_PSUTIL:
        apps = []
        for proc in psutil.process_iter(['pid', 'name', 'cpu_percent', 'memory_percent']):
            try:
                apps.append({"pid": proc.info['pid'], "name": proc.info['name'],
                             "cpu": round(proc.info['cpu_percent'], 1), "memory": round(proc.info['memory_percent'], 1)})
            except: pass
        apps.sort(key=lambda x: x['memory'], reverse=True)
        return {"status": "ok", "apps": apps[:20]}
    return {"status": "unavailable"}

def kill_app(pid: int):
    if HAS_PSUTIL:
        try:
            psutil.Process(pid).terminate()
            return {"status": "ok"}
        except Exception as e:
            return {"status": "error", "message": str(e)}
    return {"status": "unavailable"}

def get_clipboard():
    try:
        import tkinter as tk
        root = tk.Tk(); root.withdraw()
        text = root.clipboard_get(); root.destroy()
        return {"status": "ok", "text": text}
    except: return {"status": "unavailable"}

def set_clipboard(text: str):
    try:
        import tkinter as tk
        root = tk.Tk(); root.withdraw()
        root.clipboard_clear(); root.clipboard_append(text); root.update(); root.destroy()
        return {"status": "ok"}
    except: return {"status": "unavailable"}

def move_mouse(x, y):
    if HAS_PYAUTOGUI:
        w, h = pyautogui.size()
        pyautogui.moveTo(int(x * w / 1000), int(y * h / 1000))
        return {"status": "ok"}
    return {"status": "unavailable"}

def click_mouse(x, y, button="left"):
    if HAS_PYAUTOGUI:
        w, h = pyautogui.size()
        pyautogui.click(int(x * w / 1000), int(y * h / 1000), button=button)
        return {"status": "ok"}
    return {"status": "unavailable"}

def type_text(text):
    if HAS_PYAUTOGUI:
        pyautogui.typewrite(text, interval=0.05)
        return {"status": "ok"}
    return {"status": "unavailable"}

def press_key(key):
    if HAS_PYAUTOGUI:
        pyautogui.press(key)
        return {"status": "ok"}
    return {"status": "unavailable"}

# ===== COMMAND DISPATCHER =====
def execute_command(command: str, params: dict) -> dict:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] ▶️  {command}")
    handlers = {
        "system_info":     lambda: get_system_info(),
        "shutdown":        lambda: shutdown_pc(),
        "restart":         lambda: restart_pc(),
        "sleep":           lambda: sleep_pc(),
        "cancel_shutdown": lambda: cancel_shutdown(),
        "set_volume":      lambda: set_volume(params.get("level", 50)),
        "get_volume":      lambda: get_volume(),
        "screenshot":      lambda: take_screenshot(),
        "move_mouse":      lambda: move_mouse(params.get("x", 0), params.get("y", 0)),
        "click_mouse":     lambda: click_mouse(params.get("x", 0), params.get("y", 0), params.get("button", "left")),
        "type_text":       lambda: type_text(params.get("text", "")),
        "press_key":       lambda: press_key(params.get("key", "")),
        "run_command":     lambda: run_command(params.get("cmd", "")),
        "list_files":      lambda: list_files(params.get("path", "C:\\")),
        "get_apps":        lambda: get_running_apps(),
        "kill_app":        lambda: kill_app(params.get("pid", 0)),
        "get_clipboard":   lambda: get_clipboard(),
        "set_clipboard":   lambda: set_clipboard(params.get("text", "")),
    }
    handler = handlers.get(command)
    if handler:
        try: return handler()
        except Exception as e: return {"status": "error", "message": str(e)}
    return {"status": "unknown_command"}

# ===== SCREEN STREAMING =====
async def stream_screen(websocket):
    global streaming_screen
    if not HAS_MSS: return
    import io
    from PIL import Image
    with mss.mss() as sct:
        monitor = sct.monitors[1]
        while streaming_screen:
            try:
                img = sct.grab(monitor)
                pil_img = Image.frombytes("RGB", img.size, img.bgra, "raw", "BGRX")
                pil_img.thumbnail((960, 540))
                buffer = io.BytesIO()
                pil_img.save(buffer, format="JPEG", quality=70)
                await websocket.send(json.dumps({
                    "type": "screen_frame",
                    "frame": base64.b64encode(buffer.getvalue()).decode(),
                    "width": pil_img.width,
                    "height": pil_img.height
                }))
                await asyncio.sleep(0.1)
            except: break

# ===== SERVER (WebSocket) =====
async def handle_client(websocket):
    """যেকোনো client এর সাথে কথা বলো (Android App সরাসরি connect করলে)"""
    token = None
    try:
        async for message in websocket:
            data = json.loads(message)
            msg_type = data.get("type")

            if msg_type == "auth":
                import hashlib
                pwd_hash = hashlib.sha256(data.get("password", "").encode()).hexdigest()
                if pwd_hash == PASSWORD_HASH:
                    import secrets
                    token = secrets.token_hex(16)
                    await websocket.send(json.dumps({"type": "auth_success", "token": token,
                                                      "device_name": DEVICE_NAME, "device_id": DEVICE_ID,
                                                      "os": platform.system()}))
                    print(f"[{datetime.now().strftime('%H:%M:%S')}] ✅ App connected!")
                else:
                    await websocket.send(json.dumps({"type": "auth_failed"}))

            elif msg_type == "execute" and token:
                command = data.get("command")
                params  = data.get("params", {})

                if command == "start_stream":
                    global streaming_screen
                    streaming_screen = True
                    asyncio.create_task(stream_screen(websocket))
                    continue
                elif command == "stop_stream":
                    streaming_screen = False
                    continue

                result = execute_command(command, params)
                await websocket.send(json.dumps({"type": "response", "command": command, "data": result}))

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        streaming_screen = False
        print(f"[{datetime.now().strftime('%H:%M:%S')}] 📴 App disconnected")

# ===== MAIN =====
async def main():
    global config

    print("=" * 45)
    print("   🌀 Vortex Agent")
    print("=" * 45)
    print(f"🖥️  Device : {DEVICE_NAME}")
    print(f"📡 Port   : {SERVER_PORT}")

    # Auto Discovery broadcast শুরু করো
    discovery = DiscoveryServer(
        server_port=SERVER_PORT,
        password_hash=PASSWORD_HASH,
        device_name=DEVICE_NAME
    )
    discovery.start()

    local_ip = discovery.get_local_ip()
    print(f"🌐 IP      : {local_ip}  (Auto Discovery চালু)")

    # Optional DDNS info
    ddns = config.get("ddns", {})
    if ddns.get("enabled") and ddns.get("hostname"):
        print(f"🔗 DDNS    : {ddns['hostname']}  (বাইরে থেকে access)")

    print(f"\n✅ Android App খোলো → Scan করো → Connect!")
    print("=" * 45)

    # WebSocket server চালু করো
    async with websockets.serve(handle_client, "0.0.0.0", SERVER_PORT):
        await asyncio.Future()  # চলতে থাকো

if __name__ == "__main__":
    asyncio.run(main())
