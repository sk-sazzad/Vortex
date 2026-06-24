"""
Vortex — Config Manager
------------------------
প্রথমবার চালালে config.json বানাবে।
Password একবার set করলেই হবে, আর বদলাতে হবে না।
IP কখনো manually দিতে হবে না — Auto Discovery করবে।
"""

import json
import os
import hashlib
import secrets
import platform

CONFIG_FILE = "config.json"

DEFAULT_CONFIG = {
    "password": "",           # প্রথমবার চালালে set করতে বলবে
    "server_port": 8765,
    "device_name": platform.node(),
    "device_id": secrets.token_hex(8),
    # Optional: Dynamic DNS (বাইরে থেকে access করতে চাইলে)
    "ddns": {
        "enabled": False,
        "hostname": "",       # যেমন: vortex-home.ddns.net
        "provider": "noip",   # noip / duckdns
        "token": ""
    }
}

def load_config() -> dict:
    """Config load করো, না থাকলে বানাও"""
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "r") as f:
            config = json.load(f)
        # নতুন keys থাকলে add করো
        for key, value in DEFAULT_CONFIG.items():
            if key not in config:
                config[key] = value
        return config
    else:
        return DEFAULT_CONFIG.copy()

def save_config(config: dict):
    """Config save করো"""
    with open(CONFIG_FILE, "w") as f:
        json.dump(config, f, indent=2)

def setup_first_time(config: dict) -> dict:
    """প্রথমবার চালালে password set করতে বলো"""
    print("\n" + "="*40)
    print("   🌀 Vortex Agent — First Time Setup")
    print("="*40)
    print("\nএকটা password set করো (এটা মনে রাখো):")
    print("Android App এ এই password দিয়ে connect করবে।\n")

    while True:
        password = input("Password: ").strip()
        if len(password) < 4:
            print("❌ কমপক্ষে ৪ অক্ষরের password দাও!")
            continue
        confirm = input("আবার দাও: ").strip()
        if password != confirm:
            print("❌ Password মিলছে না!")
            continue
        break

    config["password"] = hashlib.sha256(password.encode()).hexdigest()
    config["device_name"] = input(f"\nDevice এর নাম [{config['device_name']}]: ").strip() or config["device_name"]

    # Optional DDNS
    print("\n[Optional] বাইরে থেকে access করতে চাও? (y/n): ", end="")
    if input().strip().lower() == 'y':
        print("DDNS hostname (যেমন: vortex-home.ddns.net): ", end="")
        hostname = input().strip()
        if hostname:
            config["ddns"]["enabled"] = True
            config["ddns"]["hostname"] = hostname
            print("DDNS token/key: ", end="")
            config["ddns"]["token"] = input().strip()

    save_config(config)
    print("\n✅ Setup complete! Vortex Agent শুরু হচ্ছে...\n")
    return config

def get_password_hash(config: dict) -> str:
    return config.get("password", "")
