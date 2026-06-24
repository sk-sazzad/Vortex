"""
Vortex — Android Discovery Client
-----------------------------------
Same WiFi তে সব Vortex Agent খোঁজো।
"""

import socket
import json
import time

DISCOVERY_PORT = 5353
SERVICE_NAME   = "VORTEX_AGENT"

def scan_for_agents(timeout: int = 4) -> list:
    """
    WiFi তে scan করো, সব Vortex Agent এর list দাও।
    Returns: [{"name": ..., "ip": ..., "port": ..., "url": ..., "os": ...}]
    """
    found   = []
    seen    = set()
    sock    = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.settimeout(1)

    try:
        sock.bind(("", DISCOVERY_PORT))
        deadline = time.time() + timeout

        while time.time() < deadline:
            try:
                data, addr = sock.recvfrom(1024)
                msg = json.loads(data.decode())

                if msg.get("service") == SERVICE_NAME:
                    ip = msg.get("ip") or addr[0]
                    if ip not in seen:
                        seen.add(ip)
                        found.append({
                            "name": msg.get("name", "Unknown Device"),
                            "ip":   ip,
                            "port": msg.get("port", 8765),
                            "os":   msg.get("os", "Windows"),
                            "url":  f"ws://{ip}:{msg.get('port', 8765)}"
                        })
            except socket.timeout:
                continue
            except:
                pass
    finally:
        sock.close()

    return found
