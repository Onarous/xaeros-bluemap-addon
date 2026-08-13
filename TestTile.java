import java.io.InputStream;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public class TestTile {
    public static void main(String[] args) throws Exception {
        URL url = new URL("http://mc.discotowns.xyz:23296/maps/world/tiles/0/x-1/z-1.prbm");
        InputStream in = url.openStream();
        // check gzip
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
        System.out.println("numValues = " + numValues);
        
        String n = readString(in);
        System.out.println("Part 1: " + n);
        in.read();
        int pad = (int) (-8 - n.length() - 2) & 3;
        for(int i=0; i<pad; i++) in.read();
        
        float[] positions = new float[15];
        byte[] fBuf = new byte[4];
        for (int i = 0; i < Math.min(numValues*3, 15); i++) {
            readExact(in, fBuf);
            int bits = (fBuf[0] & 0xFF) | ((fBuf[1] & 0xFF) << 8) | ((fBuf[2] & 0xFF) << 16) | ((fBuf[3] & 0xFF) << 24);
            positions[i] = Float.intBitsToFloat(bits);
        }
        System.out.println("First pos: " + positions[0] + ", " + positions[1] + ", " + positions[2]);
        System.out.println("Second pos: " + positions[3] + ", " + positions[4] + ", " + positions[5]);
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
