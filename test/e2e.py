"""Two simulated phones (landscape + portrait) against TestHost on port 8765.
Start the stand-in TV first (pairing code TEST23):  java -cp build/test TestHost assets   (after test/run-tests.sh)
Usage: python3 e2e.py [chrome-path] [lan-ip-for-insecure-test]
Checks drawing sync, every tab (Dim, Message, Pointer, Photo), pairing (QR code, typed code, wrong code,
remembered code), and writes shot-*.png per tab into the current folder."""
import asyncio, math, sys, urllib.request
from playwright.async_api import async_playwright

CHROME = sys.argv[1] if len(sys.argv) > 1 else "/usr/bin/google-chrome"
LAN_IP = sys.argv[2] if len(sys.argv) > 2 else None
URL = "http://127.0.0.1:8765/"
PAIR = "#k=TEST23"   # TestHost's default pairing code; the QR on the TV carries it the same way
results = []

def ok(cond, what):
    results.append((bool(cond), what)); print(("PASS " if cond else "FAIL ") + what)

def log():
    return urllib.request.urlopen(URL + "_log").read().decode("utf-8").splitlines()

def reset():
    urllib.request.urlopen(URL + "_reset").read()

async def draw(page, pts, steps=1):
    box = await page.locator('#live').bounding_box()
    X = lambda x: box['x'] + box['width'] * x / 1920; Y = lambda y: box['y'] + box['height'] * y / 1080
    await page.mouse.move(X(pts[0][0]), Y(pts[0][1])); await page.mouse.down()
    for x, y in pts[1:]: await page.mouse.move(X(x), Y(y), steps=steps)
    await page.mouse.up()

async def ink_pixels(page):
    return await page.evaluate("""()=>{const c=document.querySelector('#base');if(!c.width)return 0;const d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let n=0;for(let i=3;i<d.length;i+=4) if(d[i]>200) n++;return n;}""")

async def ink_ratio(page):
    return await page.evaluate("""()=>{const c=document.querySelector('#base');const d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let n=0;for(let i=3;i<d.length;i+=4) if(d[i]>200) n++;return n/(c.width*c.height);}""")

