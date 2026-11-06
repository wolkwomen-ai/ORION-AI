# ============================================================
# ORION AI ULTIMATE
# Pydroid 3 + Kivy + Groq + Sketchware
# ============================================================

import os
import re
import ast
import json
import time
import base64
import asyncio
import threading
import mimetypes
import operator
import traceback
import requests

from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

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


# ============================================================
# 1. ASOSIY SOZLAMALAR
# ============================================================

APP_NAME = "ORION AI ULTIMATE"

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

KEY_FILE = os.path.join(BASE_DIR, "orion_key.txt")
HISTORY_FILE = os.path.join(BASE_DIR, "orion_history.json")
MEMORY_FILE = os.path.join(BASE_DIR, "orion_memory.json")
LOG_FILE = os.path.join(BASE_DIR, "orion_log.txt")

API_URL = "https://api.groq.com/openai/v1/chat/completions"

MODEL = "qwen/qwen3.8-27b"

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 8765

MAX_HISTORY = 30
MAX_IMAGE_SIZE = 12 * 1024 * 1024

REQUEST_TIMEOUT = 90

history = []
memory = []

history_lock = threading.RLock()
memory_lock = threading.RLock()

server = None

last_answer = ""


# ============================================================
# 2. ORION SHAXSIYATI
# ============================================================

SYSTEM_PROMPT = """
Sen ORION nomli ilg'or shaxsiy sun'iy intellekt yordamchisisan.

ASOSIY VAZIFALARING:

1. O'zbek tilida ravon muloqot qilish.
2. Murakkab savollarni chuqur tahlil qilish.
3. Dasturlash va kod yozishda yordam berish.
4. Matematik masalalarni yechish.
5. Biznes va daromad loyihalarini rejalashtirish.
6. Rasmlarni tahlil qilish.
7. Texnik muammolarni aniqlash.
8. Foydalanuvchining maqsadlarini tushunish.
9. Oldingi suhbatlardan tegishli kontekstni ishlatish.
10. Rejalarni bosqichlarga ajratish.

QOIDALAR:

- Bilmagan narsangni to'qib chiqarmagin.
- Internetdan tekshirmagan bo'lsang, tekshirdim dema.
- Hech qachon bajarilmagan ishni bajarildi deb aytma.
- Kod yozganda to'liq va ishlatishga tayyor kod ber.
- Xavfli amallarni foydalanuvchining ruxsatisiz bajarma.
- Telefon fayllarini o'zboshimchalik bilan o'zgartirma.
- API kalitlarni hech kimga ko'rsatma.
- Foydalanuvchining administrator huquqini hurmat qil.
- Javoblaring aniq, tushunarli va foydali bo'lsin.

Sen ORION AI'san.
"""


# ============================================================
# 3. LOG TIZIMI
# ============================================================

def write_log(message):

    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:

            f.write(
                datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                + " | "
                + str(message)
                + "\n"
            )

    except Exception:
        pass


# ============================================================
# 4. XOTIRA TIZIMI
# ============================================================

def load_json(path):

    try:

        if os.path.isfile(path):

            with open(path, "r", encoding="utf-8") as f:

                return json.load(f)

    except Exception as e:

        write_log("JSON o'qish xatosi: " + str(e))

    return []


def save_json(path, data):

    try:

        temp = path + ".tmp"

        with open(temp, "w", encoding="utf-8") as f:

            json.dump(
                data,
                f,
                ensure_ascii=False,
                indent=2
            )

        os.replace(temp, path)

    except Exception as e:

        write_log("JSON saqlash xatosi: " + str(e))


def load_history():

    global history

    data = load_json(HISTORY_FILE)

    if isinstance(data, list):

        history = [
            item for item in data[-MAX_HISTORY:]
            if isinstance(item, dict)
            and item.get("role") in ("user", "assistant")
            and isinstance(item.get("content"), str)
        ]


def save_history():

    with history_lock:

        save_json(HISTORY_FILE, history)


def load_memory():

    global memory

    data = load_json(MEMORY_FILE)

    if isinstance(data, list):

        memory = [
            str(x) for x in data
            if isinstance(x, str)
        ]


