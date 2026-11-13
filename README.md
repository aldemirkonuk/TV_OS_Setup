# Maison launcher

An Android TV home screen for the Mi TV Stick and Fire TV. It shows the Maison launcher full-screen,
fills the **TV Apps** row with the apps installed on the stick, and opens them for real. The Wi-Fi and
Settings icons open the stick's own settings.

The whole interface is one web page: `app/src/main/assets/index.html`. Edit it and rebuild to change the look.

## Build the APK (no Android Studio needed)

1. Create a new repository on GitHub and upload this whole folder (keep the `.github` folder).
2. Open the **Actions** tab. The "Build Maison APK" run starts by itself after the upload
   (or press **Run workflow**).
3. When it turns green, open the run and download **maison-launcher** (a zip containing `maison-launcher.apk`).

With Android Studio instead: *File → Open* this folder, then *Build → Build APK(s)*.

## Install on the TV

- **Downloader app** (Mi Stick and Fire TV): put the APK somewhere with a link (e.g. a GitHub release),
  open the link in Downloader, and allow "install unknown apps" for Downloader when asked.
- **ADB** from a computer on the same Wi-Fi (Developer options → USB/ADB debugging on):

  ```
  adb connect <tv-ip-address>
  adb install -r maison-launcher.apk
  ```

## Make it the home screen

Open Maison → Settings (gear, top right) → **Make Maison my home screen**, then switch on
**Maison home button** in Accessibility. From then on, whenever the built-in home screen appears,
Maison opens on top of it. Projectivy uses the same approach. Switch Projectivy's own override off
so they don't compete.

The stock launcher stays installed, so you can always turn the accessibility switch off to go back.

## Remote

| Button | Action |
| --- | --- |
| ↑ / ↓ | Move between rows. ↑ from the first row reaches Wi-Fi, Settings and Profile |
| ← / → | Move along a row |
| OK | Open |
| Back | Close panels; otherwise jump back to the apps row |

## Prototype features

- **Hold OK** (or the Menu button) on an app: move it, hide it, open app info, uninstall. On a show: resume, start over, remove, mark as watched.
- **Search** (top bar): on-screen keyboard; finds apps, shows, songs, photos and settings, or hands the text to the assistant.
- **Quick settings** (lightning icon): sleep timer, night mode, Proton VPN, free up memory, turn the TV off.
- **Kids profile** asks for a PIN (1234 until you change it in Settings) before switching away.
- **Daily photo** wallpaper (Settings → Wallpaper source) loads a new photograph each day from picsum.photos.
- **Stick health** in Settings shows free memory and storage.

Turning the TV off and the Home button override both need the "Maison home button" accessibility switch.

## Gemini pop-up (hold the assistant button)

With the **Maison home button** accessibility switch on, Maison watches only the remote's assistant/search
button:

- **Hold** it: a small Gemini window opens over whatever is playing. It listens through the remote's
  mic, answers in a few sentences using Google Search, and can read the answer aloud.
- **Short press**: the normal assistant opens, as before.
- Change this in Maison → Settings → **Assistant button** (Hold opens Gemini / Press opens Gemini / Off).

The pop-up is a single small web page (`assets/gemini.html`), not a separate app, so it opens quickly
even on the 1st-gen Mi TV Stick.

**Gemini needs your own free API key:**

1. On a computer, go to **aistudio.google.com** → *Get API key* → *Create API key*.
2. Either type it on the TV in Maison → Settings → **Gemini API key**, or send it from the computer:

   ```
   adb shell am start -n com.maison.launcher/.MainActivity --es gemini_key YOUR_KEY
   ```

The model defaults to `gemini-flash-latest`. To use another one, send `--es gemini_model MODEL_NAME`
the same way.

## Cowork (Gemini operates the TV)

In the Gemini pop-up, switch on **⚙ Cowork**. Then ask for things in plain words, for example
"movie night: dim the lights and resume Frieren" or "play some jazz and set a 30 minute sleep timer".
Gemini decides the actions and Maison carries them out on screen, moving the focus like the remote does,
with a step list you can stop at any time.

- On the Maison home screen it can open apps, resume shows, control music, lights, scenes, the door,
  heating, sleep timer, night mode, profiles (never leaving Kids without the PIN), the door camera,
  the screensaver and settings.
- If you hold the button while another app is open, the request is handed to Maison, which comes to
  the front and carries it out.
- It cannot yet click around *inside* other apps (for example Netflix menus).
- It uses the same Gemini API key as the pop-up. The setting is remembered.

## Google account and Google Photos screensaver

Settings → **Google account** links your account with a code you enter on your phone
(google.com/device). Then **Choose Google Photos** shows a QR code. Scan it, pick photos in Google Photos
on your phone, and tap Done. The TV downloads them (up to 200) and **Screensaver photos** switches to
*Google Photos*. You can switch between *Maison*, *Google Photos* and *Maison + Google Photos* at any time.

Google requires every TV app to have its own sign-in key. It's free and takes about ten minutes:

1. Go to **console.cloud.google.com** and create a project.
2. *APIs & Services → Library*: enable **Google Photos Picker API**.
3. *OAuth consent screen*: choose *External*, fill in the app name, add your own Google account as a
   **test user**, and add the scope `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`.
4. *Credentials → Create credentials → OAuth client ID*, type **TVs and Limited Input devices**.
5. Copy the client ID and secret into Maison (Settings → Google account asks for them), or send them:

   ```
   adb shell am start -n com.maison.launcher/.MainActivity --es google_client_id ID --es google_client_secret SECRET
   ```

Unlinking removes the downloaded photos from the TV.

## Notes

- Needs an up-to-date **Android System WebView** (update it from the Play Store on the Mi Stick).
- On the 1st-gen Mi TV Stick, keep **Settings → Lighter effects** on (it is on by default in the app).
- Continue watching, Music and Home show sample data for now. Hooking them to real services
  (Home Assistant, Trakt or the apps themselves) is the next step.
