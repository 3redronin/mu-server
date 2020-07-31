package scaffolding;

import java.util.function.Supplier;

public class AsyncUtils {

    public static void waitUtil(Supplier<Boolean> doneChecker, long timeoutInMillis) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        do {
            if (System.currentTimeMillis() - startTime >= timeoutInMillis)
                throw new RuntimeException(String.format("wait %s ms, timeout...", timeoutInMillis));
            Thread.sleep(10L);
        } while (!doneChecker.get());
    }
}
