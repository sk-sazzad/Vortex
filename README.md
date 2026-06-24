# 🔗 Vortex

তোমার সব device এক জায়গায় control করো।

## 📦 Project Structure

```
Vortex/
├── server/
│   ├── main.py          ← PC তে চলবে (Server)
│   └── requirements.txt
├── agent/
│   ├── agent.py         ← PC & Laptop এ চলবে (Agent)
│   └── requirements.txt
├── android/
│   ├── main.py          ← Android App
│   └── buildozer.spec
└── .github/
    └── workflows/
        ├── build-exe.yml   ← .exe auto build
        └── build-apk.yml   ← .apk auto build
```

---

## 🚀 Setup করো

### Step 1 — Config করো

**server/main.py** এ তোমার password দাও:
```python
PASSWORD = "তোমার_password"
```

**agent/agent.py** এ তোমার PC এর IP ও password দাও:
```python
SERVER_URL = "ws://192.168.1.X:8765"   # তোমার PC এর IP
PASSWORD = "তোমার_password"
```

**android/main.py** এ তোমার PC এর IP দাও:
```python
SERVER_URL = "ws://192.168.1.X:8765"
```

### Step 2 — PC এর IP বের করো

Windows PowerShell এ চালাও:
```
ipconfig
```
"IPv4 Address" এর পাশে যে number দেখাবে সেটাই তোমার IP।
যেমন: `192.168.1.5`

### Step 3 — GitHub এ push করো

```bash
git add .
git commit -m "Initial setup"
git push origin main
```

GitHub Actions automatically .exe ও .apk বানাবে।

### Step 4 — Download ও Install করো

GitHub → Actions → Build হলে → Artifacts থেকে download করো।

---

## ▶️ চালানোর নিয়ম

### PC তে (Server + Agent দুইটাই চালাও):

```bash
# Terminal 1 — Server
cd server
pip install -r requirements.txt
python main.py

# Terminal 2 — Agent  
cd agent
pip install -r requirements.txt
python agent.py
```

অথবা .exe হলে শুধু double-click করো।

### Mobile তে:
APK install করো → App খোলো → IP ও password দাও → Connect!

---

## ✅ Features

- 🔴 Remote Shutdown / Restart / Sleep
- 🔊 Volume Control
- 📸 Screenshot
- 💻 Terminal Command
- 📁 File Browser
- 📊 System Info (CPU, RAM, Disk, Battery)
- 📋 Clipboard Sync
- 📱 Running Apps দেখা ও বন্ধ করা
- 🖥️ Live Screen View (Coming soon)

---

## 🔒 Security

- Password protected
- Local network এ কাজ করে
- Token-based authentication
