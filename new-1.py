# ORION AI - yagona asosiy versiya
# Pydroid 3 da sinash uchun. Eski orion.py fayliga tegmang.
# API kalit orion_key.txt faylida, shu fayl bilan bir papkada turishi kerak.

import os
import json
import base64
import asyncio
import threading
import mimetypes
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

from kivy.app import App
from kivy.clock import Clock
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.scrollview import ScrollView
from kivy.uix.popup import Popup
from kivy.uix.filechooser import FileChooserIconView


# ---------------- SOZLAMALAR ----------------

APP_NAME = "ORION AI"
FOLDER = os.path.dirname(os.path.abspath(__file__))

KEY_FILE = os.path.join(FOLDER, "orion_key.txt")
HISTORY_FILE = os.path.join(FOLDER, "orion_history.json")

API_URL = "https://api.groq.com/openai/v1/chat/completions"
MODEL = "qwen/qwen3.8-27b"

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 8765

MAX_HISTORY_MESSAGES = 30
MAX_IMAGE_BYTES = 12 * 1024 * 1024
REQUEST_TIMEOUT = 90

SYSTEM_PROMPT = """
Sen ORION nomli shaxsiy sun'iy intellekt yordamchisisan.
Foydalanuvchi bilan o'zbek tilida, sodda, hurmat bilan va aniq gaplash.
Murakkab vazifalarni kichik bosqichlarga ajrat.
Bilmagan narsangni to'qib chiqarmagin; noaniqlikni ochiq ayt.
Internetga ulanmagan bo'lsang, internetdan tekshirdim deb da'vo qilma.
Telefon yoki fayllarda amal bajarilgan deb aytma, agar haqiqatan bajarilmagan bo'lsa.
Xavfli yoki qaytarib bo'lmaydigan amallardan oldin foydalanuvchidan ruxsat so'ra.
Foydalanuvchiga ism bilan murojaat qilish shart emas.
"""

history_lock = threading.RLock()
history = []
server_instance = None
server_lock = threading.Lock()


# ---------------- XOTIRA ----------------

def load_history():
    try:
        if os.path.isfile(HISTORY_FILE):
            with open(HISTORY_FILE, "r", encoding="utf-8") as file:
                data = json.load(file)
                if isinstance(data, list):
                    # Faqat modelga mos matnli xabarlarni qoldiramiz.
                    cleaned = []
                    for item in data[-MAX_HISTORY_MESSAGES:]:
                        if (
                            isinstance(item, dict)
                            and item.get("role") in ("user", "assistant")
                            and isinstance(item.get("content"), str)
                        ):
                            cleaned.append(item)
                    return cleaned
    except Exception as error:
        print("Xotirani o'qish xatosi:", error)
    return []


def save_history():
    try:
        with history_lock:
            temporary = HISTORY_FILE + ".tmp"
            with open(temporary, "w", encoding="utf-8") as file:
                json.dump(history, file, ensure_ascii=False, indent=2)
            os.replace(temporary, HISTORY_FILE)
    except Exception as error:
        print("Xotirani saqlash xatosi:", error)


history = load_history()


def clear_history_data():
    global history
    with history_lock:
        history = []
        save_history()


# ---------------- API KALIT ----------------

def get_api_key():
    if not os.path.isfile(KEY_FILE):
        raise RuntimeError(
            "orion_key.txt topilmadi. API kalit faylini ORION kodi bilan "
            "bir papkaga joylashtiring."
        )

    with open(KEY_FILE, "r", encoding="utf-8") as file:
        key = file.read().strip()

    if not key:
        raise RuntimeError("orion_key.txt ichida API kalit yo'q.")

    return key


# ---------------- RASM ----------------

def image_to_data_url(path):
    if not path or not os.path.isfile(path):
        return None

    size = os.path.getsize(path)
    if size > MAX_IMAGE_BYTES:
        raise RuntimeError("Rasm 12 MB dan katta. Kichikroq rasm tanlang.")

    mime = mimetypes.guess_type(path)[0] or "image/jpeg"

    with open(path, "rb") as file:
        encoded = base64.b64encode(file.read()).decode("utf-8")

    return "data:" + mime + ";base64," + encoded


# ---------------- ORION AQLI ----------------

