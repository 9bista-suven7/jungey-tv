# Jungey TV

A control center for a Samsung smart TV, and the command Jungey uses to drive it.

It is its own app, beside Jungey rather than inside it: a window of its own, a jar of its
own, its own settings. Jungey only runs `jungey-tv --json …` and says what comes back.

## Install

```bash
apps/tv/install.sh
```

That builds it and adds the `jungey-tv` command and **Jungey TV** to the app menu, for your
account. On Jungey OS it is already installed. `apps/tv/install.sh --uninstall` takes it
away again.

## First time

The TV has to be on and on the same Wi-Fi. Open Jungey TV, or run:

```bash
jungey-tv pair
```

The TV asks whether to allow "Jungey TV". Choose **Allow** with the TV's remote. That is
the only time it asks. To turn the TV **on** from standby as well as off, enable
*Settings → General → Network → Expert Settings → Power On with Mobile* on the TV.

## The window

Open **Jungey TV** from the menu, or run `jungey-tv` with nothing after it. Every part of
the TV is a card on one screen:

- **Television**: whether it is on, and a switch to turn it on or off
- **Sound**: volume up and down, five at a time, and mute
- **YouTube**: search for anything, or paste a link, and it plays on the TV; the video
  shows under *Now playing*
- **Inputs**: HDMI 1 to 4, and the TV's own list of inputs
- **Navigate**: the arrows, OK, Back and Home, for the TV's menus
- **Apps**: the TV's apps, the usual ones first; click one to open it

The keyboard works too: arrows, Enter, Backspace for back, `+` and `-` for volume, `M` to
mute, `H` for home, Space to play.

## Commands

```
jungey-tv status                 is the TV on, and is it paired
jungey-tv find                   look for Samsung TVs on the network
jungey-tv use 192.168.1.20       use the TV at this address
jungey-tv pair [--reset]         ask the TV for permission
jungey-tv on | off
jungey-tv volume up|down [N]     N presses, 3 if not given
jungey-tv mute
jungey-tv channel up|down [N]
jungey-tv source [hdmi1..hdmi4]
jungey-tv key BUTTON [N]         home, back, up, down, left, right, ok, play, pause, ...
jungey-tv apps                   list the TV's apps
jungey-tv open netflix           open an app by name ("prime", "disney plus", ...)
jungey-tv youtube lofi beats     play the first video found, or a link
```

Add `--json` for `{"ok": true, "message": "…"}` instead of plain text; the message is
worded to be spoken, which is what Jungey does with it.

## Through Jungey

Mention the TV and Jungey hands the rest to Jungey TV:

```
turn on the TV
open Netflix on the TV
play lofi hip hop on YouTube on the TV
TV volume down by 5
mute the TV
switch the TV to HDMI 2
what apps are on the TV
pair the TV
```

Without the TV in the sentence, "volume up" and "open Netflix" still mean this computer.

## How it talks to the TV

- **Finding it**: a UPnP search, then the TV's own description on port 8001.
- **Buttons and apps**: the secure remote-control channel Samsung's phone app uses
  (WebSocket, port 8002). The TV issues a token when Allow is pressed; the TV's
  certificate is remembered at the same moment, and a different one later is refused.
- **YouTube**: DIAL on port 8080, the way a phone casts, so no account or key is needed.
- **Waking it**: a Wake-on-LAN packet to the network address the TV reports.

Settings, including the token, are in `~/.config/jungey-tv/tv.json`, readable by you only.
Delete it to make Jungey TV forget the TV.

Tested with a 2019 Samsung 7 Series (UN55RU7100, Tizen). Samsung TVs from 2016 on use the
same channel; older ones do not.
