# Supporting the phone trackpad in your TV app

TV Overlay Suite 1.6+ turns the phone app into a trackpad. Moves reach the suite on the TV over the phone's existing
link; the suite hands them to **the app in front**, if that app asked for them. Your app gets pointer moves, scrolls,
clicks and a few keys, and draws its own pointer.

Communication uses Android's `Messenger` (no network inside the TV, no tokens). Each side can check who the other is:
your app binds the suite by exact package and class, and `Message.sendingUid` on what you receive is the suite's
app id.

## 1. Manifest

```xml
<!-- Android 11+: see the suite's package -->
<queries>
    <package android:name="io.github.tvoverlaysuite" />
</queries>
```

## 2. Bind and say when you are in front

```java
static final ComponentName PAD = new ComponentName("io.github.tvoverlaysuite", "io.github.tvoverlaysuite.PadService");
static final int HELLO = 1, FRONT = 2, LINE = 10;

Messenger me = new Messenger(new Handler(Looper.getMainLooper(), this::onPadMessage));
int suiteUid = getPackageManager().getApplicationInfo("io.github.tvoverlaysuite", 0).uid;
bindService(new Intent().setComponent(PAD), connection, BIND_AUTO_CREATE);

// onServiceConnected: suite = new Messenger(binder); send HELLO, then FRONT.
Message m = Message.obtain(null, FRONT, inFront ? 1 : 0, 0);   // 1 in onResume, 0 in onPause
Bundle b = new Bundle();
b.putString("name", "My App");    // shown on the phone, up to 40 characters
m.setData(b);
m.replyTo = me;                    // every message must carry replyTo
suite.send(m);
```

Only the app that most recently sent `FRONT 1` (and has not sent `FRONT 0` or died since) receives the trackpad.
Send `FRONT 0` in `onPause`, so the phone shows that no trackpad app is in front.

## 3. Receive

```java
boolean onPadMessage(Message m) {
    if (m.what != LINE || m.sendingUid != suiteUid) return true;
    String line = m.peekData().getString("l");
    // ...
}
```

| Line | Meaning |
| --- | --- |
| `m DX DY` | Move the pointer. Pixels of a 1920-pixel-wide screen: multiply by `yourWidth / 1920f`. The phone has already applied acceleration. Several moves can arrive per frame: add them up and apply them on your next frame. |
| `s DX DY` | Scroll what is under the pointer. Positive `DY` = further down the page (content moves up). Momentum after a flick is already included. |
| `c` | Click at the pointer. |
| `r` | Secondary click: your app's menu for what is under the pointer. |
| `d 1` / `d 0` | Press and hold at the pointer (drag) / let go. Moves between them are drag moves. |
| `k KEY` | A key from the buttons under the pad: `back`, `home`, `menu`, `pp` (play / pause). |

Numbers have at most one decimal and are clamped to ±4000. Lines are checked by the suite before they reach you;
ignore anything you don't recognise, so later versions can add lines.

## Tips

- Hold a `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` lock while you are in front and bound. Without it, the TV's Wi-Fi
  power saving can delay each packet from the phone by 100 ms or more. The lock only works while your app is in the
  foreground, which is exactly when the trackpad is in use.
- Apply most of the pending movement on the next frame (for example 70%, the rest on the frame after). That keeps
  up with the finger and still hides Wi-Fi jitter.