def ask_brain(question, image_path=None):
    question = (question or "").strip()

    if not question and not image_path:
        return "Savolingizni yozing yoki rasm tanlang."

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]

    with history_lock:
        messages.extend(history[-MAX_HISTORY_MESSAGES:])

    if image_path:
        image_url = image_to_data_url(image_path)
        if not image_url:
            raise RuntimeError("Rasmni o'qib bo'lmadi.")

        user_content = [
            {"type": "text", "text": question or "Bu rasmni batafsil tahlil qil."},
            {"type": "image_url", "image_url": {"url": image_url}}
        ]
        user_message = {"role": "user", "content": user_content}
    else:
        user_message = {"role": "user", "content": question}

    messages.append(user_message)

    headers = {
        "Authorization": "Bearer " + get_api_key(),
        "Content-Type": "application/json"
    }

    payload = {
        "model": MODEL,
        "messages": messages,
        "temperature": 0.6,
        "max_tokens": 1600
    }

    try:
        response = requests.post(
            API_URL,
            headers=headers,
            json=payload,
            timeout=REQUEST_TIMEOUT
        )
    except requests.exceptions.Timeout:
        raise RuntimeError("Internet yoki AI serveri javob berishi cho'zildi. Qayta urinib ko'ring.")
    except requests.exceptions.ConnectionError:
        raise RuntimeError("Internetga ulanib bo'lmadi. Internetni tekshiring.")

    if response.status_code != 200:
        try:
            detail = response.json().get("error", {}).get("message", response.text)
        except Exception:
            detail = response.text
        raise RuntimeError("AI server xatosi (" + str(response.status_code) + "): " + str(detail)[:700])

    data = response.json()

    try:
        answer = data["choices"][0]["message"]["content"]
    except Exception:
        raise RuntimeError("AI javobini o'qib bo'lmadi: javob formati kutilmagan.")

    if isinstance(answer, list):
        answer = " ".join(
            part.get("text", "")
            for part in answer
            if isinstance(part, dict)
        )

    answer = str(answer).strip()
    if not answer:
        answer = "AI bo'sh javob qaytardi. Yana bir marta urinib ko'ring."

    # Rasmning o'zi tarixga base64 ko'rinishida yozilmaydi.
    saved_user_text = question if question else (
        "Foydalanuvchi rasm yubordi va uni tahlil qilishni so'radi."
    )

    with history_lock:
        history.append({"role": "user", "content": saved_user_text})
        history.append({"role": "assistant", "content": answer})
        del history[:-MAX_HISTORY_MESSAGES]
        save_history()

    return answer


# ---------------- OVOZ ----------------
# Edge TTS va pygame bo'lsa ishlaydi. Bo'lmasa matnli chat ishlashda davom etadi.

def speak(text_to_say):
    try:
        import edge_tts
        import pygame

        voice_path = os.path.join(FOLDER, "orion_ovoz.mp3")

        async def create_audio():
            speaker = edge_tts.Communicate(
                text_to_say[:3500],
                "uz-UZ-MadinaNeural"
            )
            await speaker.save(voice_path)

        asyncio.run(create_audio())

        if not pygame.mixer.get_init():
            pygame.mixer.init()

        pygame.mixer.music.load(voice_path)
        pygame.mixer.music.play()

    except Exception as error:
        print("Ovoz hozir ishlamadi:", error)


# ---------------- LOCAL SERVER ----------------
# Sketchware ulanishi uchun. question ham, savol ham qabul qilinadi.