def remember(text):

    with memory_lock:

        if text not in memory:

            memory.append(text)

            save_json(MEMORY_FILE, memory)

    return "Ma'lumot ORION xotirasiga saqlandi."


def forget_memory():

    global memory

    with memory_lock:

        memory = []

        save_json(MEMORY_FILE, memory)

    return "ORION xotirasi tozalandi."


load_history()
load_memory()


# ============================================================
# 5. API KALIT
# ============================================================

def get_api_key():

    if not os.path.isfile(KEY_FILE):

        raise RuntimeError(
            "orion_key.txt fayli topilmadi."
        )

    with open(KEY_FILE, "r", encoding="utf-8") as f:

        key = f.read().strip()

    if not key:

        raise RuntimeError(
            "API kalit bo'sh."
        )

    return key


# ============================================================
# 6. MATEMATIK HISOBLASH
# ============================================================

OPERATORS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.Pow: operator.pow,
    ast.Mod: operator.mod,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos
}


def safe_calculate(expression):

    if len(expression) > 150:

        raise ValueError("Ifoda juda uzun.")

    def evaluate(node):

        if isinstance(node, ast.Constant):

            if isinstance(node.value, (int, float)):

                return node.value

            raise ValueError("Faqat sonlar mumkin.")

        if isinstance(node, ast.BinOp):

            op_type = type(node.op)

            if op_type not in OPERATORS:

                raise ValueError("Amal ruxsat etilmagan.")

            left = evaluate(node.left)
            right = evaluate(node.right)

            if op_type is ast.Pow and abs(right) > 100:

                raise ValueError("Daraja juda katta.")

            result = OPERATORS[op_type](left, right)

            if abs(result) > 10 ** 100:

                raise ValueError("Natija juda katta.")

            return result

        if isinstance(node, ast.UnaryOp):

            op_type = type(node.op)

            if op_type not in OPERATORS:

                raise ValueError("Amal ruxsat etilmagan.")

            return OPERATORS[op_type](
                evaluate(node.operand)
            )

        raise ValueError("Noto'g'ri matematik ifoda.")

    tree = ast.parse(expression, mode="eval")

    return evaluate(tree.body)


# ============================================================
# 7. RASM TAHLILI
# ============================================================

def image_to_url(path):

    if not os.path.isfile(path):

        raise RuntimeError("Rasm topilmadi.")

    size = os.path.getsize(path)

    if size > MAX_IMAGE_SIZE:

        raise RuntimeError("Rasm 12 MB dan katta.")

    mime = mimetypes.guess_type(path)[0] or "image/jpeg"

    with open(path, "rb") as f:

        encoded = base64.b64encode(
            f.read()
        ).decode("utf-8")

    return "data:" + mime + ";base64," + encoded


# ============================================================
# 8. INTERNET QIDIRUVI
# ============================================================

def internet_search(query):

    try:

        response = requests.get(
            "https://html.duckduckgo.com/html/",
            params={"q": query},
            headers={
                "User-Agent": "Mozilla/5.0"
            },
            timeout=15
        )

        response.raise_for_status()

        from html import unescape

        page = response.text

        results = re.findall(
            r'class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>',
            page,
            re.I | re.S
        )

        snippets = re.findall(
            r'class="result__snippet"[^>]*>(.*?)</(?:a|div)>',
            page,
            re.I | re.S
        )

        output = []

        for i, item in enumerate(results[:7]):

            url = unescape(
                re.sub("<.*?>", "", item[0])
            )

            title = unescape(
                re.sub("<.*?>", "", item[1])
            )

            snippet = ""

            if i < len(snippets):

                snippet = unescape(
                    re.sub("<.*?>", "", snippets[i])
                )

            output.append(
                "Sarlavha: " + title
                + "\nManba: " + url
                + "\nMa'lumot: " + snippet
            )

        if not output:

            return "Qidiruv natijalari topilmadi."

        return "\n\n".join(output)

    except Exception as e:

        return "Internet qidiruvi ishlamadi: " + str(e)


# ============================================================
# 9. MAXSUS BUYRUQLAR
# ============================================================

