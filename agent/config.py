"""
Vortex — Config Manager
------------------------
GUI popup দিয়ে password নেবো।
Double click করলেও কাজ করবে।
"""

import json
import os
import hashlib
import secrets
import platform
import sys

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

DEFAULT_CONFIG = {
    "password": "",
    "server_port": 8765,
    "device_name": platform.node(),
    "device_id": secrets.token_hex(8),
    "ddns": {
        "enabled": False,
        "hostname": "",
        "provider": "noip",
        "token": ""
    }
}

def load_config() -> dict:
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "r") as f:
            config = json.load(f)
        for key, value in DEFAULT_CONFIG.items():
            if key not in config:
                config[key] = value
        return config
    return DEFAULT_CONFIG.copy()

def save_config(config: dict):
    with open(CONFIG_FILE, "w") as f:
        json.dump(config, f, indent=2)

def setup_first_time(config: dict) -> dict:
    """GUI popup দিয়ে first time setup"""
    try:
        import tkinter as tk
        from tkinter import messagebox, simpledialog
        _setup_gui(config)
    except Exception:
        # Fallback — console এ নেবো
        _setup_console(config)
    return load_config()

def _setup_gui(config: dict):
    import tkinter as tk
    from tkinter import ttk, messagebox

    root = tk.Tk()
    root.title("🌀 Vortex — First Time Setup")
    root.geometry("420x480")
    root.resizable(False, False)
    root.configure(bg="#1a1a2e")
    root.eval('tk::PlaceWindow . center')

    # Title
    tk.Label(root, text="🌀 Vortex Agent", font=("Arial", 20, "bold"),
             bg="#1a1a2e", fg="#4d9fff").pack(pady=(25, 5))
    tk.Label(root, text="একবার setup করো, সবসময় কাজ করবে",
             font=("Arial", 10), bg="#1a1a2e", fg="#888888").pack(pady=(0, 20))

    frame = tk.Frame(root, bg="#1a1a2e", padx=30)
    frame.pack(fill="x")

    # Device name
    tk.Label(frame, text="Device এর নাম:", font=("Arial", 11),
             bg="#1a1a2e", fg="#ffffff", anchor="w").pack(fill="x", pady=(0,3))
    name_var = tk.StringVar(value=config.get("device_name", platform.node()))
    name_entry = tk.Entry(frame, textvariable=name_var, font=("Arial", 11),
                          bg="#2a2a3e", fg="white", insertbackground="white",
                          relief="flat", bd=8)
    name_entry.pack(fill="x", pady=(0, 12))

    # Password
    tk.Label(frame, text="Password:", font=("Arial", 11),
             bg="#1a1a2e", fg="#ffffff", anchor="w").pack(fill="x", pady=(0,3))
    pass_var = tk.StringVar()
    pass_entry = tk.Entry(frame, textvariable=pass_var, show="●",
                          font=("Arial", 11), bg="#2a2a3e", fg="white",
                          insertbackground="white", relief="flat", bd=8)
    pass_entry.pack(fill="x", pady=(0, 12))

    # Confirm password
    tk.Label(frame, text="Password আবার দাও:", font=("Arial", 11),
             bg="#1a1a2e", fg="#ffffff", anchor="w").pack(fill="x", pady=(0,3))
    confirm_var = tk.StringVar()
    confirm_entry = tk.Entry(frame, textvariable=confirm_var, show="●",
                             font=("Arial", 11), bg="#2a2a3e", fg="white",
                             insertbackground="white", relief="flat", bd=8)
    confirm_entry.pack(fill="x", pady=(0, 20))

    status_lbl = tk.Label(frame, text="", font=("Arial", 10),
                          bg="#1a1a2e", fg="#ff4444")
    status_lbl.pack()

    def on_save():
        name = name_var.get().strip()
        pwd  = pass_var.get().strip()
        conf = confirm_var.get().strip()

        if not name:
            status_lbl.config(text="❌ Device এর নাম দাও!")
            return
        if len(pwd) < 4:
            status_lbl.config(text="❌ কমপক্ষে ৪ অক্ষরের password দাও!")
            return
        if pwd != conf:
            status_lbl.config(text="❌ Password মিলছে না!")
            return

        config["device_name"] = name
        config["password"]    = hashlib.sha256(pwd.encode()).hexdigest()
        save_config(config)
        status_lbl.config(text="✅ Setup complete!", fg="#44ff88")
        root.after(1000, root.destroy)

    save_btn = tk.Button(root, text="✅  Setup Complete",
                         font=("Arial", 12, "bold"),
                         bg="#4d9fff", fg="white", relief="flat",
                         bd=0, padx=20, pady=10, cursor="hand2",
                         command=on_save)
    save_btn.pack(pady=15)

    root.mainloop()

def _setup_console(config: dict):
    """Console fallback"""
    print("\n" + "="*40)
    print("   🌀 Vortex Agent — First Time Setup")
    print("="*40)

    while True:
        try:
            password = input("Password: ").strip()
        except Exception:
            password = "vortex123"
            break
        if len(password) < 4:
            print("❌ কমপক্ষে ৪ অক্ষরের password দাও!")
            continue
        try:
            confirm = input("আবার দাও: ").strip()
        except Exception:
            confirm = password
        if password != confirm:
            print("❌ Password মিলছে না!")
            continue
        break

    config["password"] = hashlib.sha256(password.encode()).hexdigest()
    try:
        name = input(f"Device এর নাম [{config['device_name']}]: ").strip()
        if name:
            config["device_name"] = name
    except Exception:
        pass
    save_config(config)
    print("✅ Setup complete!")

def get_password_hash(config: dict) -> str:
    return config.get("password", "")
