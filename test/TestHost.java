import io.github.tvoverlaysuite.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Desktop stand-in for the TV: runs InkServer + Ink + Extras on port 8765 and serves assets/.
 *  Usage: java -cp build/test TestHost <assets-dir> [pairing-code]   (default code TEST23; "-" turns pairing off)
 *  GET /_log returns the v1.2 commands the "TV" received (dimmer / pop-ups / laser), one per line. */
public class TestHost {
  static final List<String> log = Collections.synchronizedList(new ArrayList<>());
  static volatile int dim = 0, warm = 0;
  static volatile String photo = "I 0";
  public static void main(String[] a) throws Exception {
    final String assets = a.length > 0 ? a[0] : "assets";
    final Ink ink = new Ink(null);
    final InkServer[] srv = new InkServer[1];
    final boolean[] shown = {false};
    final Extras.Sink sink = new Extras.Sink() {
      public void dim(int l, int w) { dim = l; if (w >= 0) warm = w; log.add("DIM " + l + " " + warm); srv[0].broadcast("d " + l + " " + warm, null); }
      public void popup(int s, int p, boolean st, String t) { log.add("POP " + s + " " + p + " " + (st ? "s" : "t") + " " + t.replace("\n", "\\n")); }
      public void laser(float x, float y) { log.add("LASER " + Math.round(x) + " " + Math.round(y)); }
      public void laserHide() { log.add("LASER -"); }
      public void laserPing(float x, float y) { log.add("PING " + Math.round(x) + " " + Math.round(y)); }
      public void photo(int pos, int size, byte[] img) { photo = "I 1 " + Extras.PHOTO_POS[pos] + " " + Extras.PHOTO_SIZE[size];
        log.add("PHOTO " + Extras.PHOTO_POS[pos] + " " + Extras.PHOTO_SIZE[size] + " " + img.length + " " + String.format("%02X%02X", img[0], img[1])); srv[0].broadcast(photo, null); }
      public void photoMove(int pos, int size) { if (photo.equals("I 0")) return; photo = "I 1 " + Extras.PHOTO_POS[pos] + " " + Extras.PHOTO_SIZE[size]; log.add("PHOTOMOVE " + photo.substring(4)); srv[0].broadcast(photo, null); }
      public void photoHide() { photo = "I 0"; log.add("PHOTO -"); srv[0].broadcast(photo, null); }
    };
    srv[0] = new InkServer(8765, new InkServer.Handler() {
      public void onOpen(InkServer.Client c){ c.send("x\n"+ink.snapshot()+"v "+(shown[0]?1:0)+"\nd "+dim+" "+warm+"\n"+photo); }
      public void onText(InkServer.Client c, String t0){
        String t = Extras.route(t0, sink);
        if (t.isEmpty()) return;
        int f = ink.apply(c, t);
        if (!t.startsWith("t ") && (f & Ink.CHANGED)!=0) srv[0].broadcast(t, c);
        if ((f & Ink.WANT_HIDE)!=0) { shown[0]=false; srv[0].broadcast("v 0", null); System.out.println("HIDE"); }
        else if ((f & Ink.WANT_SHOW)!=0 && !shown[0]) { shown[0]=true; srv[0].broadcast("v 1", null); System.out.println("SHOW"); }
      }
      public void onClose(InkServer.Client c){ String e=ink.endOpenStrokes(c); if(!e.isEmpty()) srv[0].broadcast(e,c); System.out.println("CLOSE"); }
      public byte[] page(String p){
        if (p.equals("/_log")) { synchronized (log) { return String.join("\n", log).getBytes(StandardCharsets.UTF_8); } }
        if (p.equals("/_reset")) { log.clear(); return "ok".getBytes(); }
        try { String f = p.equals("/")?"index.html":p.substring(1); return Files.readAllBytes(Paths.get(assets, f)); } catch(Exception e){ return null; } }
    });
    final String code = a.length > 1 ? a[1] : "TEST23";
    if (!code.equals("-")) srv[0].setGate(() -> code);
    srv[0].start();
    System.out.println("READY");
    while (true) { Thread.sleep(1000); synchronized(ink){ System.out.println("STROKES " + ink.strokes.size() + " pts " + ink.strokes.stream().mapToInt(s->s.count).sum() + " open " + ink.strokes.stream().filter(s->!s.done).count()); } }
  }
}
