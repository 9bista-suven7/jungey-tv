# Jungey TV

A control center for a Samsung smart TV, and the command Jungey uses to drive it.

It is its own app, beside Jungey rather than inside it: a window of its own, a jar of its
own, its own settings. Jungey only runs `jungey-tv --json …` and says what comes back.

## Install

```bash
./install.sh
```

That builds it and adds the `jungey-tv` command and **Jungey TV** to the app menu, for your
account. On Jungey OS it is already installed. `./install.sh --uninstall` takes it
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
- **Cast**: this computer's screen and sound on the TV, like an HDMI cable without the
  cable. Press *Start casting*; the TV shows it a few seconds later. Playing a film in the
  browser? Press F11 for full screen. With *Sound on the TV* on, the sound moves to the TV
  while casting and comes back when it stops. Stop it here, or with Back on the TV's remote.
- **Inputs**: HDMI 1 to 4, and the TV's own list of inputs
- **Navigate**: the arrows, OK, Back and Home for the TV's menus, and rewind, play, pause
  and fast forward
- **Apps**: the TV's apps, the usual ones first; click one to open it

The keyboard works too: arrows, Enter, Backspace for back, `+` and `-` for volume, `M` to
mute, `H` for home, Space to play.

### Searching YouTube

The bar at the top searches YouTube, over the whole window: twenty videos at a time with
their thumbnails, length, channel, views and age, and *More results* for the next twenty.
Filters, as on the website: All, Videos, Movies, Live, 20+ min and Newest. Click a video to
play it on the TV; paste a link to play it at once. Beside the results is the TV's remote -
the arrows, OK, Back and Home, and play and pause - for when YouTube on the TV asks who is
watching.

### Casting needs

ffmpeg (`sudo apt install ffmpeg`) and an X11 desktop. The TV fetches the picture from this
computer, so both must be on the same network; only the TV's address is let in.

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
jungey-tv search youtube lofi    list videos; add --filter movies|live|long|newest|videos
jungey-tv search the office      movies and shows, and which apps have them
jungey-tv watch the office on peacock
                                 open a movie or show in the app that has it
jungey-tv type friends           type into the TV's on-screen keyboard
jungey-tv cast [--no-sound]      this computer on the TV until Ctrl+C
jungey-tv browse lofi beats      open the window at YouTube's results
```

Add `--json` for `{"ok": true, "message": "…"}` instead of plain text; the message is
worded to be spoken, which is what Jungey does with it.

## Through Jungey

Mention the TV and Jungey hands the rest to Jungey TV:

```
turn on the TV
open Netflix on the TV
watch Stranger Things on Netflix
watch The Office on the TV
play lofi hip hop on YouTube on the TV
search YouTube for cooking videos on the TV    then: play number two / show them
search for Inception on the TV                 then: watch the first one / show them
type friends on the TV
TV volume down by 5
switch the TV to HDMI 2
pair the TV
```

*Watch* finds a film or series in your apps; *play* finds a YouTube video. After a search
Jungey reads out the top three and lists ten; for ten minutes "play number two" picks one,
and "show them" opens the window at every result.

Without the TV in the sentence, "volume up" and "open Netflix" still mean this computer.
Naming a streaming service is enough, though: "watch the office on peacock" goes to the TV.

## How it talks to the TV

- **Finding it**: a UPnP search, then the TV's own description on port 8001.
- **Buttons and apps**: the secure remote-control channel Samsung's phone app uses
  (WebSocket, port 8002). The TV issues a token when Allow is pressed; the TV's
  certificate is remembered at the same moment, and a different one later is refused.
- **YouTube**: DIAL on port 8080, the way a phone casts, so no account or key is needed.
  Searching asks the service YouTube's own website uses, twenty results a page.
- **Movies & shows**: JustWatch's listings of what streams where, from the service its
  website uses. It has no published API, so this may change without notice.
- **Typing**: the remote channel's text input, which fills whatever keyboard is open on the
  TV.
- **Waking it**: a Wake-on-LAN packet to the network address the TV reports.

Settings, including the token, are in `~/.config/jungey-tv/tv.json`, readable by you only.
Delete it to make Jungey TV forget the TV.

Tested with a 2019 Samsung 7 Series (UN55RU7100, Tizen). Samsung TVs from 2016 on use the
same channel; older ones do not.
