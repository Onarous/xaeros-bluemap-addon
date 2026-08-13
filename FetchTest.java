import java.net.URL;
import java.net.HttpURLConnection;
import java.io.InputStream;
import java.io.FileOutputStream;

public class FetchTest {
    public static void main(String[] args) throws Exception {
        String url = "http://mc.discotowns.xyz:23296/maps/world/tiles/0/x0/z0.prbm";
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        System.out.println("Status: " + con.getResponseCode());
        if (con.getResponseCode() == 200) {
            InputStream in = con.getInputStream();
            FileOutputStream out = new FileOutputStream("tile.bin");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            System.out.println("Saved tile.bin");
        }
    }
}
