"""
Vortex Agent — Main UI Window
- Window close করলে background এ চলতে থাকে (active থাকলে)
- Windows startup এ auto-start
"""
import json
import os
import sys
import platform
import subprocess
import threading
import tkinter as tk
from pathlib import Path

CONFIG_FILE = Path(__file__).parent / "config.json"

# ── colours ──────────────────────────────────────────────
BG         = "#0a0a0f"
SURFACE    = "#111118"
BORDER     = "#1e1e2e"
ACCENT     = "#00e5ff"
ACCENT_DIM = "#004f5a"
TEXT       = "#e0e0f0"
TEXT_DIM   = "#44445a"
RED        = "#ff4566"
GREEN      = "#00e5a0"

# ─────────────────────────────────────────────────────────
def load_config():
    if CONFIG_FILE.exists():
        with open(CONFIG_FILE) as f:
            return json.load(f)
    return {
        "device_name": platform.node(),
        "password": "",
        "port": 8765,
        "active": False
    }

def save_config(cfg):
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f, indent=2)

# ── Windows startup registry ──────────────────────────────
def set_autostart(enable: bool):
    """Add or remove VortexAgent from Windows startup registry."""
    try:
        import winreg
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Run",
            0, winreg.KEY_SET_VALUE
        )
        if enable:
            exe = sys.executable if not getattr(sys, "frozen", False) else sys.executable
            winreg.SetValueEx(key, "VortexAgent", 0, winreg.REG_SZ, f'"{exe}"')
        else:
            try:
                winreg.DeleteValue(key, "VortexAgent")
            except FileNotFoundError:
                pass
        winreg.CloseKey(key)
    except Exception as e:
        print(f"[Vortex] autostart error: {e}")

