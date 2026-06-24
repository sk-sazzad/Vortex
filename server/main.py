"""
Vortex Server
-----------------
PC তে চলবে। সব device এর মাঝখানে কাজ করবে।
"""

import asyncio
import json
import base64
import hashlib
import secrets
from datetime import datetime
from typing import Dict, Set
import websockets
from websockets.server import WebSocketServerProtocol

# ========== CONFIG ==========
PASSWORD = "your_password_here"   # এটা তোমার password দিয়ে বদলাও
SERVER_HOST = "0.0.0.0"
SERVER_PORT = 8765
# ============================

# Connected devices & apps
devices: Dict[str, dict] = {}       # device_id → {ws, name, type, info}
controllers: Set[WebSocketServerProtocol] = set()  # Android app connections
tokens: Set[str] = set()            # Valid session tokens

def hash_password(pwd: str) -> str:
    return hashlib.sha256(pwd.encode()).hexdigest()

def generate_token() -> str:
    return secrets.token_hex(32)

async def broadcast_device_list():
    """সব controller কে device list পাঠাও"""
    device_list = []
    for did, info in devices.items():
        device_list.append({
            "id": did,
            "name": info["name"],
            "type": info["type"],
            "os": info.get("os", "unknown"),
            "online": True,
            "last_seen": info.get("last_seen", "")
        })

    msg = json.dumps({"type": "device_list", "devices": device_list})
    for ctrl in controllers.copy():
        try:
            await ctrl.send(msg)
        except:
            controllers.discard(ctrl)

async def handle_connection(websocket: WebSocketServerProtocol):
    """নতুন connection এলে handle করো"""
    client_type = None
    device_id = None

    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                msg_type = data.get("type")

                # ===== AUTH =====
                if msg_type == "auth":
                    pwd = data.get("password", "")
                    if hash_password(pwd) == hash_password(PASSWORD):
                        token = generate_token()
                        tokens.add(token)
                        await websocket.send(json.dumps({
                            "type": "auth_success",
                            "token": token
                        }))
                        print(f"[{datetime.now().strftime('%H:%M:%S')}] ✅ Auth success")
                    else:
                        await websocket.send(json.dumps({
                            "type": "auth_failed",
                            "message": "Wrong password"
                        }))
                        print(f"[{datetime.now().strftime('%H:%M:%S')}] ❌ Auth failed")

                # ===== DEVICE REGISTER (Agent থেকে) =====
                elif msg_type == "register_device":
                    token = data.get("token", "")
                    if token not in tokens:
                        await websocket.send(json.dumps({"type": "error", "message": "Unauthorized"}))
                        continue

                    device_id = data.get("device_id")
                    client_type = "device"
                    devices[device_id] = {
                        "ws": websocket,
                        "name": data.get("name", "Unknown Device"),
                        "type": data.get("device_type", "pc"),
                        "os": data.get("os", "windows"),
                        "last_seen": datetime.now().strftime("%H:%M:%S")
                    }
                    print(f"[{datetime.now().strftime('%H:%M:%S')}] 🖥️  Device connected: {data.get('name')}")
                    await broadcast_device_list()

                # ===== CONTROLLER REGISTER (Android App থেকে) =====
                elif msg_type == "register_controller":
                    token = data.get("token", "")
                    if token not in tokens:
                        await websocket.send(json.dumps({"type": "error", "message": "Unauthorized"}))
                        continue

                    client_type = "controller"
                    controllers.add(websocket)
                    print(f"[{datetime.now().strftime('%H:%M:%S')}] 📱 Controller connected")
                    await broadcast_device_list()

                # ===== COMMAND (Controller → Device) =====
                elif msg_type == "command":
                    token = data.get("token", "")
                    if token not in tokens:
                        continue

                    target_id = data.get("target_device")
                    if target_id in devices:
                        target_ws = devices[target_id]["ws"]
                        try:
                            await target_ws.send(json.dumps({
                                "type": "execute",
                                "command": data.get("command"),
                                "params": data.get("params", {})
                            }))
                        except:
                            del devices[target_id]
                            await broadcast_device_list()

                # ===== RESPONSE (Device → Controller) =====
                elif msg_type == "response":
                    for ctrl in controllers.copy():
                        try:
                            await ctrl.send(json.dumps({
                                "type": "device_response",
                                "device_id": device_id,
                                "data": data.get("data"),
                                "command": data.get("command")
                            }))
                        except:
                            controllers.discard(ctrl)

                # ===== SCREEN STREAM =====
                elif msg_type == "screen_frame":
                    for ctrl in controllers.copy():
                        try:
                            await ctrl.send(json.dumps({
                                "type": "screen_frame",
                                "device_id": device_id,
                                "frame": data.get("frame"),
                                "width": data.get("width"),
                                "height": data.get("height")
                            }))
                        except:
                            controllers.discard(ctrl)

            except json.JSONDecodeError:
                pass

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        # Cleanup on disconnect
        if client_type == "device" and device_id:
            if device_id in devices:
                print(f"[{datetime.now().strftime('%H:%M:%S')}] 🔴 Device disconnected: {devices[device_id]['name']}")
                del devices[device_id]
                await broadcast_device_list()
        elif client_type == "controller":
            controllers.discard(websocket)
            print(f"[{datetime.now().strftime('%H:%M:%S')}] 📴 Controller disconnected")

async def main():
    print("=" * 40)
    print("   Vortex Server")
    print("=" * 40)
    print(f"🚀 Starting on port {SERVER_PORT}...")
    print(f"🔑 Password: {PASSWORD}")
    print(f"📡 Waiting for connections...")
    print("=" * 40)

    async with websockets.serve(handle_connection, SERVER_HOST, SERVER_PORT):
        await asyncio.Future()  # Run forever

if __name__ == "__main__":
    asyncio.run(main())
