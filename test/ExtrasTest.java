import io.github.tvoverlaysuite.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JVM checks for the v1.2 protocol parser. Usage: java -cp build/test:test ExtrasTest */
public class ExtrasTest {
  static int fails = 0;
  static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }
  static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

  public static void main(String[] a) {
    final List<String> got = new ArrayList<>();
    Extras.Sink sink = new Extras.Sink() {
      public void dim(int l, int w) { got.add("d" + l + "/" + w); }
      public void popup(int s, int p, boolean st, String t) { got.add("p" + s + "/" + p + "/" + st + "/" + t); }
      public void laser(float x, float y) { got.add("l" + x + "," + y); }
      public void laserHide() { got.add("l-"); }
      public void laserPing(float x, float y) { got.add("l*" + x + "," + y); }
      public void photo(int pos, int size, byte[] img) { got.add("i" + pos + "/" + size + "/" + img.length); }
      public void photoMove(int pos, int size) { got.add("i~" + pos + "/" + size); }
      public void photoHide() { got.add("i-"); }
    };
    String rest = Extras.route("d 50 20", sink);
    check(rest.isEmpty() && got.equals(Arrays.asList("d50/20")), "dimmer with warmth");
    got.clear(); Extras.route("d 140", sink);
    check(got.equals(Arrays.asList("d90/-1")), "dimmer capped at 90, warmth optional");
    got.clear(); Extras.route("d -5 300", sink);
    check(got.equals(Arrays.asList("d0/100")), "dimmer clamped low, warmth clamped high");

    got.clear(); rest = Extras.route("s a.1 e0242f 10 5 5\nl 100 200\nm a.1 6 6\ne a.1", sink);
    check(rest.equals("s a.1 e0242f 10 5 5\nm a.1 6 6\ne a.1") && got.equals(Arrays.asList("l100.0,200.0")), "mixed message: ink lines kept, laser routed");
    String plain = "s a.2 E 70 1 1";
    check(Extras.route(plain, sink) == plain, "pure ink message passes through untouched");

    got.clear(); Extras.route("l -\nl * 3000 -9\nl 960.5 540", sink);
    check(got.equals(Arrays.asList("l-", "l*1920.0,0.0", "l960.5,540.0")), "laser hide / ping clamp / move");

    got.clear(); Extras.route("p x.1 5 top t " + b64("Goal!! ⚽ ¿Viste eso?"), sink);
    check(got.equals(Arrays.asList("p5/0/false/Goal!! ⚽ ¿Viste eso?")), "text pop-up, UTF-8 + base64url");
    got.clear(); Extras.route("p x.2 99 bottom s " + b64("👍🏽"), sink);
    check(got.equals(Arrays.asList("p15/2/true/👍🏽")), "sticker pop-up, duration capped at 15");
    got.clear(); Extras.route("p x.3 1 weird t " + b64("a\n\n\nb\nc\nd‮evil\u0007"), sink);
    check(got.equals(Arrays.asList("p3/1/false/a\nb\ncdevil")), "max 3 lines, blank lines, control + bidi chars dropped");
    StringBuilder longText = new StringBuilder(); for (int i = 0; i < 300; i++) longText.append('x');
    got.clear(); Extras.route("p x.4 5 center t " + b64(longText.toString()), sink);
    check(got.size() == 1 && got.get(0).length() == "p5/1/false/".length() + 160, "text cut at 160 characters");
    got.clear(); Extras.route("p x.5 5 center s " + b64("this is not a sticker at all"), sink);
    check(got.isEmpty(), "over-long sticker rejected");
    got.clear(); Extras.route("p x.6 5 center t !!!notbase64***\nd 10", sink);
    check(got.equals(Arrays.asList("d10/-1")), "malformed line skipped, next line still applied");
    got.clear(); Extras.route("p x.7 5 center t " + b64("   "), sink);
    check(got.isEmpty(), "blank message ignored");

    byte[] jpg = new byte[100]; jpg[0] = (byte) 0xFF; jpg[1] = (byte) 0xD8; jpg[2] = (byte) 0xFF;
    got.clear(); Extras.route("i a.1 tl l " + Base64.getEncoder().encodeToString(jpg), sink);
    check(got.equals(Arrays.asList("i0/2/100")), "photo: JPEG accepted, top-left large");
    got.clear(); Extras.route("i a.2 nowhere zz " + Base64.getEncoder().encodeToString(jpg), sink);
    check(got.equals(Arrays.asList("i3/1/100")), "photo: unknown position/size fall back to bottom-right medium");
    got.clear(); Extras.route("i a.3 br m " + Base64.getEncoder().encodeToString("<html>not an image</html>".getBytes()), sink);
    check(got.isEmpty(), "photo: non-image bytes rejected");
    got.clear(); Extras.route("i ~ c s\ni -", sink);
    check(got.equals(Arrays.asList("i~4/0", "i-")), "photo: move/resize and remove");

    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
