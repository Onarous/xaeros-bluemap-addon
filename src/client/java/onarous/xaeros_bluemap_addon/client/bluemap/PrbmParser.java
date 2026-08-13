package onarous.xaeros_bluemap_addon.client.bluemap;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class PrbmParser {

    public static class ChunkData {
        public final BlockState[] blocks = new BlockState[256];
        public final int[] heights = new int[256];
    }

    private static final Map<BlockState, int[]> PALETTE = new LinkedHashMap<>();

    static {
        add(Blocks.GRASS_BLOCK, 90, 150, 65);
        add(Blocks.WATER, 60, 100, 200);
        add(Blocks.STONE, 125, 125, 125);
        add(Blocks.DIRT, 134, 96, 67);
        add(Blocks.SAND, 219, 211, 160);
        add(Blocks.OAK_LEAVES, 50, 120, 40);
        add(Blocks.SNOW_BLOCK, 240, 255, 255);
        add(Blocks.OAK_LOG, 100, 70, 40);
        add(Blocks.DEEPSLATE, 70, 70, 70);
        add(Blocks.GRAVEL, 150, 150, 150);
        add(Blocks.LAVA, 255, 100, 0);
        add(Blocks.TERRACOTTA, 150, 90, 60);
        add(Blocks.SPRUCE_LEAVES, 40, 80, 40);
        add(Blocks.BIRCH_LEAVES, 100, 150, 80);
    }

    private static void add(Block block, int r, int g, int b) {
        PALETTE.put(block.defaultBlockState(), new int[]{r, g, b});
    }

    public static BlockState findClosest(int r, int g, int b) {
        BlockState best = Blocks.STONE.defaultBlockState();
        int minDist = Integer.MAX_VALUE;
        for (Map.Entry<BlockState, int[]> entry : PALETTE.entrySet()) {
            int[] c = entry.getValue();
            int dr = c[0] - r;
            int dg = c[1] - g;
            int db = c[2] - b;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < minDist) {
                minDist = dist;
                best = entry.getKey();
            }
        }
        return best;
    }

    public static Map<ChunkPos, ChunkData> parse(InputStream rawIn, int tx, int tz, int tileSize) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(rawIn, 2);
        int b1 = pb.read();
        int b2 = pb.read();
        pb.unread(b2);
        pb.unread(b1);
        InputStream in;
        if (b1 == 0x1F && b2 == 0x8B) {
            in = new GZIPInputStream(pb);
        } else {
            in = pb;
        }

        in.read(); // version
        in.read(); // header bits
        int numValues = read3(in);
        read3(in); // indices
        
        long count = 8;
        
        // 1. position
        String name = readString(in);
        count += name.length() + 1;
        in.read(); count++;
        int pad = (int) (-count & 3);
        count += pad;
        for (int i = 0; i < pad; i++) in.read();
        
        float[] positions = new float[numValues * 3];
        byte[] fBuf = new byte[4];
        for (int i = 0; i < numValues * 3; i++) {
            readExact(in, fBuf);
            int bits = (fBuf[0] & 0xFF) | ((fBuf[1] & 0xFF) << 8) | ((fBuf[2] & 0xFF) << 16) | ((fBuf[3] & 0xFF) << 24);
            positions[i] = Float.intBitsToFloat(bits);
        }
        count += numValues * 12L;
        
        // 2. normal
        name = readString(in);
        count += name.length() + 1;
        in.read(); count++;
        pad = (int) (-count & 3);
        count += pad;
        for (int i = 0; i < pad; i++) in.read();
        
        skipExact(in, numValues * 3L);
        count += numValues * 3L;
        
        // 3. color
        name = readString(in);
        count += name.length() + 1;
        in.read(); count++;
        pad = (int) (-count & 3);
        count += pad;
        for (int i = 0; i < pad; i++) in.read();
        
        Map<ChunkPos, ChunkData> chunkMap = new HashMap<>();
        
        for (int i = 0; i < numValues; i++) {
            float x = positions[i * 3];
            float y = positions[i * 3 + 1];
            float z = positions[i * 3 + 2];
            
            int r = in.read() & 0xFF;
            int g = in.read() & 0xFF;
            int b = in.read() & 0xFF;
            int a = in.read() & 0xFF;
            
            int bx = (int) Math.floor(x) + tx * tileSize;
            int by = (int) Math.floor(y);
            int bz = (int) Math.floor(z) + tz * tileSize;
            
            ChunkPos pos = new ChunkPos(bx >> 4, bz >> 4);
            ChunkData data = chunkMap.computeIfAbsent(pos, k -> {
                ChunkData d = new ChunkData();
                for (int j = 0; j < 256; j++) d.heights[j] = Integer.MIN_VALUE;
                return d;
            });
            
            int lx = bx & 15;
            int lz = bz & 15;
            int idx = lx + lz * 16;
            
            if (by > data.heights[idx]) {
                data.heights[idx] = by;
                data.blocks[idx] = findClosest(r, g, b);
            }
        }
        
        return chunkMap;
    }

    private static void readExact(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int r = in.read(buf, read, buf.length - read);
            if (r == -1) throw new IOException("EOF");
            read += r;
        }
    }

    private static void skipExact(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int toRead = (int) Math.min(n, buf.length);
            int r = in.read(buf, 0, toRead);
            if (r == -1) throw new IOException("EOF");
            n -= r;
        }
    }

    private static int read3(InputStream in) throws IOException {
        int a = in.read();
        int b = in.read();
        int c = in.read();
        if (a == -1 || b == -1 || c == -1) throw new IOException("EOF");
        return a | (b << 8) | (c << 16);
    }

    private static String readString(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) > 0) sb.append((char) c);
        return sb.toString();
    }
}
