[app]
title = ORION AI
package.name = orion
package.domain = org.orion.ai
source.dir = .
source.include_exts = py,png,jpg,kv,json
source.exclude_dirs = .git,.github,__pycache__
version = 1.0
requirements = python3,kivy,requests,certifi,pillow
orientation = portrait
fullscreen = 0
android.permissions = INTERNET,CAMERA
android.api = 35
android.minapi = 24
android.archs = arm64-v8a

[buildozer]
log_level = 2
warn_on_root = 18
