"""
Vortex Agent — Main UI Window
Simple control panel: Name, Password, Active/Deactive toggle
"""
import json
import os
import platform
import subprocess
import threading
import tkinter as tk
from tkinter import font as tkfont
from pathlib import Path

CONFIG_FILE = Path(__file__).parent / "config.json"

# ── colours ──────────────────────────────────────────────
BG        = "#0a0a0f"
SURFACE   = "#111118"
BORDER    = "#1e1e2e"
ACCENT    = "#00e5ff"
ACCENT_DIM= "#004f5a"
TEXT      = "#e0e0f0"
TEXT_DIM  = "#44445a"
RED       = "#ff4566"
GREEN     = "#00e5a0"

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

# ─────────────────────────────────────────────────────────
class VortexUI:
    def __init__(self):
        self.cfg = load_config()
        self.agent_thread = None
        self.agent_proc   = None

        self.root = tk.Tk()
        self.root.title("Vortex Agent")
        self.root.configure(bg=BG)
        self.root.resizable(False, False)

        # centre window
        W, H = 420, 480
        self.root.geometry(
            f"{W}x{H}+"
            f"{(self.root.winfo_screenwidth()-W)//2}+"
            f"{(self.root.winfo_screenheight()-H)//2}"
        )

        self._build()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
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

        self.status_dot  = tk.Label(status_col, bg=BG,
                                    font=("Segoe UI", 11))
        self.status_label = tk.Label(status_col, bg=BG,
                                     font=("Consolas", 13, "bold"))
        self.status_dot.pack(side="left")
        self.status_label.pack(side="left", padx=(4, 0))

        # toggle button (right side)
        self.toggle_btn = tk.Button(
            row,
            font=("Consolas", 10, "bold"),
            relief="flat", bd=0,
            padx=20, pady=8,
            cursor="hand2",
            command=self._toggle
        )
        self.toggle_btn.pack(side="right")

        # ── save button ──
        tk.Frame(self.root, bg=BG).pack(expand=True)  # spacer

        save_btn = tk.Button(
            self.root,
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
        save_btn.pack(fill="x", padx=32, pady=(0, 28))

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

    # ── status / toggle ──────────────────────────────────
    def _refresh_status(self):
        active = self.cfg.get("active", False)
        if active:
            self.status_dot.config(text="●", fg=GREEN)
            self.status_label.config(text="Active", fg=GREEN)
            self.toggle_btn.config(
                text="Deactivate",
                bg="#1a0010", fg=RED,
                activebackground="#2a0018",
                activeforeground=RED,
                highlightbackground=RED
            )
        else:
            self.status_dot.config(text="●", fg=TEXT_DIM)
            self.status_label.config(text="Inactive", fg=TEXT_DIM)
            self.toggle_btn.config(
                text="  Activate  ",
                bg=ACCENT_DIM, fg=ACCENT,
                activebackground="#006878",
                activeforeground=ACCENT,
                highlightbackground=ACCENT
            )

    def _toggle(self):
        # save current field values first
        self._apply_fields()
        self.cfg["active"] = not self.cfg.get("active", False)
        save_config(self.cfg)
        self._refresh_status()

        if self.cfg["active"]:
            self._start_agent()
        else:
            self._stop_agent()

    # ── agent control ────────────────────────────────────
    def _start_agent(self):
        """Start the websocket agent in a background thread."""
        import importlib.util, sys
        agent_path = Path(__file__).parent / "agent.py"

        def run():
            try:
                spec = importlib.util.spec_from_file_location("agent", agent_path)
                mod  = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(mod)
                mod.run_server()          # blocking — runs until stopped
            except Exception as e:
                print(f"[Vortex] agent error: {e}")

        self.agent_thread = threading.Thread(target=run, daemon=True)
        self.agent_thread.start()

    def _stop_agent(self):
        """Signal agent to stop (it's daemonised, so closing app kills it too)."""
        try:
            import agent as ag
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

        # flash the save button green briefly
        for widget in self.root.winfo_children():
            if isinstance(widget, tk.Button) and "Save" in str(widget.cget("text")):
                widget.config(fg=GREEN)
                self.root.after(800, lambda: widget.config(fg=TEXT_DIM))
                break

    def _on_close(self):
        self._apply_fields()
        save_config(self.cfg)
        self.root.destroy()


# ─────────────────────────────────────────────────────────
def show_setup():
    """Called from agent.py on first run — same window, blocks until closed."""
    VortexUI()
    return load_config()

if __name__ == "__main__":
    VortexUI()