def process_command(question):

    text = question.strip()

    if text.startswith("/hisob "):

        expression = text[7:]

        try:

            result = safe_calculate(expression)

            return "Hisob natijasi: " + str(result)

        except Exception as e:

            return "Hisoblash xatosi: " + str(e)

    if text.startswith("/eslab qol "):

        return remember(text[11:].strip())

    if text == "/xotira":

        with memory_lock:

            if not memory:

                return "Xotira hozircha bo'sh."

            return "\n".join(
                str(i + 1) + ". " + item
                for i, item in enumerate(memory)
            )

    if text == "/xotirani tozalash":

        return forget_memory()

    if text == "/vaqt":

        return datetime.now().strftime(
            "Sana: %d.%m.%Y | Vaqt: %H:%M:%S"
        )

    if text.startswith("/qidir "):

        query = text[7:].strip()

        if not query:

            return "Qidiruv so'zini yozing."

        return internet_search(query)

    return None


# ============================================================
# 10. ORION AI MIYASI
# ============================================================

def ask_orion(question, image_path=None):

    global history

    question = (question or "").strip()

    if not question and not image_path:

        return "Savol yozing yoki rasm tanlang."

    if question and not image_path:

        command_result = process_command(question)

        if command_result is not None:

            return command_result

    with history_lock:

        messages = [
            {
                "role": "system",
                "content": SYSTEM_PROMPT
            }
        ]

        with memory_lock:

            if memory:

                messages.append({
                    "role": "system",
                    "content": (
                        "Foydalanuvchi haqida saqlangan "
                        "xotiralar:\n"
                        + "\n".join(memory[-50:])
                    )
                })

        messages.extend(history[-MAX_HISTORY:])

    if image_path:

        image_url = image_to_url(image_path)

        user_content = [
            {
                "type": "text",
                "text": question or "Rasmni tahlil qil."
            },
            {
                "type": "image_url",
                "image_url": {
                    "url": image_url
                }
            }
        ]

        user_message = {
            "role": "user",
            "content": user_content
        }

    else:

        user_message = {
            "role": "user",
            "content": question
        }

    messages.append(user_message)

    headers = {
        "Authorization": "Bearer " + get_api_key(),
        "Content-Type": "application/json"
    }

    payload = {
        "model": MODEL,
        "messages": messages,
        "temperature": 0.6,
        "max_completion_tokens": 2500
    }

    try:

        response = requests.post(
            API_URL,
            headers=headers,
            json=payload,
            timeout=REQUEST_TIMEOUT
        )

    except requests.exceptions.Timeout:

        raise RuntimeError(
            "AI javobi kechikdi. Qayta urinib ko'ring."
        )

    except requests.exceptions.ConnectionError:

        raise RuntimeError(
            "Internet bilan aloqa yo'q."
        )

    if response.status_code != 200:

        try:

            detail = response.json().get(
                "error", {}
            ).get("message", response.text)

        except Exception:

            detail = response.text

        raise RuntimeError(
            "Groq xatosi "
            + str(response.status_code)
            + ": "
            + str(detail)[:700]
        )

    data = response.json()

    answer = data["choices"][0]["message"]["content"]

    if isinstance(answer, list):

        answer = " ".join(
            part.get("text", "")
            for part in answer
            if isinstance(part, dict)
        )

    answer = str(answer).strip()

    if not answer:

        answer = "ORION bo'sh javob oldi."

    saved_question = question or "Foydalanuvchi rasm yubordi."

    with history_lock:

        history.append({
            "role": "user",
            "content": saved_question
        })

        history.append({
            "role": "assistant",
            "content": answer
        })

        history[:] = history[-MAX_HISTORY:]

        save_history()

    return answer


# ============================================================
# 11. OVOZ
# ============================================================

def speak(text):

    try:

        import edge_tts
        import pygame

        audio_file = os.path.join(
            BASE_DIR,
            "orion_voice.mp3"
        )

        async def create_voice():

            voice = edge_tts.Communicate(
                text[:3500],
                "uz-UZ-MadinaNeural"
            )

            await voice.save(audio_file)

        asyncio.run(create_voice())

        if not pygame.mixer.get_init():

            pygame.mixer.init()

        pygame.mixer.music.load(audio_file)

        pygame.mixer.music.play()

    except Exception as e:

        write_log("Ovoz xatosi: " + str(e))


