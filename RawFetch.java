import java.net.URL;
import java.io.InputStream;
import java.io.FileOutputStream;

public class RawFetch {
    public static void main(String[] args) throws Exception {
        URL url = new URL("http://mc.discotowns.xyz:23296/maps/world/tiles/0/x0/z0.prbm");
        java.net.HttpURLConnection con = (java.net.HttpURLConnection) url.openConnection();
        con.setRequestProperty("Accept-Encoding", "identity"); // Prevent auto-decompression
        System.out.println("Content-Encoding: " + con.getHeaderField("Content-Encoding"));
        InputStream in = con.getInputStream();
        FileOutputStream out = new FileOutputStream("rawtile.bin");
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.close();
        in.close();
    }
}
