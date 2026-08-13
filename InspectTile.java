import java.io.*;
import java.net.*;
import java.util.zip.*;

public class InspectTile {
    public static void main(String[] args) throws Exception {
        URL url = new URL("http://mc.discotowns.xyz:23296/maps/world/tiles/0/x1/z1.prbm.gz");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestProperty("Accept-Encoding", "gzip");
        InputStream raw = con.getInputStream();
        PushbackInputStream pb = new PushbackInputStream(raw, 2);
        int b1 = pb.read();
        int b2 = pb.read();
        pb.unread(b2);
        pb.unread(b1);
        InputStream in = (b1 == 0x1F && b2 == 0x8B) ? new GZIPInputStream(pb) : pb;
        
        in.read(); in.read();
        int n1 = in.read() & 0xFF;
        int n2 = in.read() & 0xFF;
        int n3 = in.read() & 0xFF;
        int numValues = n1 | (n2 << 8) | (n3 << 16);
        in.read(); in.read(); in.read(); // indices
        
        long count = 8;
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) > 0 && c != -1) sb.append((char) c);
        String name = sb.toString();
        
        count += name.length() + 1;
        int type1 = in.read(); count++;
        int pad = (int) (-count & 3);
        count += pad;
        for (int i = 0; i < pad; i++) in.read();
        
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        byte[] fBuf = new byte[4];
        for(int i=0; i<numValues; i++) {
            int read = 0;
            while(read < 4) read += in.read(fBuf, read, 4 - read);
            int bits = (fBuf[0]&0xFF)|((fBuf[1]&0xFF)<<8)|((fBuf[2]&0xFF)<<16)|((fBuf[3]&0xFF)<<24);
            float x = Float.intBitsToFloat(bits);
            
            read = 0; while(read < 4) read += in.read(fBuf, read, 4 - read);
            
            read = 0; while(read < 4) read += in.read(fBuf, read, 4 - read);
            bits = (fBuf[0]&0xFF)|((fBuf[1]&0xFF)<<8)|((fBuf[2]&0xFF)<<16)|((fBuf[3]&0xFF)<<24);
            float z = Float.intBitsToFloat(bits);
            
            if(x < minX) minX = x;
            if(x > maxX) maxX = x;
            if(z < minZ) minZ = z;
            if(z > maxZ) maxZ = z;
        }
        System.out.println("Tile bounds: MinX=" + minX + " MaxX=" + maxX + " MinZ=" + minZ + " MaxZ=" + maxZ);
        
        System.out.println("Attr1: " + name + ", type: " + type1);
        
        // skip position data
        count += numValues * 12L;
        
        // Attr 2
        sb = new StringBuilder();
        while ((c = in.read()) > 0 && c != -1) sb.append((char) c);
        name = sb.toString();
        count += name.length() + 1;
        int type2 = in.read(); count++;
        pad = (int) (-count & 3);
        count += pad;
        for (int i = 0; i < pad; i++) in.read();
        
        System.out.println("Attr2: " + name + ", type: " + type2);
        
        // Attr 3
        int bytesPerNormal = 3;
        if(type2 == 33) bytesPerNormal = 12; // float?
        in.skip(numValues * bytesPerNormal);
        count += numValues * bytesPerNormal;
        
        sb = new StringBuilder();
        while ((c = in.read()) > 0 && c != -1) sb.append((char) c);
        name = sb.toString();
        count += name.length() + 1;
        int type3 = in.read(); count++;
        System.out.println("Attr3: " + name + ", type: " + type3);
        System.out.println("First 16 bytes of color data:");
        for(int i=0; i<16; i++) {
            System.out.printf("%02X ", in.read() & 0xFF);
        }
        System.out.println();
    }
}
