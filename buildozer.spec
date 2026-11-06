[app]

title = ORION AI
package.name = orion
package.domain = org.orion.ai

source.dir = .
source.include_exts = py,png,jpg,jpeg,webp,kv,json
source.exclude_dirs = .git,.github,__pycache__,.buildozer,bin

version = 1.0

requirements = python3==3.12.10,hostpython3==3.12.10,kivy,requests

orientation = portrait
fullscreen = 0

android.permissions = INTERNET,CAMERA

android.api = 35
android.minapi = 24
android.ndk = 28c
android.archs = arm64-v8a

p4a.bootstrap = sdl2

[buildozer]

log_level = 2
warn_on_root = 0
