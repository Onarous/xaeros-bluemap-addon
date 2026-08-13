import java.net.HttpURLConnection;
import java.net.URL;

public class TestClient2 {
    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        URL url = new URL("http://mc.discotowns.xyz:23296/maps/world/tiles/0/x10/z10.prbm");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("HEAD");
        con.setConnectTimeout(3000);
        con.setReadTimeout(3000);
        int code = con.getResponseCode();
        System.out.println("Code: " + code + " in " + (System.currentTimeMillis() - start) + "ms");
    }
}