# ─────────────────────────────────────────────────────────
class VortexUI:
    def __init__(self):
        self.cfg          = load_config()
        self.agent_thread = None
        self._hidden      = False

        self.root = tk.Tk()
        self.root.title("Vortex Agent")
        self.root.configure(bg=BG)
        self.root.resizable(False, False)

        W, H = 420, 500
        self.root.geometry(
            f"{W}x{H}+"
            f"{(self.root.winfo_screenwidth()-W)//2}+"
            f"{(self.root.winfo_screenheight()-H)//2}"
        )

        self._build()

        # Close button → hide (background এ চলবে) যদি active থাকে
        # নইলে normally বন্ধ হবে
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        # Auto-start agent if was active last session
        if self.cfg.get("active", False):
            self._start_agent()

        self.root.mainloop()

    # ── build UI ─────────────────────────────────────────
    def _build(self):
        pad = dict(padx=32)

        # ── header ──
        hdr = tk.Frame(self.root, bg=BG)
        hdr.pack(fill="x", pady=(36, 0), **pad)

        tk.Label(hdr, text="⚡", bg=BG, fg=ACCENT,
                 font=("Segoe UI", 28)).pack(side="left", padx=(0, 10))

        title_box = tk.Frame(hdr, bg=BG)
        title_box.pack(side="left")
        tk.Label(title_box, text="VORTEX", bg=BG, fg=TEXT,
                 font=("Consolas", 18, "bold")).pack(anchor="w")
        tk.Label(title_box, text="Agent Control Panel", bg=BG, fg=TEXT_DIM,
                 font=("Consolas", 8)).pack(anchor="w")

        # ── divider ──
        tk.Frame(self.root, bg=BORDER, height=1).pack(
            fill="x", padx=32, pady=(24, 28))

        # ── fields ──
        self._field("Device Name", self.cfg.get("device_name", ""))
        self._spacer(14)
        self._field("Password", self.cfg.get("password", ""), secret=True)

        # ── divider ──
        tk.Frame(self.root, bg=BORDER, height=1).pack(
            fill="x", padx=32, pady=(32, 28))

        # ── status row ──
        row = tk.Frame(self.root, bg=BG)
        row.pack(fill="x", **pad)

        status_col = tk.Frame(row, bg=BG)
        status_col.pack(side="left")

        tk.Label(status_col, text="STATUS", bg=BG, fg=TEXT_DIM,
                 font=("Consolas", 7)).pack(anchor="w")

        status_inner = tk.Frame(status_col, bg=BG)
        status_inner.pack(anchor="w")
        self.status_dot   = tk.Label(status_inner, bg=BG, font=("Segoe UI", 11))
        self.status_label = tk.Label(status_inner, bg=BG, font=("Consolas", 13, "bold"))
        self.status_dot.pack(side="left")
        self.status_label.pack(side="left", padx=(4, 0))

        # toggle button
        self.toggle_btn = tk.Button(
            row,
            font=("Consolas", 10, "bold"),
            relief="flat", bd=0,
            padx=20, pady=8,
            cursor="hand2",
            command=self._toggle
        )
        self.toggle_btn.pack(side="right")

        # ── bottom buttons ──
        tk.Frame(self.root, bg=BG).pack(expand=True)

        btn_frame = tk.Frame(self.root, bg=BG)
        btn_frame.pack(fill="x", padx=32, pady=(0, 28))

        # Save Settings
        self.save_btn = tk.Button(
            btn_frame,
            text="Save Settings",
            command=self._save,
            bg=SURFACE, fg=TEXT_DIM,
            activebackground=BORDER, activeforeground=TEXT,
            font=("Consolas", 9),
            relief="flat", bd=0,
            pady=10, cursor="hand2",
            highlightthickness=1,
            highlightbackground=BORDER
        )
        self.save_btn.pack(fill="x", pady=(0, 8))

        # Run in background / Exit row
        bottom = tk.Frame(btn_frame, bg=BG)
        bottom.pack(fill="x")

        self.bg_btn = tk.Button(
            bottom,
            text="Run in Background",
            command=self._hide_to_background,
            bg=SURFACE, fg=TEXT_DIM,
            activebackground=BORDER, activeforeground=TEXT,
            font=("Consolas", 9),
            relief="flat", bd=0,
            pady=8, cursor="hand2",
            highlightthickness=1,
            highlightbackground=BORDER
        )
        self.bg_btn.pack(side="left", fill="x", expand=True, padx=(0, 4))

        exit_btn = tk.Button(
            bottom,
            text="Exit",
            command=self._exit_app,
            bg="#1a0010", fg=RED,
            activebackground="#2a0018", activeforeground=RED,
            font=("Consolas", 9),
            relief="flat", bd=0,
            pady=8, cursor="hand2",
            highlightthickness=1,
            highlightbackground="#3a0020"
        )
        exit_btn.pack(side="right", padx=(4, 0))

        self._refresh_status()

    def _field(self, label_text, default, secret=False):
        pad = dict(padx=32)
        tk.Label(self.root, text=label_text, bg=BG, fg=TEXT_DIM,
                 font=("Consolas", 8), anchor="w").pack(fill="x", **pad)
        show = "●" if secret else None
        e = tk.Entry(
            self.root,
            bg=SURFACE, fg=TEXT,
            insertbackground=ACCENT,
            font=("Consolas", 12),
            relief="flat", bd=0,
            highlightthickness=1,
            highlightbackground=BORDER,
            highlightcolor=ACCENT,
            show=show
        )
        e.pack(fill="x", ipady=10, pady=(4, 0), **pad)
        e.insert(0, default)
        if label_text == "Device Name":
            self.name_entry = e
        else:
            self.pass_entry = e

    def _spacer(self, h=10):
        tk.Frame(self.root, bg=BG, height=h).pack()

    # ── status ───────────────────────────────────────────
    def _refresh_status(self):
        active = self.cfg.get("active", False)
        if active:
            self.status_dot.config(text="●", fg=GREEN)
            self.status_label.config(text="Active", fg=GREEN)
            self.toggle_btn.config(
                text="Deactivate",
                bg="#1a0010", fg=RED,
                activebackground="#2a0018", activeforeground=RED,
                highlightbackground=RED
            )
            self.bg_btn.config(state="normal", fg=TEXT_DIM)
        else:
            self.status_dot.config(text="●", fg=TEXT_DIM)
            self.status_label.config(text="Inactive", fg=TEXT_DIM)
            self.toggle_btn.config(
                text="  Activate  ",
                bg=ACCENT_DIM, fg=ACCENT,
                activebackground="#006878", activeforeground=ACCENT,
                highlightbackground=ACCENT
            )
            self.bg_btn.config(state="disabled", fg="#222233")

    # ── toggle ───────────────────────────────────────────
    def _toggle(self):
        self._apply_fields()
        self.cfg["active"] = not self.cfg.get("active", False)
        save_config(self.cfg)

        if self.cfg["active"]:
            set_autostart(True)
            self._start_agent()
        else:
            set_autostart(False)
            self._stop_agent()

        self._refresh_status()

    # ── agent control ────────────────────────────────────
    def _start_agent(self):
        import importlib.util
        agent_path = Path(__file__).parent / "agent.py"

        def run():
            try:
                spec = importlib.util.spec_from_file_location("vortex_agent", agent_path)
                mod  = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(mod)
                mod.run_server()
            except Exception as e:
                print(f"[Vortex] agent error: {e}")

        if not self.agent_thread or not self.agent_thread.is_alive():
            self.agent_thread = threading.Thread(target=run, daemon=True)
            self.agent_thread.start()

    def _stop_agent(self):
        try:
            import vortex_agent as ag
            ag.agent_running = False
        except Exception:
            pass

    # ── save ─────────────────────────────────────────────
    def _apply_fields(self):
        self.cfg["device_name"] = self.name_entry.get().strip() or platform.node()
        self.cfg["password"]    = self.pass_entry.get().strip()

    def _save(self):
        self._apply_fields()
        save_config(self.cfg)
        self.save_btn.config(fg=GREEN)
        self.root.after(800, lambda: self.save_btn.config(fg=TEXT_DIM))

    # ── window control ───────────────────────────────────
    def _hide_to_background(self):
        """Window লুকিয়ে background এ চালু রাখো।"""
        self._apply_fields()
        save_config(self.cfg)
        self.root.withdraw()   # window hide — process চলতে থাকে
        self._hidden = True

    def _on_close(self):
        """X বাটন চাপলে — active থাকলে background এ যাবে, নইলে বন্ধ।"""
        self._apply_fields()
        save_config(self.cfg)
        if self.cfg.get("active", False):
            self.root.withdraw()   # background এ চলতে থাকবে
            self._hidden = True
        else:
            self.root.destroy()

    def _exit_app(self):
        """সম্পূর্ণ বন্ধ করো — agent ও বন্ধ হবে।"""
        self._apply_fields()
        self.cfg["active"] = False
        save_config(self.cfg)
        set_autostart(False)
        self._stop_agent()
        self.root.destroy()
        os._exit(0)


# ─────────────────────────────────────────────────────────
def show_setup():
    VortexUI()
    return load_config()

if __name__ == "__main__":
    VortexUI()