# ============================================================
# 12. SKETCHWARE SERVER
# ============================================================

class OrionServer(BaseHTTPRequestHandler):

    def send_json(self, status, data):

        body = json.dumps(
            data,
            ensure_ascii=False
        ).encode("utf-8")

        self.send_response(status)

        self.send_header(
            "Content-Type",
            "application/json; charset=utf-8"
        )

        self.send_header(
            "Content-Length",
            str(len(body))
        )

        self.send_header(
            "Access-Control-Allow-Origin",
            "*"
        )

        self.send_header(
            "Access-Control-Allow-Headers",
            "Content-Type"
        )

        self.send_header(
            "Access-Control-Allow-Methods",
            "GET, POST, OPTIONS"
        )

        self.end_headers()

        self.wfile.write(body)

    def do_OPTIONS(self):

        self.send_json(200, {"ok": True})

    def do_GET(self):

        if self.path == "/health":

            self.send_json(
                200,
                {
                    "ok": True,
                    "name": "ORION AI",
                    "status": "online"
                }
            )

        else:

            self.send_json(
                404,
                {"error": "Manzil topilmadi"}
            )

    def do_POST(self):

        if self.path != "/ask":

            self.send_json(
                404,
                {"error": "Manzil topilmadi"}
            )

            return

        try:

            length = int(
                self.headers.get(
                    "Content-Length", "0"
                )
            )

            if length <= 0 or length > 100000:

                self.send_json(
                    400,
                    {"error": "So'rov hajmi noto'g'ri"}
                )

                return

            incoming = json.loads(
                self.rfile.read(length).decode("utf-8")
            )

            question = str(
                incoming.get(
                    "question",
                    incoming.get(
                        "savol",
                        incoming.get("text", "")
                    )
                )
            ).strip()

            if not question:

                self.send_json(
                    400,
                    {"error": "Savol bo'sh"}
                )

                return

            answer = ask_orion(question)

            self.send_json(
                200,
                {
                    "ok": True,
                    "answer": answer
                }
            )

        except Exception as e:

            write_log(traceback.format_exc())

            self.send_json(
                500,
                {
                    "ok": False,
                    "error": str(e)
                }
            )

    def log_message(self, fmt, *args):

        write_log(fmt % args)


def start_server():

    global server

    if server is not None:

        return

    try:

        server = ThreadingHTTPServer(
            (SERVER_HOST, SERVER_PORT),
            OrionServer
        )

        server.daemon_threads = True

        thread = threading.Thread(
            target=server.serve_forever,
            daemon=True
        )

        thread.start()

        write_log("ORION SERVER ISHLADI")

        print(
            "ORION SERVER:",
            SERVER_HOST,
            SERVER_PORT
        )

    except Exception as e:

        server = None

        write_log("SERVER XATOSI: " + str(e))


# ============================================================
# 13. KIVY INTERFEYS
# ============================================================

