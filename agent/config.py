"""
Vortex Agent — Setup & Control
Chrome Remote Desktop style flow
"""
import json
import os
import sys
import platform
import threading
import tkinter as tk
from pathlib import Path

CONFIG_FILE = Path(__file__).parent / "config.json"

# ── colours ──────────────────────────────────────────────
BG         = "#0a0a0f"
SURFACE    = "#0f0f1a"
BORDER     = "#1a1a2e"
ACCENT     = "#00e5ff"
ACCENT_DIM = "#003d4a"
TEXT       = "#e0e0f0"
TEXT_DIM   = "#3a3a55"
TEXT_MID   = "#7070a0"
RED        = "#ff3355"
GREEN      = "#00e5a0"

# ─────────────────────────────────────────────────────────
def load_config():
    if CONFIG_FILE.exists():
        with open(CONFIG_FILE) as f:
            return json.load(f)
    return None

def save_config(cfg):
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f, indent=2)

def is_first_run():
    return load_config() is None

# ── Windows autostart ─────────────────────────────────────
def set_autostart(enable: bool):
    try:
        import winreg
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Run",
            0, winreg.KEY_SET_VALUE
        )
        if enable:
            exe = sys.executable
            winreg.SetValueEx(key, "VortexAgent", 0, winreg.REG_SZ, f'"{exe}"')
        else:
            try:
                winreg.DeleteValue(key, "VortexAgent")
            except FileNotFoundError:
                pass
        winreg.CloseKey(key)
    except Exception as e:
        print(f"[Vortex] autostart error: {e}")

# ── Agent runner ──────────────────────────────────────────
_agent_thread = None

def start_agent_background():
    """
    EXE environment এ agent.py আলাদাভাবে load হয় না।
    তাই agent module থেকে সরাসরি run_server import করি।
    """
    global _agent_thread

    def run():
        try:
            import agent
            # Config reload করি যাতে নতুন password কাজ করে
            agent.CONFIG = load_config() or agent.CONFIG
            agent.run_server()
        except Exception as e:
            print(f"[Vortex] agent error: {e}")

    if not _agent_thread or not _agent_thread.is_alive():
        _agent_thread = threading.Thread(target=run, daemon=True)
        _agent_thread.start()

