# The Sofadrop screensaver

Back to the [README](../README.md).

Sofadrop includes a built-in screensaver that shows your wallpapers full screen with a clock (and the weather, if you want). It works with or without Projectivy. Set it up in *Settings → Screensaver*; *Start screensaver*, there, shows it right away. While it runs, **OK** opens the title on screen (*Opening a title*), the **left and right arrows** go to the previous and next wallpaper, and any other button stops it.

![The Sofadrop screensaver with its clock](images/screensaver_en.jpg)

*Start screensaver* also works as a poster mode where the screensaver can't be switched on: it stays on until you press a button, with no setup. Launchers can start it too. And with *Start when Sofadrop is left open*, leaving Sofadrop's settings on screen starts it by itself after 5 minutes, or the time you choose.

## Making it the TV's screensaver

Choose *Screensaver → Use as the TV's screensaver*:

* **Android TV** devices that list other apps' screensavers open Android's screensaver settings: choose **Sofadrop**. On TVs without a direct link it opens Android's settings instead: go to *Device Preferences → Screen saver* (the names can change with the TV's brand).
* **Google TV** (Chromecast with Google TV, Google TV Streamer, TVs with Google TV) only offers Google's own screensavers. Sofadrop's screensaver is switched on **once** from a computer on the same Wi-Fi, with `adb` from Google's [platform-tools](https://developer.android.com/tools/releases/platform-tools). Sofadrop shows these same steps with your TV's address filled in:
  1. Turn on the developer options: *Settings → System → About*, then press **OK** 7 times on *Android TV OS build*.
  2. Turn on wireless debugging: *Settings → System → Developer options → Wireless debugging*, then *Pair device with pairing code*. Leave that screen open: it shows the TV's address, a port and a code.
  3. On the computer, with the TV's address (`IP`):
     ```
     adb pair IP:PORT CODE
     adb connect IP:PORT
     adb shell settings put secure screensaver_components com.rfmarcos.sofadrop/com.rfmarcos.sofadrop.SofascreenService
     ```
     `adb pair` takes the port and code from the pairing screen; `adb connect` takes the port shown under *Wireless debugging* (*IP address & port*).
  4. That's all: wireless debugging and the developer options can be turned off again.
* **Fire TV** depends on the version of Fire OS (*Settings → My Fire TV → About*):
  * **Fire OS 8.1 and above** can't use another screensaver, not even with `adb`: Amazon's Ambient Experience always takes over. Use *Start screensaver*.
  * **Fire OS 7** can, from a computer on the same Wi-Fi with `adb`. These steps come from [Aerial Views](https://github.com/theothernt/AerialViews) and haven't been tested with Sofadrop:
    1. Turn on the developer options: *Settings → My Fire TV → About*, then press **OK** 7 times on the device's name.
    2. Turn on *Developer options → ADB debugging*. The TV's address (`IP`) is in *About → Network*.
    3. On the computer:
       ```
       adb connect IP:5555
       adb shell settings put secure screensaver_components com.rfmarcos.sofadrop/com.rfmarcos.sofadrop.SofascreenService
       adb shell settings put secure screensaver_default_component com.rfmarcos.sofadrop/com.rfmarcos.sofadrop.SofascreenService
       adb shell settings put secure contextual_screen_off_timeout 300000
       adb shell settings put secure screensaver_enabled 1
       ```

> **With Projectivy**, turn off *Settings → Appearance → Launcher Idle Mode → Prevent system screensaver*, then restart the TV: otherwise Projectivy keeps any system screensaver from starting.

## Going back

To Google's screensaver:

```
adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
```

To Amazon's, on Fire OS 7:

```
adb shell settings put secure screensaver_components com.amazon.bueller.photos/.daydream.ScreenSaverService
adb shell settings put secure screensaver_default_component com.amazon.bueller.photos/.daydream.ScreenSaverService
```
