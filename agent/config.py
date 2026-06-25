"""
Vortex Config — First-time setup GUI
"""
import json
import os
import platform
import tkinter as tk
from tkinter import ttk, messagebox
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

    # Center window
    w, h = 400, 320
    sw = root.winfo_screenwidth()
    sh = root.winfo_screenheight()
    root.geometry(f"{w}x{h}+{(sw-w)//2}+{(sh-h)//2}")

    style = ttk.Style()
    style.configure("TEntry", foreground="white", background="#0a0a20")

    def label(parent, text, color="#ffffff", size=10, bold=False):
        font = ("Consolas", size, "bold" if bold else "normal")
        tk.Label(parent, text=text, fg=color, bg="#030310", font=font).pack(anchor="w", pady=(0, 2))

    def entry_field(parent, default=""):
        e = tk.Entry(parent, bg="#0a0a20", fg="white", insertbackground="cyan",
                    font=("Consolas", 11), relief="flat", bd=0)
        e.pack(fill="x", ipady=6, pady=(0, 12))
        e.insert(0, default)
        return e

    frame = tk.Frame(root, bg="#030310", padx=30, pady=20)
    frame.pack(fill="both", expand=True)

    # Title
    tk.Label(frame, text="⚡ VORTEX AGENT", fg="#00e5ff", bg="#030310",
            font=("Consolas", 16, "bold")).pack(pady=(0, 4))
    tk.Label(frame, text="First-time setup", fg="#444466", bg="#030310",
            font=("Consolas", 9)).pack(pady=(0, 20))

    # Fields
    label(frame, "Device Name", color="#888899")
    name_entry = entry_field(frame, config.get("device_name", platform.node()))

    label(frame, "Password", color="#888899")
    pass_entry = entry_field(frame, config.get("password", "vortex123"))

    label(frame, "Port", color="#888899")
    port_entry = entry_field(frame, str(config.get("port", 8765)))

    def save():
        config["device_name"] = name_entry.get().strip() or platform.node()
        config["password"] = pass_entry.get().strip() or "vortex123"
        try:
            config["port"] = int(port_entry.get().strip())
        except:
            config["port"] = 8765
        save_config(config)
        root.destroy()

    # Save button
    btn = tk.Button(frame, text="[ START AGENT ]", command=save,
                   bg="#001a1f", fg="#00e5ff", activebackground="#002a35",
                   activeforeground="#00e5ff", font=("Consolas", 11, "bold"),
                   relief="flat", bd=0, padx=20, pady=8, cursor="hand2")
    btn.pack(fill="x", pady=(4, 0))

    root.mainloop()
    return load_config()

if __name__ == "__main__":
    show_setup()
