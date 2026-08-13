import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public class PrbmParserTest {
    public static void main(String[] args) throws Exception {
        InputStream in = new FileInputStream("rawtile.bin");
        int b1 = in.read();
        int b2 = in.read();
        if (b1 == 0x1F && b2 == 0x8B) {
            System.out.println("GZIPPED");
            in.close();
            in = new GZIPInputStream(new FileInputStream("rawtile.bin"));
        } else {
            System.out.println("NOT GZIPPED");
            in.close();
            in = new FileInputStream("rawtile.bin");
        }
        
        int version = in.read();
        int flags = in.read();
        int numValues = read3(in);
        int numIndices = read3(in);
        System.out.println("Version: " + version + ", NumValues: " + numValues);
        
        // read position
        String name = readString(in);
        System.out.println("Array: " + name);
        int attrType = in.read();
        System.out.println("AttrType: " + attrType);
        
        long count = 8 + name.length() + 1 + 1;
        int padding = (int) (-count & 3);
        System.out.println("Padding: " + padding);
        for(int i=0; i<padding; i++) in.read();
        
        // Read first 3 floats
        float x = readFloat(in);
        float y = readFloat(in);
        float z = readFloat(in);
        System.out.println("Pos 0: " + x + ", " + y + ", " + z);
    }
    
    static int read3(InputStream in) throws IOException {
        int a = in.read(); int b = in.read(); int c = in.read();
        return a | (b << 8) | (c << 16);
    }
    static float readFloat(InputStream in) throws IOException {
        int a = in.read(); int b = in.read(); int c = in.read(); int d = in.read();
        return Float.intBitsToFloat(a | (b << 8) | (c << 16) | (d << 24));
    }
    static String readString(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) > 0) sb.append((char)c);
        return sb.toString();
    }
}
