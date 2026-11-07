[app]
title = ORION A17 TEST
package.name = oriona17test
package.domain = org.orion.test
source.dir = .
source.include_exts = py,kv,png,jpg
version = 1.0
requirements = python3==3.12.10,hostpython3==3.12.10,kivy==2.3.1
orientation = portrait
fullscreen = 0
android.permissions =
android.api = 35
android.minapi = 26
android.ndk = 25b
android.archs = arm64-v8a
android.ant_path = /usr/share/ant
p4a.bootstrap = sdl2

[buildozer]
log_level = 2
warn_on_root = 0