class OrionRequestHandler(BaseHTTPRequestHandler):

    def send_json(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            print("Ulangan dastur javobni kutishni to'xtatdi.")

    def do_OPTIONS(self):
        self.send_json(200, {"ok": True})

    def do_GET(self):
        if self.path == "/health":
            self.send_json(200, {"status": "ORION ishlayapti"})
        else:
            self.send_json(404, {"error": "Manzil topilmadi"})

    def do_POST(self):
        if self.path != "/ask":
            self.send_json(404, {"error": "Manzil topilmadi"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 100000:
                self.send_json(400, {"error": "So'rov hajmi noto'g'ri."})
                return

            incoming = json.loads(self.rfile.read(length).decode("utf-8"))

            # Sketchware'dagi eski "savol" nomi ham, "question" ham ishlaydi.
            question = str(
                incoming.get("question", incoming.get("savol", incoming.get("text", "")))
            ).strip()

            if not question:
                self.send_json(400, {"error": "Savol bo'sh. question yoki savol yuboring."})
                return

            answer = ask_brain(question)

            threading.Thread(
                target=speak,
                args=(answer,),
                daemon=True
            ).start()

            self.send_json(200, {"answer": answer, "ok": True})

        except Exception as error:
            self.send_json(500, {"error": str(error), "ok": False})

    def log_message(self, fmt, *args):
        print("ORION SERVER:", fmt % args)


def start_server():
    global server_instance

    with server_lock:
        if server_instance is not None:
            return

        try:
            server_instance = ThreadingHTTPServer(
                (SERVER_HOST, SERVER_PORT),
                OrionRequestHandler
            )
            threading.Thread(
                target=server_instance.serve_forever,
                daemon=True
            ).start()
            print("ORION SERVER ISHLADI:", SERVER_HOST, SERVER_PORT)
        except Exception as error:
            server_instance = None
            print("SERVER XATOSI:", error)


# ---------------- KIVY ILOVA ----------------

class OrionApp(App):

    def build(self):
        self.title = APP_NAME
        self.selected_image = None

        root = BoxLayout(
            orientation="vertical",
            padding=dp(9),
            spacing=dp(7)
        )
        self.root_box = root

        title = Label(
            text="[b]ORION AI[/b]",
            markup=True,
            size_hint_y=None,
            height=dp(42),
            font_size="23sp"
        )
        root.add_widget(title)

        self.status = Label(
            text="Tayyor. ORIONga savol bering.",
            size_hint_y=None,
            height=dp(30)
        )
        root.add_widget(self.status)

        self.scroll = ScrollView()
        self.chat_box = BoxLayout(
            orientation="vertical",
            size_hint_y=None,
            spacing=dp(9),
            padding=dp(6)
        )
        self.chat_box.bind(
            minimum_height=self.chat_box.setter("height")
        )
        self.scroll.add_widget(self.chat_box)
        root.add_widget(self.scroll)

        self.input_box = TextInput(
            hint_text="ORIONga savol yozing...",
            size_hint_y=None,
            height=dp(95),
            multiline=True
        )
        root.add_widget(self.input_box)

        row = BoxLayout(
            size_hint_y=None,
            height=dp(48),
            spacing=dp(5)
        )

        send_btn = Button(text="YUBORISH")
        send_btn.bind(on_release=self.send_message)

        image_btn = Button(text="RASM")
        image_btn.bind(on_release=self.open_image_picker)

        voice_btn = Button(text="OVOZ")
        voice_btn.bind(on_release=self.repeat_last_answer)

        row.add_widget(send_btn)
        row.add_widget(image_btn)
        row.add_widget(voice_btn)
        root.add_widget(row)

        bottom = BoxLayout(
            size_hint_y=None,
            height=dp(42),
            spacing=dp(5)
        )

        clear_btn = Button(text="XOTIRANI TOZALASH")
        clear_btn.bind(on_release=self.clear_history)

        bottom.add_widget(clear_btn)
        root.add_widget(bottom)

        self.last_answer = ""
        start_server()

        return root

    def add_message(self, title, message):
        label = Label(
            text=title + ": " + str(message),
            size_hint_y=None,
            halign="left",
            valign="top"
        )
        label.text_size = (max(dp(200), self.root_box.width - dp(35)), None)
        label.bind(
            texture_size=lambda obj, size:
            setattr(obj, "height", size[1] + dp(14))
        )
        self.chat_box.add_widget(label)
        Clock.schedule_once(
            lambda dt: setattr(self.scroll, "scroll_y", 0),
            0.1
        )

    def send_message(self, instance):
        question = self.input_box.text.strip()
        image_path = self.selected_image

        if not question and not image_path:
            self.status.text = "Avval savol yozing yoki rasm tanlang."
            return

        self.input_box.text = ""
        self.selected_image = None

        self.add_message("SIZ", question or "[Rasm yuborildi]")
        self.status.text = "ORION javob tayyorlayapti..."

        threading.Thread(
            target=self.get_answer,
            args=(question, image_path),
            daemon=True
        ).start()

    def get_answer(self, question, image_path):
        try:
            answer = ask_brain(question, image_path)
            Clock.schedule_once(lambda dt: self.show_answer(answer), 0)
        except Exception as error:
            Clock.schedule_once(lambda dt: self.show_error(str(error)), 0)

    def show_answer(self, answer):
        self.last_answer = answer
        self.add_message("ORION", answer)
        self.status.text = "Javob tayyor."

    def show_error(self, error):
        self.add_message("XATO", error)
        self.status.text = "Xatolik. Internet va API kalitni tekshiring."

    def repeat_last_answer(self, instance):
        if not self.last_answer:
            self.status.text = "Hali ORION javobi yo'q."
            return
        self.status.text = "Ovoz tayyorlanmoqda..."
        threading.Thread(
            target=speak,
            args=(self.last_answer,),
            daemon=True
        ).start()

    def open_image_picker(self, instance):
        chooser = FileChooserIconView(
            filters=["*.png", "*.jpg", "*.jpeg", "*.webp"]
        )

        layout = BoxLayout(
            orientation="vertical",
            spacing=dp(5)
        )
        layout.add_widget(chooser)

        buttons = BoxLayout(
            size_hint_y=None,
            height=dp(48),
            spacing=dp(5)
        )

        popup = Popup(
            title="Rasm tanlang",
            content=layout,
            size_hint=(0.96, 0.9)
        )

        def choose(_):
            if chooser.selection:
                self.selected_image = chooser.selection[0]
                self.status.text = "Rasm tanlandi. Savol yozib YUBORISHni bosing."
                popup.dismiss()

        ok_btn = Button(text="TANLASH")
        cancel_btn = Button(text="BEKOR")
        ok_btn.bind(on_release=choose)
        cancel_btn.bind(on_release=popup.dismiss)

        buttons.add_widget(ok_btn)
        buttons.add_widget(cancel_btn)
        layout.add_widget(buttons)
        popup.open()

    def clear_history(self, instance):
        clear_history_data()
        self.chat_box.clear_widgets()
        self.last_answer = ""
        self.status.text = "Suhbat xotirasi tozalandi."


if __name__ == "__main__":
    OrionApp().run()
