import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TestClient {
    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        
        ExecutorService pool = Executors.newFixedThreadPool(4);
        int total = 529;
        AtomicInteger done = new AtomicInteger(0);
        long start = System.currentTimeMillis();
        
        CompletableFuture[] futures = new CompletableFuture[total];
        for (int i = 0; i < total; i++) {
            final int tx = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                String[] exts = {".prbm", ".json", ".prbm.gz", "_old.json"};
                for (String ext : exts) {
                    try {
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create("http://mc.discotowns.xyz:23296/maps/world/tiles/0/x" + tx + "/z999" + ext))
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(3))
                                .build();
                        HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                        if (resp.statusCode() == 200) break;
                    } catch (Exception e) {}
                }
                done.incrementAndGet();
            }, pool);
        }
        
        CompletableFuture.allOf(futures).join();
        pool.shutdown();
        System.out.println("Done " + done.get() + " in " + (System.currentTimeMillis() - start) + "ms");
    }
}