async def tab(page, name):
    await page.click(f'#tabs button[data-tab="{name}"]'); await asyncio.sleep(0.25)

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=["--no-proxy-server"])
        ctx1 = await b.new_context(viewport={'width': 844, 'height': 390}, device_scale_factor=3, has_touch=True, is_mobile=True)
        ctx2 = await b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=3, has_touch=True, is_mobile=True)
        A = await ctx1.new_page(); B = await ctx2.new_page()
        errs = []
        for pg in (A, B):
            pg.on('pageerror', lambda e: errs.append(str(e)))
            pg.on('console', lambda m: errs.append(m.text) if m.type == 'error' else None)
        await A.goto(URL + PAIR); await B.goto(URL + PAIR); await asyncio.sleep(1)

        # ---------------- v1.1 drawing (unchanged behaviour) ----------------
        ok("Connected" in await A.inner_text('#statusText'), "phone A connects")
        pts = [(300 + i * 12, 500 + 120 * math.sin(i / 6)) for i in range(80)]
        await draw(A, pts); await asyncio.sleep(0.6)
        a1 = await ink_pixels(A); ra, rb = await ink_ratio(A), await ink_ratio(B)
        ok(a1 > 1000 and abs(ra - rb) / ra < 0.35, f"stroke syncs A->B (coverage {ra:.4f} vs {rb:.4f}; thin lines scale differently on the small board)")
        ok(await B.evaluate("document.querySelector('#eye').classList.contains('on')"), "drawing auto-shows the layer")
        await B.click('.sw[data-c="1f5fe0"]'); await B.click('#size')
        await draw(B, [(200, 200), (900, 260), (1500, 220)], steps=20); await asyncio.sleep(0.6)
        a2, b2 = await ink_pixels(A), await ink_pixels(B); ok(a2 > a1, "stroke B->A")
        await A.click('#eraser'); await draw(A, [(250, 500), (1200, 500)], steps=30); await asyncio.sleep(0.6)
        a3, b3 = await ink_pixels(A), await ink_pixels(B); ok(a3 < a2 and b3 < b2, "eraser syncs")
        await A.click('#undo'); await asyncio.sleep(0.6)
        ok(abs(await ink_pixels(A) - a2) < a2 * 0.03, "undo eraser restores")
        await A.screenshot(path='shot-draw-landscape.png'); await B.screenshot(path='shot-draw-portrait.png')

        # ---------------- Dim ----------------
        await tab(B, 'pDim'); await tab(A, 'pDim')
        await B.evaluate("""()=>{const el=document.querySelector('#warm');el.value=0;el.dispatchEvent(new Event('input'));}"""); await asyncio.sleep(0.3)
        reset()
        await B.click('#presets button[data-v="50"]'); await asyncio.sleep(0.5)
        ok("DIM 50 0" in log(), "Dim preset 50 sends d 50 0")
        ok(await A.inner_text('#dimVal') == "50", "other phone shows the echoed level")
        reset()
        n_frames = await B.evaluate("""async()=>{const el=document.querySelector('#dim');let f=0;
            for(let v=0;v<=80;v+=2){el.value=v;el.dispatchEvent(new Event('input'));if(v%6==0){await new Promise(r=>requestAnimationFrame(r));f++;}}
            await new Promise(r=>setTimeout(r,200));return f;}""")
        dims = [l for l in log() if l.startswith("DIM")]
        ok(0 < len(dims) <= n_frames + 2 and dims[-1] == "DIM 80 0", f"slider drag throttled to frames ({len(dims)} msgs for {n_frames} frames, last {dims[-1] if dims else None})")
        await B.evaluate("""()=>{const el=document.querySelector('#warm');el.value=60;el.dispatchEvent(new Event('input'));}"""); await asyncio.sleep(0.3)
        ok(log()[-1] == "DIM 80 60", "warmth slider sends d 80 60")
        await A.screenshot(path='shot-dim-landscape.png'); await B.screenshot(path='shot-dim-portrait.png')
        C = await ctx1.new_page(); await C.goto(URL + PAIR); await asyncio.sleep(1)
        await tab(C, 'pDim')
        ok(await C.inner_text('#dimVal') == "80" and await C.inner_text('#warmVal') == "60", "late joiner gets the dimmer level in the snapshot")
        await tab(C, 'pDraw'); await asyncio.sleep(0.4)
        cpx = await ink_pixels(C); ok(abs(cpx - a2) < a2 * 0.05, f"late joiner gets the drawing ({cpx} vs {a2})")
        await C.close()
        await B.click('#presets button[data-v="0"]'); await asyncio.sleep(0.3)
        ok(log()[-1] == "DIM 0 60", "Off preset sends d 0")

        # ---------------- Message ----------------
        reset()
        await tab(B, 'pMessage'); await tab(A, 'pMessage')
        await B.fill('#msg', 'Goal!! ⚽ ¿Viste eso?')
        await B.click('#send'); await asyncio.sleep(0.4)
        ok("POP 5 1 t Goal!! ⚽ ¿Viste eso?" in log(), "text pop-up, default 5 s center, UTF-8 intact")
        ok(await B.input_value('#msg') == "", "text box clears after send")
        await B.click('#dur button[data-v="10"]'); await B.click('#where button[data-v="top"]')
        await B.click('#stickers button >> nth=10'); await asyncio.sleep(0.4)
        ok(log()[-1] == "POP 10 0 s ❤️", f"sticker pop-up with 10 s, top ({log()[-1]})")
        await B.fill('#msg', 'line one\nline two'); await B.click('#send'); await asyncio.sleep(0.3)
        ok(log()[-1] == "POP 10 0 t line one\\nline two", "two-line message keeps its line break")
        await B.reload(); await asyncio.sleep(1)
        rec = await B.locator('#recent button').count()
        ok(rec == 2, f"recent list survives reload ({rec} items)")
        ok(await B.evaluate("document.querySelector('#where button.on').dataset.v") == "top", "position choice remembered")
        await B.click('#recent button >> nth=1'); await asyncio.sleep(0.3)
        ok(log()[-1].startswith("POP 10 0 t Goal!!"), "tapping a recent message sends it again")
        await A.screenshot(path='shot-message-landscape.png'); await B.screenshot(path='shot-message-portrait.png')

        # ---------------- Pointer ----------------
        reset()
        await tab(A, 'pPointer'); await tab(B, 'pPointer')
        ok(await A.evaluate("document.querySelector('#pmode button.on').dataset.m") == "gyro", "secure page: Motion is the default mode")
        ok(not await A.evaluate("document.querySelector('#gyroHelp').classList.contains('show')"), "no how-to shown when motion is available")
        box = await A.locator('#pad').bounding_box()
        cx, cy = box['x'] + box['width'] / 2, box['y'] + box['height'] / 2
        await A.mouse.move(cx, cy); await A.mouse.down(); await asyncio.sleep(0.15)
        # phone flat, turning left (alpha +) then tilting up (beta +)
        await A.evaluate("""async()=>{const ev=(a,b)=>window.dispatchEvent(new DeviceMotionEvent('devicemotion',{accelerationIncludingGravity:{x:0,y:0,z:9.81},rotationRate:{alpha:a,beta:b,gamma:0},interval:16}));
            for(let i=0;i<30;i++){ev(30,0);await new Promise(r=>setTimeout(r,16));}
            for(let i=0;i<30;i++){ev(0,20);await new Promise(r=>setTimeout(r,16));}}""")
        await asyncio.sleep(0.2)
        las = [l for l in log() if l.startswith("LASER")]
        xs = [int(l.split()[1]) for l in las]; ys = [int(l.split()[2]) for l in las]
        ok(len(las) > 10 and xs[0] == 960 and min(xs) < 820, f"Motion: turning left moves the dot left (x {xs[0]}→{min(xs) if xs else None})")
        ok(ys and min(ys) < 460, f"Motion: tilting up moves the dot up (y→{min(ys) if ys else None})")
        # upright phone (screen facing you, back toward the TV): turning left = rotation around the phone's y axis
        reset()
        await A.evaluate("""async()=>{const ev=(g)=>window.dispatchEvent(new DeviceMotionEvent('devicemotion',{accelerationIncludingGravity:{x:0,y:9.81,z:0},rotationRate:{alpha:0,beta:0,gamma:g},interval:16}));
            for(let i=0;i<20;i++){ev(0);await new Promise(r=>setTimeout(r,16));}
            for(let i=0;i<30;i++){ev(-30);await new Promise(r=>setTimeout(r,16));}}""")
        await asyncio.sleep(0.2)
        xs2 = [int(l.split()[1]) for l in log() if l.startswith("LASER")]
        ok(len(xs2) > 5 and xs2[-1] > xs2[0] + 100, f"Motion, phone upright: turning right moves the dot right ({xs2[0] if xs2 else None}→{xs2[-1] if xs2 else None})")
        # Motion mode: dragging the finger must NOT move the dot
        reset()
        await A.mouse.move(cx + 200, cy + 80, steps=15); await asyncio.sleep(0.2)
        moved_xs = {l for l in log() if l.startswith("LASER")}
        ok(len(moved_xs) <= 1, f"Motion mode: dragging the finger does not move the dot ({len(moved_xs)} distinct positions)")
        # hold still: keep-alive messages so the TV keeps the dot
        reset(); await asyncio.sleep(2.0)
        ok(len([l for l in log() if l.startswith("LASER")]) >= 2, "holding still sends keep-alive positions (dot stays on TV)")
        await A.mouse.up()
        reset(); await asyncio.sleep(0.4)
        await A.evaluate("""()=>{for(let i=0;i<5;i++) window.dispatchEvent(new DeviceMotionEvent('devicemotion',{accelerationIncludingGravity:{x:0,y:0,z:9.81},rotationRate:{alpha:40,beta:0,gamma:0},interval:16}));}""")
        await asyncio.sleep(0.2)
        ok(not [l for l in log() if l.startswith("LASER")], "motion ignored when the finger is lifted")
        await A.screenshot(path='shot-pointer-landscape.png')
        # trackpad + double tap on phone B
        await B.click('#pmode button[data-m="pad"]')
        reset()
        bb = await B.locator('#pad').bounding_box()
        bx, by = bb['x'] + 60, bb['y'] + bb['height'] / 2
        await B.mouse.move(bx, by); await B.mouse.down()
        await B.mouse.move(bx + 150, by, steps=15); await B.mouse.up(); await asyncio.sleep(0.2)
        xs3 = [int(l.split()[1]) for l in log() if l.startswith("LASER")]
        ok(len(xs3) > 3 and xs3[-1] > xs3[0] + 300, f"Trackpad drag moves the dot ({xs3[0] if xs3 else None}→{xs3[-1] if xs3 else None})")
        reset()
        await B.mouse.click(bx, by); await asyncio.sleep(0.08); await B.mouse.click(bx, by); await asyncio.sleep(0.3)
        ok(any(l.startswith("PING") for l in log()), "double-tap sends a ping")
        await B.click('#recenter'); await asyncio.sleep(0.2)
        ok(log()[-1] == "LASER 960 540", "Re-center puts the dot in the middle")
        await B.screenshot(path='shot-pointer-portrait.png')

        # ---------------- Photo (picture-in-picture) ----------------
        from PIL import Image
        Image.new('RGB', (4000, 3000), (40, 120, 200)).save('/tmp/test-photo.jpg', quality=92)
        reset()
        await tab(B, 'pPhoto'); await tab(A, 'pPhoto')
        ok(await B.is_disabled('#showPhoto'), "Show on TV disabled until a photo is chosen")
        await B.set_input_files('#file', '/tmp/test-photo.jpg'); await asyncio.sleep(1.0)
        ok(not await B.is_disabled('#showPhoto') and await B.locator('#preview img').count() == 1, "preview shown after choosing a photo")
        await B.click('#showPhoto'); await asyncio.sleep(1.0)
        ph = [l for l in log() if l.startswith("PHOTO ")]
        ok(ph and ph[0].startswith("PHOTO br m") and ph[0].endswith("FFD8") and int(ph[0].split()[3]) < 400000,
           f"photo sent shrunk as JPEG, bottom-right medium ({ph[0] if ph else None})")
        ok("On the TV" in await B.inner_text('#photoState') and "On the TV" in await A.inner_text('#photoState'), "both phones show 'On the TV'")
        await B.click('#posPick button[data-v="tl"]'); await B.click('#psize button[data-v="l"]'); await asyncio.sleep(0.4)
        ok(log()[-1] == "PHOTOMOVE tl l" and "top left · large" in await A.inner_text('#photoState'), "position/size change moves it live without resending")
        ok(len([l for l in log() if l.startswith("PHOTO ")]) == 1, "the image itself was sent only once")
        await A.screenshot(path='shot-photo-landscape.png'); await B.screenshot(path='shot-photo-portrait.png')
        C2 = await ctx2.new_page(); await C2.goto(URL + PAIR); await asyncio.sleep(1); await tab(C2, 'pPhoto')
        ok("top left · large" in await C2.inner_text('#photoState'), "late joiner sees the photo state")
        await C2.close()
        await A.click('#removePhoto'); await asyncio.sleep(0.4)
        ok(log()[-1] == "PHOTO -" and await B.is_disabled('#removePhoto'), "Remove from TV works and updates every phone")

        # ---------------- tab bar sanity ----------------
        for pg, nm in ((A, 'landscape'), (B, 'portrait')):
            tb = await pg.locator('#tabs').bounding_box()
            vw = pg.viewport_size
            if nm == 'landscape': ok(tb['x'] + tb['width'] >= vw['width'] - 1 and tb['height'] > vw['height'] * 0.9, "landscape: tabs are a right-side rail")
            else: ok(tb['y'] + tb['height'] >= vw['height'] - 1 and tb['width'] > vw['width'] * 0.9, "portrait: tabs are a bottom bar")
        await tab(A, 'pDraw'); await asyncio.sleep(0.4)
        ok(await ink_pixels(A) > 1000, "drawing still there after switching tabs")

        # ---------------- plain http on a LAN address (like the real TV) ----------------
        if LAN_IP:
            D = await ctx2.new_page(); await D.goto(f"http://{LAN_IP}:8765/" + PAIR); await asyncio.sleep(1)
            await tab(D, 'pPointer')
            ok(not await D.evaluate("window.isSecureContext"), "LAN page is an insecure context (as on the TV)")
            ok(await D.evaluate("document.querySelector('#pmode button.on').dataset.m") == "pad", "insecure page falls back to Trackpad")
            txt = await D.inner_text('#gyroHelp')
            ok("unsafely-treat-insecure-origin-as-secure" in txt and f"http://{LAN_IP}:8765" in txt, "how-to shows the Chrome flag and this page's exact address")
            await D.screenshot(path='shot-pointer-insecure-portrait.png')

        # ---------------- pairing (v1.4) ----------------
        ok(await A.evaluate("location.hash") == "", "pairing code removed from the address bar after load")
        ctx3 = await b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=3, has_touch=True, is_mobile=True)
        E = await ctx3.new_page()
        E.on('pageerror', lambda e: errs.append(str(e)))
        await E.goto(URL); await asyncio.sleep(1)
        ok(await E.is_visible('#pair'), "no code: pairing sheet shown, no connection")
        ok("Connected" not in await E.inner_text('#statusText'), "no code: not connected")
        await E.fill('#pairCode', 'abcdef'); await E.click('#pairGo'); await asyncio.sleep(1.2)
        ok(await E.is_visible('#pair') and "did not accept" in await E.inner_text('#pairMsg'), "wrong code: TV refuses, sheet says so")
        await E.screenshot(path='shot-pair-wrong-code.png')
        await E.fill('#pairCode', 'tes 23'); await asyncio.sleep(0.1)
        ok(await E.is_disabled('#pairGo'), "Connect stays disabled until 6 valid characters (spaces ignored)")
        await E.fill('#pairCode', 'tes t23'); await E.click('#pairGo'); await asyncio.sleep(1.2)
        ok(not await E.is_visible('#pair') and "Connected" in await E.inner_text('#statusText'), "typed code (lower case) pairs")
        ok(await ink_pixels(E) > 1000, "paired phone gets the drawing snapshot")
        await E.reload(); await asyncio.sleep(1.2)
        ok(not await E.is_visible('#pair') and "Connected" in await E.inner_text('#statusText'), "reload without the QR reconnects with the remembered code")
        await ctx3.close()

        ok(not errs, f"no page errors {errs}")
        await b.close()
    bad = [w for c, w in results if not c]
    print("\nALL PASS" if not bad else f"\n{len(bad)} FAILED: {bad}")

asyncio.run(main())
