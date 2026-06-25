"""
Vortex Config — First-time setup GUI
"""
import json
import os
import platform
import tkinter as tk
from pathlib import Path

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

def save_config(config):
    with open(CONFIG_FILE, "w") as f:
        json.dump(config, f, indent=2)

def show_setup():
    config = load_config()
    root = tk.Tk()
    root.title("Vortex Agent Setup")
    root.configure(bg="#030310")
    root.resizable(False, False)

    # Bigger window — button দেখা যাবে
    w, h = 400, 420
    sw = root.winfo_screenwidth()
    sh = root.winfo_screenheight()
    root.geometry(f"{w}x{h}+{(sw-w)//2}+{(sh-h)//2}")

    frame = tk.Frame(root, bg="#030310", padx=30, pady=24)
    frame.pack(fill="both", expand=True)

    # Logo
    tk.Label(frame, text="⚡  VORTEX AGENT", fg="#00e5ff", bg="#030310",
            font=("Consolas", 15, "bold")).pack(pady=(0, 4))
    tk.Label(frame, text="First-time setup", fg="#333355", bg="#030310",
            font=("Consolas", 9)).pack(pady=(0, 24))

    def label(text):
        tk.Label(frame, text=text, fg="#555577", bg="#030310",
                font=("Consolas", 8), anchor="w").pack(fill="x", pady=(0, 3))

    def entry(default, show=None):
        e = tk.Entry(frame, bg="#080818", fg="white",
                    insertbackground="#00e5ff",
                    font=("Consolas", 11), relief="flat", bd=0,
                    highlightthickness=1,
                    highlightbackground="#1a1a2e",
                    highlightcolor="#00e5ff",
                    show=show)
        e.pack(fill="x", ipady=8, pady=(0, 16))
        e.insert(0, default)
        return e

    label("Device Name")
    name_e = entry(config.get("device_name", platform.node()))

    label("Password")
    pass_e = entry(config.get("password", "vortex123"))

    label("Port")
    port_e = entry(str(config.get("port", 8765)))

    # Divider
    tk.Frame(frame, bg="#1a1a2e", height=1).pack(fill="x", pady=(4, 16))

    def save():
        config["device_name"] = name_e.get().strip() or platform.node()
        config["password"] = pass_e.get().strip() or "vortex123"
        try:
            config["port"] = int(port_e.get().strip())
        except:
            config["port"] = 8765
        save_config(config)
        root.destroy()

    # Button — clearly visible
    btn = tk.Button(frame, text="[ START AGENT ]",
                   command=save,
                   bg="#001820", fg="#00e5ff",
                   activebackground="#002a35",
                   activeforeground="#00e5ff",
                   font=("Consolas", 12, "bold"),
                   relief="flat", bd=0,
                   pady=12, cursor="hand2",
                   highlightthickness=1,
                   highlightbackground="#00e5ff")
    btn.pack(fill="x", pady=(0, 8))

    # Info text
    tk.Label(frame, text=f"Will listen on port {config.get('port', 8765)}",
            fg="#222244", bg="#030310",
            font=("Consolas", 8)).pack()

    root.mainloop()
    return load_config()

if __name__ == "__main__":
    show_setup()