class OrionApp(App):

    def build(self):

        self.title = APP_NAME

        self.selected_image = None

        self.last_answer = ""

        root = BoxLayout(
            orientation="vertical",
            padding=dp(8),
            spacing=dp(6)
        )

        self.root_box = root

        title = Label(
            text="[b]ORION AI ULTIMATE[/b]",
            markup=True,
            size_hint_y=None,
            height=dp(45),
            font_size="22sp"
        )

        root.add_widget(title)

        self.status = Label(
            text="ORION tayyor.",
            size_hint_y=None,
            height=dp(32)
        )

        root.add_widget(self.status)

        self.scroll = ScrollView()

        self.chat = BoxLayout(
            orientation="vertical",
            size_hint_y=None,
            spacing=dp(8),
            padding=dp(5)
        )

        self.chat.bind(
            minimum_height=self.chat.setter("height")
        )

        self.scroll.add_widget(self.chat)

        root.add_widget(self.scroll)

        self.input = TextInput(
            hint_text="ORIONga savol yozing...",
            size_hint_y=None,
            height=dp(90),
            multiline=True
        )

        root.add_widget(self.input)

        row = BoxLayout(
            size_hint_y=None,
            height=dp(48),
            spacing=dp(5)
        )

        send = Button(text="YUBORISH")

        send.bind(
            on_release=self.send_message
        )

        photo = Button(text="RASM")

        photo.bind(
            on_release=self.choose_image
        )

        voice = Button(text="OVOZ")

        voice.bind(
            on_release=self.play_voice
        )

        row.add_widget(send)
        row.add_widget(photo)
        row.add_widget(voice)

        root.add_widget(row)

        bottom = BoxLayout(
            size_hint_y=None,
            height=dp(45)
        )

        clear = Button(
            text="SUHBATNI TOZALASH"
        )

        clear.bind(
            on_release=self.clear_chat
        )

        bottom.add_widget(clear)

        root.add_widget(bottom)

        start_server()

        return root

    def add_message(self, title, message):

        label = Label(
            text=title + ":\n" + str(message),
            size_hint_y=None,
            halign="left",
            valign="top"
        )

        label.text_size = (
            max(dp(200), self.root_box.width - dp(35)),
            None
        )

        label.bind(
            texture_size=lambda obj, size:
            setattr(obj, "height", size[1] + dp(15))
        )

        self.chat.add_widget(label)

        Clock.schedule_once(
            lambda dt: setattr(
                self.scroll,
                "scroll_y",
                0
            ),
            0.2
        )

    def send_message(self, instance):

        question = self.input.text.strip()

        image_path = self.selected_image

        if not question and not image_path:

            self.status.text = "Savol yozing."

            return

        self.input.text = ""

        self.selected_image = None

        self.add_message(
            "SIZ",
            question or "[Rasm]"
        )

        self.status.text = "ORION o'ylayapti..."

        threading.Thread(
            target=self.get_answer,
            args=(question, image_path),
            daemon=True
        ).start()

    def get_answer(self, question, image_path):

        try:

            answer = ask_orion(
                question,
                image_path
            )

            Clock.schedule_once(
                lambda dt: self.show_answer(answer)
            )

        except Exception as e:

            write_log(traceback.format_exc())

            Clock.schedule_once(
                lambda dt: self.show_error(str(e))
            )

    def show_answer(self, answer):

        self.last_answer = answer

        self.add_message(
            "ORION",
            answer
        )

        self.status.text = "ORION tayyor."

    def show_error(self, error):

        self.add_message(
            "XATO",
            error
        )

        self.status.text = "Xatolik yuz berdi."

    def choose_image(self, instance):

        chooser = FileChooserIconView(
            filters=[
                "*.png",
                "*.jpg",
                "*.jpeg",
                "*.webp"
            ]
        )

        layout = BoxLayout(
            orientation="vertical"
        )

        layout.add_widget(chooser)

        popup = Popup(
            title="Rasm tanlash",
            content=layout,
            size_hint=(0.95, 0.9)
        )

        buttons = BoxLayout(
            size_hint_y=None,
            height=dp(45)
        )

        def select_image(_):

            if chooser.selection:

                self.selected_image = chooser.selection[0]

                self.status.text = "Rasm tanlandi."

                popup.dismiss()

        select = Button(text="TANLASH")

        select.bind(
            on_release=select_image
        )

        cancel = Button(text="BEKOR")

        cancel.bind(
            on_release=popup.dismiss
        )

        buttons.add_widget(select)
        buttons.add_widget(cancel)

        layout.add_widget(buttons)

        popup.open()

    def play_voice(self, instance):

        if not self.last_answer:

            self.status.text = "Hali javob yo'q."

            return

        self.status.text = "Ovoz tayyorlanmoqda..."

        threading.Thread(
            target=speak,
            args=(self.last_answer,),
            daemon=True
        ).start()

    def clear_chat(self, instance):

        global history

        with history_lock:

            history = []

            save_history()

        self.chat.clear_widgets()

        self.last_answer = ""

        self.status.text = "Suhbat tarixi tozalandi."


# ============================================================
# 14. ISHGA TUSHIRISH
# ============================================================

if __name__ == "__main__":

    try:

        print("=" * 40)

        print("ORION AI ULTIMATE")

        print("Tizim ishga tushmoqda...")

        print("=" * 40)

        OrionApp().run()

    except Exception:

        print(traceback.format_exc())

        write_log(traceback.format_exc())