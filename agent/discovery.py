"""
Vortex — Auto Discovery Module
--------------------------------
Agent চালু হলে WiFi তে নিজেকে broadcast করবে।
Android App সেই broadcast শুনে automatically connect হবে।
কোনো IP লাগবে না।
"""

import socket
import json
import threading
import time
import platform

DISCOVERY_PORT = 5353       # UDP broadcast port
BROADCAST_INTERVAL = 2      # প্রতি ২ সেকেন্ডে broadcast
SERVICE_NAME = "VORTEX_AGENT"

class DiscoveryServer:
    """Agent এর পক্ষ থেকে broadcast করে"""

    def __init__(self, server_port: int, password_hash: str, device_name: str):
        self.server_port = server_port
        self.password_hash = password_hash
        self.device_name = device_name
        self.running = False
        self._thread = None

    def get_local_ip(self) -> str:
        """PC এর local IP automatically বের করো"""
        try:
            # Google DNS তে connect করার ভান করলে local IP পাওয়া যায়
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except:
            return "127.0.0.1"

    def start(self):
        """Background এ broadcast শুরু করো"""
        self.running = True
        self._thread = threading.Thread(target=self._broadcast_loop, daemon=True)
        self._thread.start()
        ip = self.get_local_ip()
        print(f"[Discovery] 📡 Broadcasting on {ip}:{self.server_port}")

    def stop(self):
        self.running = False

    def _broadcast_loop(self):
        """UDP broadcast loop"""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

        local_ip = self.get_local_ip()

        message = json.dumps({
            "service": SERVICE_NAME,
            "name": self.device_name,
            "ip": local_ip,
            "port": self.server_port,
            "os": platform.system(),
            "version": "1.0"
        }).encode()

        while self.running:
            try:
                # সব device কে broadcast পাঠাও
                sock.sendto(message, ("<broadcast>", DISCOVERY_PORT))
                time.sleep(BROADCAST_INTERVAL)
            except Exception as e:
                time.sleep(2)

        sock.close()


class DiscoveryClient:
    """Android App এর পক্ষ থেকে Agent খোঁজে"""

    def __init__(self, timeout: int = 5):
        self.timeout = timeout

    def scan(self) -> list:
        """
        Network scan করো, সব Vortex Agent খোঁজো।
        Returns: [{"name": ..., "ip": ..., "port": ..., "os": ...}, ...]
        """
        found = []
        seen_ips = set()

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.settimeout(self.timeout)

        try:
            sock.bind(("", DISCOVERY_PORT))
            deadline = time.time() + self.timeout

            while time.time() < deadline:
                try:
                    data, addr = sock.recvfrom(1024)
                    message = json.loads(data.decode())

                    if message.get("service") == SERVICE_NAME:
                        ip = message.get("ip") or addr[0]
                        if ip not in seen_ips:
                            seen_ips.add(ip)
                            found.append({
                                "name": message.get("name", "Unknown"),
                                "ip": ip,
                                "port": message.get("port", 8765),
                                "os": message.get("os", "Windows"),
                                "url": f"ws://{ip}:{message.get('port', 8765)}"
                            })
                except socket.timeout:
                    break
                except:
                    pass
        finally:
            sock.close()

        return found


# Standalone test
if __name__ == "__main__":
    print("Scanning for Vortex agents...")
    client = DiscoveryClient(timeout=5)
    agents = client.scan()
    if agents:
        print(f"✅ {len(agents)} agent(s) found:")
        for a in agents:
            print(f"  → {a['name']} ({a['ip']}:{a['port']}) [{a['os']}]")
    else:
        print("❌ No agents found")
