import io.github.tvoverlaysuite.AdbProbe;

/** Runs Adb.java against test/fake_adbd.py: first connect shows the key (prompt), second is already trusted.
 *  Usage: java -cp build/test AdbTest PORT PEM_OUT  (writes the private key as PEM for the cross-check). */
public class AdbTest {
    public static void main(String[] a) throws Exception {
        System.exit(AdbProbe.run(Integer.parseInt(a[0]), a[1]) ? 0 : 1);
    }
}
