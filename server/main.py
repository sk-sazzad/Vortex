"""
Vortex Server — WebSocket Hub
Manages connections between agents and Android app
"""
import asyncio
import websockets
import json

clients = {}

async def handler(websocket, path=None):
    device_id = id(websocket)
    clients[device_id] = websocket
    try:
        async for message in websocket:
            for cid, client in list(clients.items()):
                if cid != device_id:
                    try:
                        await client.send(message)
                    except:
                        clients.pop(cid, None)
    finally:
        clients.pop(device_id, None)

async def main():
    print("[✓] Vortex Server running on port 8765")
    async with websockets.serve(handler, "0.0.0.0", 8765):
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
