import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Acceptor {

    public void start() throws IOException {
        // @todo: Move params to config / Handle exception
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.socket().bind(new InetSocketAddress("0.0.0.0", 4444));

        Selector selector = Selector.open(); // @todo: Propagate exception
        ConcurrentLinkedDeque<SocketChannel> clientQueue = new ConcurrentLinkedDeque<>();

        // @todo: Find strategy how to extend worker count. For now only one worker supported.
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(new EventLoopWorker(selector, clientQueue));

        while (!Thread.currentThread().isInterrupted()) {
            SocketChannel clientSocketChannel = serverSocketChannel.accept(); // Blocking
            System.out.println("New client connected");

            clientSocketChannel.configureBlocking(false); // @todo: !! Handle exception

            // Note: All register calls must be from the same thread that is doing selecting or deadlocks will occur
            // @todo: !! Round robin -> wakeup
            clientQueue.offer(clientSocketChannel);

            // Wake up worker thread since it could sleep if all current clients keeps silent
            selector.wakeup();
        }
    }

}
