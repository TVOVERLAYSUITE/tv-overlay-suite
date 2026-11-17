# Using the phone camera in your TV app

TV Overlay Suite 1.7+ lets a TV app use the phone app's **camera and microphone**, for example for a video call in a
TV web browser. The suite never touches the video: it relays a few set-up lines between your app and the phone app,
and the camera and sound then go **straight from the phone to your app** over the home Wi-Fi with WebRTC.

You need a WebRTC stack on the TV side. A `WebView` page is the simplest (`RTCPeerConnection` is built in).

## 1. Connect

Bind to the suite's `PadService` exactly as for the trackpad ([trackpad.md](trackpad.md), steps 1 and 2): send
`HELLO`, and `FRONT 1` / `FRONT 0` as your app comes to the front and leaves it. **Only the app in front may open the
phone camera.**

```java
static final int HELLO = 1, FRONT = 2, CAM = 3, LINE = 10, CAM_LINE = 11;

void camSend(String line) {            // to the suite
    Message m = Message.obtain(null, CAM);
    Bundle b = new Bundle();
    b.putString("l", line);
    m.setData(b);
    m.replyTo = me;
    suite.send(m);
}

boolean onPadMessage(Message m) {      // from the suite
    if (m.sendingUid != suiteUid) return true;
    if (m.what == CAM_LINE) onCam(m.peekData().getString("l"));
    return true;
}
```

## 2. Lines

`SID` is 8 letters or digits that you choose for each session. `SDP` is the WebRTC session description in
**base64url** (no padding). The phone makes the offer; your app answers.

| Your app → suite | Meaning |
| --- | --- |
| `o SID KIND FACING` | Open. `KIND`: `v` camera, `a` microphone, `va` both. `FACING`: `u` front, `e` back. |
| `s SID SDP` | Your WebRTC answer. |
| `f SID FACING` | Switch to the front / back camera. |
| `x SID` | End the session. |

| Suite → your app | Meaning |
| --- | --- |
| `y N` | `N` phone apps are ready to be a camera. Sent after `HELLO` and whenever it changes. |
| `s SID SDP` | The phone's WebRTC offer. |
| `h SID STATE [MSG]` | `wait` (the phone is asking its owner: allow up to 2 minutes), `live`, `deny`, `err`. `MSG` is a base64url reason. |
| `x SID` | The session ended on the phone (or the phone went away). |

Sessions belong to the app and the phone that started them: nobody else can feed them or end them. They end when your
app or the phone disconnects. Up to 4 sessions per app.

## 3. A session in a WebView page

```js
// after sending "o SID va u":
const pc = new RTCPeerConnection({ iceServers: [] });     // same Wi-Fi: no STUN / TURN needed
pc.ontrack = e => attach(e.track);                         // the phone's camera / microphone
// on "s SID SDP":
await pc.setRemoteDescription({ type: 'offer', sdp });
await pc.setLocalDescription(await pc.createAnswer());
// wait for ICE gathering to complete (or ~2 s), then send "s SID " + base64url(pc.localDescription.sdp)
```

The phone sends up to 1280×720 at 30 fps (about 2.5 Mbit/s). It needs its **Phone camera** screen open during the
call: Android doesn't let an app use the camera from the background.

## Privacy

The phone asks its owner the first time each TV app (by package name) wants the camera, and shows the app's name. The
phone app's "Forget camera permissions" withdraws every app's permission.