# ─────────────────────────────────────────────────────────
class VortexApp:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Vortex Agent")
        self.root.configure(bg=BG)
        self.root.resizable(False, False)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        self._center(400, 520)

        if is_first_run():
            self._build_setup()
        else:
            # Auto-start agent
            start_agent_background()
            set_autostart(True)
            self._build_settings()

        self.root.mainloop()

    def _center(self, w, h):
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        self.root.geometry(f"{w}x{h}+{(sw-w)//2}+{(sh-h)//2}")

    def _clear(self):
        for w in self.root.winfo_children():
            w.destroy()

    # ── SHARED WIDGETS ────────────────────────────────────
    def _header(self, subtitle=""):
        hdr = tk.Frame(self.root, bg=BG)
        hdr.pack(fill="x", padx=36, pady=(36, 0))
        tk.Label(hdr, text="⚡", bg=BG, fg=ACCENT,
                 font=("Segoe UI", 26)).pack(side="left", padx=(0, 12))
        box = tk.Frame(hdr, bg=BG)
        box.pack(side="left")
        tk.Label(box, text="VORTEX", bg=BG, fg=TEXT,
                 font=("Consolas", 17, "bold")).pack(anchor="w")
        if subtitle:
            tk.Label(box, text=subtitle, bg=BG, fg=TEXT_MID,
                     font=("Consolas", 8)).pack(anchor="w")
        tk.Frame(self.root, bg=BORDER, height=1).pack(
            fill="x", padx=36, pady=(22, 0))

    def _label(self, text):
        tk.Label(self.root, text=text, bg=BG, fg=TEXT_DIM,
                 font=("Consolas", 8), anchor="w").pack(
                 fill="x", padx=36, pady=(18, 3))

    def _entry(self, default="", secret=False):
        e = tk.Entry(
            self.root,
            bg=SURFACE, fg=TEXT,
            insertbackground=ACCENT,
            font=("Consolas", 12),
            relief="flat", bd=0,
            highlightthickness=1,
            highlightbackground=BORDER,
            highlightcolor=ACCENT,
            show="●" if secret else None
        )
        e.pack(fill="x", ipady=10, padx=36)
        e.insert(0, default)
        return e

    def _btn(self, text, cmd, primary=True):
        if primary:
            b = tk.Button(
                self.root, text=text, command=cmd,
                bg=ACCENT_DIM, fg=ACCENT,
                activebackground="#005566", activeforeground=ACCENT,
                font=("Consolas", 11, "bold"),
                relief="flat", bd=0, pady=13, cursor="hand2",
                highlightthickness=1, highlightbackground=ACCENT
            )
        else:
            b = tk.Button(
                self.root, text=text, command=cmd,
                bg=SURFACE, fg=TEXT_MID,
                activebackground=BORDER, activeforeground=TEXT,
                font=("Consolas", 10),
                relief="flat", bd=0, pady=10, cursor="hand2",
                highlightthickness=1, highlightbackground=BORDER
            )
        b.pack(fill="x", padx=36, pady=(10, 0))
        return b

    # ══════════════════════════════════════════════════════
    # FIRST RUN — SETUP SCREEN
    # ══════════════════════════════════════════════════════
    def _build_setup(self):
        self._clear()
        self._header("First-time Setup")

        tk.Label(self.root,
                 text="Set a name and password for this device.\nYou'll use these to connect from your phone.",
                 bg=BG, fg=TEXT_MID,
                 font=("Consolas", 8),
                 justify="left").pack(anchor="w", padx=36, pady=(16, 0))

        self._label("DEVICE NAME")
        self.name_e = self._entry(platform.node())

        self._label("PASSWORD")
        self.pass_e = self._entry(secret=True)

        self._label("CONFIRM PASSWORD")
        self.pass2_e = self._entry(secret=True)

        self.err_lbl = tk.Label(self.root, text="", bg=BG, fg=RED,
                                font=("Consolas", 8))
        self.err_lbl.pack(pady=(10, 0))

        tk.Frame(self.root, bg=BG).pack(expand=True)
        self._btn("Start Agent", self._finish_setup, primary=True)
        tk.Frame(self.root, bg=BG, height=28).pack()

    def _finish_setup(self):
        name = self.name_e.get().strip() or platform.node()
        pwd  = self.pass_e.get().strip()
        pwd2 = self.pass2_e.get().strip()

        if not pwd:
            self.err_lbl.config(text="⚠  Password cannot be empty")
            return
        if pwd != pwd2:
            self.err_lbl.config(text="⚠  Passwords do not match")
            return

        cfg = {"device_name": name, "password": pwd, "port": 8765}
        save_config(cfg)
        set_autostart(True)
        start_agent_background()

        self._build_settings()
        self.root.after(500, self._hide)

    # ══════════════════════════════════════════════════════
    # SETTINGS SCREEN
    # ══════════════════════════════════════════════════════
    def _build_settings(self):
        self._clear()
        cfg = load_config() or {}

        self._header("Agent Control Panel")

        status_row = tk.Frame(self.root, bg=BG)
        status_row.pack(fill="x", padx=36, pady=(20, 0))
        running = _agent_thread is not None and _agent_thread.is_alive()
        dot_color = GREEN if running else TEXT_DIM
        status_text = "Running in background" if running else "Not running — click Save & Hide to start"
        tk.Label(status_row, text="●", bg=BG, fg=dot_color,
                 font=("Segoe UI", 12)).pack(side="left")
        tk.Label(status_row, text=f"  {status_text}", bg=BG, fg=dot_color,
                 font=("Consolas", 10, "bold")).pack(side="left")

        tk.Frame(self.root, bg=BORDER, height=1).pack(
            fill="x", padx=36, pady=(20, 0))

        self._label("DEVICE NAME")
        self.name_e = self._entry(cfg.get("device_name", platform.node()))

        self._label("PASSWORD")
        self.pass_e = self._entry(cfg.get("password", ""), secret=True)

        self.err_lbl = tk.Label(self.root, text="", bg=BG, fg=RED,
                                font=("Consolas", 8))
        self.err_lbl.pack(pady=(8, 0))

        tk.Frame(self.root, bg=BG).pack(expand=True)
        self._btn("Save & Hide", self._save_and_hide, primary=True)
        self._btn("Exit Agent", self._exit_agent, primary=False)
        tk.Frame(self.root, bg=BG, height=24).pack()

    def _save_and_hide(self):
        cfg = load_config() or {}
        name = self.name_e.get().strip() or platform.node()
        pwd  = self.pass_e.get().strip()

        if not pwd:
            self.err_lbl.config(text="⚠  Password cannot be empty")
            return

        cfg["device_name"] = name
        cfg["password"]    = pwd
        save_config(cfg)

        # Running agent এ নতুন config apply করো
        try:
            import agent
            agent.CONFIG = cfg
        except Exception:
            pass

        # Agent না চললে start করো
        if not (_agent_thread and _agent_thread.is_alive()):
            start_agent_background()

        self._hide()

    def _hide(self):
        self.root.withdraw()

    def _exit_agent(self):
        set_autostart(False)
        try:
            import agent
            agent.agent_running = False
        except Exception:
            pass
        self.root.destroy()
        os._exit(0)

    def _on_close(self):
        self._hide()


# ─────────────────────────────────────────────────────────
def show_setup():
    VortexApp()
    return load_config()

if __name__ == "__main__":
    VortexApp()
