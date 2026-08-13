import java.io.InputStream;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public class TestTile2 {
    public static void main(String[] args) throws Exception {
        checkTile(0, 0);
        checkTile(-1, -1);
        checkTile(1, 1);
    }
    
    static void checkTile(int tx, int tz) throws Exception {
        URL url = new URL("http://mc.discotowns.xyz:23296/maps/world/tiles/0/x" + tx + "/z" + tz + ".prbm");
        InputStream in = url.openStream();
        byte[] magic = new byte[2];
        in.read(magic);
        if (magic[0] == (byte)0x1F && magic[1] == (byte)0x8B) {
            in = new GZIPInputStream(url.openStream());
        } else {
            in.close();
            in = url.openStream();
        }
        
        in.read(); in.read(); // header
        int numValues = read3(in);
        read3(in); // indices
        
        String n = readString(in);
        in.read();
        int pad = (int) (-8 - n.length() - 2) & 3;
        for(int i=0; i<pad; i++) in.read();
        
        byte[] fBuf = new byte[4];
        float minX = 100000, maxX = -100000, minZ = 100000, maxZ = -100000;
        for (int i = 0; i < numValues; i++) {
            readExact(in, fBuf);
            int bits = (fBuf[0] & 0xFF) | ((fBuf[1] & 0xFF) << 8) | ((fBuf[2] & 0xFF) << 16) | ((fBuf[3] & 0xFF) << 24);
            float x = Float.intBitsToFloat(bits);
            
            readExact(in, fBuf); // y
            
            readExact(in, fBuf);
            bits = (fBuf[0] & 0xFF) | ((fBuf[1] & 0xFF) << 8) | ((fBuf[2] & 0xFF) << 16) | ((fBuf[3] & 0xFF) << 24);
            float z = Float.intBitsToFloat(bits);
            
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        System.out.println("Tile " + tx + "," + tz + " => x: " + minX + " to " + maxX + ", z: " + minZ + " to " + maxZ);
    }
    
    static int read3(InputStream in) throws Exception {
        return in.read() | (in.read() << 8) | (in.read() << 16);
    }
    static String readString(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) > 0) sb.append((char)c);
        return sb.toString();
    }
    static void readExact(InputStream in, byte[] buf) throws Exception {
        int r = 0; while (r < buf.length) r += in.read(buf, r, buf.length - r);
    }
}
