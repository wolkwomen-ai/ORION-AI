from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.button import Button

class OrionA17Test(App):
    def build(self):
        root = BoxLayout(orientation="vertical", padding=24, spacing=20)
        root.add_widget(Label(
            text="ORION A17 TEST\n\nAgar bu yozuv ko'rinsa, Kivy ishga tushdi.",
            halign="center"
        ))
        btn = Button(
            text="TEKSHIRUV MUVAFFAQIYATLI",
            size_hint_y=None,
            height=64
        )
        root.add_widget(btn)
        return root

if __name__ == "__main__":
    OrionA17Test().run()
