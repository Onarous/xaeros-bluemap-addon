import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;

public class PrbmFullParserTest {
    static class CountingInputStream extends InputStream {
        InputStream in;
        long count = 0;
        CountingInputStream(InputStream in) { this.in = in; }
        public int read() throws IOException { int r = in.read(); if(r != -1) count++; return r; }
        public int read(byte[] b, int off, int len) throws IOException {
            int r = in.read(b, off, len); if(r != -1) count += r; return r;
        }
        public long getCount() { return count; }
    }

    public static void main(String[] args) throws Exception {
        CountingInputStream in = new CountingInputStream(new FileInputStream("rawtile.bin"));
        in.read(); in.read();
        int numValues = read3(in);
        read3(in);
        
        // position
        String name = readString(in);
        in.read();
        int pad = (int)(-in.getCount() & 3);
        for(int i=0; i<pad; i++) in.read();
        skipExact(in, numValues * 12L);
        
        // normal
        name = readString(in);
        System.out.println("2: " + name);
        in.read();
        pad = (int)(-in.getCount() & 3);
        for(int i=0; i<pad; i++) in.read();
        skipExact(in, numValues * 3L);
        
        // color
        name = readString(in);
        System.out.println("3: " + name);
        in.read();
        pad = (int)(-in.getCount() & 3);
        for(int i=0; i<pad; i++) in.read();
        
        int r = in.read() & 0xFF;
        int g = in.read() & 0xFF;
        int b = in.read() & 0xFF;
        System.out.println("Color 0: " + r + ", " + g + ", " + b);
        in.close();
    }
    
    static void skipExact(CountingInputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int toRead = (int)Math.min(n, buf.length);
            int r = in.read(buf, 0, toRead);
            if (r == -1) break;
            n -= r;
        }
    }
    
    static int read3(InputStream in) throws IOException {
        int a = in.read(); int b = in.read(); int c = in.read();
        return a | (b << 8) | (c << 16);
    }
    static String readString(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) > 0 && c != -1) sb.append((char)c);
        return sb.toString();
    }
}
