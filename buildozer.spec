[app]

title = ORION AI
package.name = orion
package.domain = org.orion.ai

source.dir = .
source.include_exts = py,png,jpg,jpeg,webp,kv,json
source.exclude_dirs = .git,.github,__pycache__,.buildozer,bin

version = 1.0

requirements = python3,kivy,requests,charset-normalizer==2.1.1

orientation = portrait
fullscreen = 0

android.permissions = INTERNET,CAMERA

android.api = 35
android.minapi = 24
android.ndk = 28c
android.archs = arm64-v8a

p4a.branch = v2026.05.09
p4a.commit = 58d2114
p4a.bootstrap = sdl2

[buildozer]

log_level = 2
warn_on_root = 0
