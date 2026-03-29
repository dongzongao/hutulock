import com.hutulock.client.HutuLockClient;
import com.hutulock.client.LockContext;
import java.util.concurrent.TimeUnit;

/**
 * Simulates multiple concurrent lock holders and waiters for demo purposes.
 * Run with: java -cp hutulock-client/target/... simulate_locks.java
 */
public class simulate_locks {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting lock simulation...");

        // Client 1 - holds order-lock
        HutuLockClient c1 = HutuLockClient.builder().addNode("127.0.0.1", 8881).build();
        c1.connect();

        // Client 2 - waits for order-lock, holds payment-lock
        HutuLockClient c2 = HutuLockClient.builder().addNode("127.0.0.1", 8881).build();
        c2.connect();

        // Client 3 - waits for order-lock, holds inventory-lock
        HutuLockClient c3 = HutuLockClient.builder().addNode("127.0.0.1", 8881).build();
        c3.connect();

        // Client 4 - holds user-lock
        HutuLockClient c4 = HutuLockClient.builder().addNode("127.0.0.1", 8881).build();
        c4.connect();

        // c1 acquires order-lock (will be holder)
        System.out.println("c1 acquiring order-lock...");
        c1.lock("order-lock");
        System.out.println("c1 holds order-lock");

        // c2 tries order-lock (will wait), and holds payment-lock
        Thread t2 = new Thread(() -> {
            try {
                System.out.println("c2 waiting for order-lock...");
                c2.lock("order-lock");
                System.out.println("c2 acquired order-lock");
            } catch (Exception e) { e.printStackTrace(); }
        });
        t2.setDaemon(true);
        t2.start();

        Thread.sleep(300);

        // c3 tries order-lock (will wait)
        Thread t3 = new Thread(() -> {
            try {
                System.out.println("c3 waiting for order-lock...");
                c3.lock("order-lock");
                System.out.println("c3 acquired order-lock");
            } catch (Exception e) { e.printStackTrace(); }
        });
        t3.setDaemon(true);
        t3.start();

        Thread.sleep(300);

        // c2 also holds payment-lock
        c2.lock("payment-lock");
        System.out.println("c2 holds payment-lock");

        // c4 holds inventory-lock
        c4.lock("inventory-lock");
        System.out.println("c4 holds inventory-lock");

        // c3 also waits for payment-lock
        Thread t3b = new Thread(() -> {
            try {
                System.out.println("c3 waiting for payment-lock...");
                c3.lock("payment-lock");
                System.out.println("c3 acquired payment-lock");
            } catch (Exception e) { e.printStackTrace(); }
        });
        t3b.setDaemon(true);
        t3b.start();

        System.out.println("\n=== Locks are now active. Open http://localhost:5173 to see them. ===");
        System.out.println("Press Ctrl+C to stop simulation.\n");

        // Keep running so locks stay active
        Thread.currentThread().join();
    }
}
