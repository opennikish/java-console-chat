import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Acceptor {

    private Logger logger = LoggerFactory.getLogger(Acceptor.class);

    public void start() throws IOException {
        // @todo: Move params to config / Handle exception
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.socket().bind(new InetSocketAddress("0.0.0.0", 4444));

        int threadNumber = Runtime.getRuntime().availableProcessors(); // @todo: Optimize formula for large CPU count
        logger.info("IO Worker thread count: {}", threadNumber);

        ExecutorService executorService = Executors.newFixedThreadPool(threadNumber);

        List<EventLoopWorker> workers = registerWorkers(threadNumber, executorService);

        int roundIndex = -1;

        while (!Thread.currentThread().isInterrupted()) {
            SocketChannel clientSocketChannel = serverSocketChannel.accept(); // Blocking
            logger.info("New client connected");
            clientSocketChannel.configureBlocking(false); // @todo: !! Handle exception

            roundIndex = this.nextIndex(roundIndex, workers.size());
            EventLoopWorker eventLoopWorker = workers.get(roundIndex);

            // Note: All register calls must be from the same thread that is doing select or deadlocks will occur
            eventLoopWorker.getNewClientsQueue().offer(clientSocketChannel);

            // @todo: atomic boolean check
            // Wake up worker thread since it could sleep if all current clients keeps silent
            eventLoopWorker.getSelector().wakeup();
        }
    }

    private List<EventLoopWorker> registerWorkers(int threadNumber, ExecutorService executorService) throws IOException {
        List<EventLoopWorker> workers = new ArrayList<>(threadNumber);

        for (int i = 0; i < threadNumber; i++) {
            // @todo: `Selector.open()` - Propagate exception
            EventLoopWorker worker = new EventLoopWorker(
                Selector.open(), new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>(), workers
            );
            workers.add(worker);
            executorService.execute(worker);
        }

        return workers;
    }

    private int nextIndex(int current, int total) {
        int next = current + 1;
        return next < total ? next : 0;
    }

}
