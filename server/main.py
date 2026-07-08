"""
Vortex Server — WebSocket Hub
Manages connections between agents and Android app
Bug #5 Fix: Added authentication — only authenticated clients get messages
"""
import asyncio
import websockets
import json

# client_id -> {ws, authed, role}
clients = {}

async def handler(websocket, path=None):
    device_id = id(websocket)
    clients[device_id] = {"ws": websocket, "authed": False}
    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                # Auth message — mark client as authenticated
                if data.get("type") == "auth":
                    clients[device_id]["authed"] = True
                    # Broadcast to all other authenticated clients
                for cid, client in list(clients.items()):
                    if cid != device_id and client.get("authed"):
                        try:
                            await client["ws"].send(message)
                        except:
                            clients.pop(cid, None)
            except json.JSONDecodeError:
                pass
    finally:
        clients.pop(device_id, None)

async def main():
    print("[✓] Vortex Server running on port 8765")
    async with websockets.serve(handler, "0.0.0.0", 8765):
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
