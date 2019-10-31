import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NioServer {

    public static void main(String[] args) throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();

        serverSocketChannel.socket().bind(new InetSocketAddress("localhost",4444));
        // serverSocketChannel.socket().bind(null); // Random free port

        Selector selector = Selector.open();

        ConcurrentLinkedDeque<SocketChannel> clientQueue = new ConcurrentLinkedDeque<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);

        executorService.execute(new Processor(selector, clientQueue));


        while (true) {
            SocketChannel clientSocketChannel = serverSocketChannel.accept();

            System.out.println("New client connected");

            clientSocketChannel.configureBlocking(false);

            // Note: All register calls must be from the same thread that is doing selecting or deadlocks will occur
            clientQueue.offer(clientSocketChannel);

            // Wake up processor thread since it could sleep if all current clients keeps silient
            selector.wakeup();

            System.out.println("End main-loop iteration\n");
        }
    }

    static class Processor implements Runnable {

        private ConcurrentLinkedDeque<SocketChannel> clientQueue;

        private Selector selector;

        public Processor(Selector selector, ConcurrentLinkedDeque<SocketChannel> clientQueue) {
            this.selector = selector;
            this.clientQueue = clientQueue;
        }

        @Override
        public void run() {

            while (true) {
                registerNewClients();

                try {
                    System.out.println("> Loop iteration");

                    int readyChannelsCount = selector.select(); // Blocking

                    System.out.println("> Selected: " + readyChannelsCount);

                    if (readyChannelsCount > 0) {
                        this.process();
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }

        private void registerNewClients() {
            if (this.clientQueue.size() > 0) {
                SocketChannel clientSocketChannel;
                while ((clientSocketChannel = this.clientQueue.poll()) != null) {

                    try {
                        clientSocketChannel.register(selector, SelectionKey.OP_READ);
                    } catch (ClosedChannelException e) {
                        e.printStackTrace();
                    }

                }
            }
        }

        private void process() {
            final Set<SelectionKey> selectionKeys = this.selector.selectedKeys();
            Iterator<SelectionKey> selectionKeyIterator = selectionKeys.iterator();

            while (selectionKeyIterator.hasNext()) {
                SelectionKey selectionKey = selectionKeyIterator.next();

                if (selectionKey.isReadable()) {
                    this.readMessage((SocketChannel)selectionKey.channel());
                    this.writeMessage((SocketChannel)selectionKey.channel());
                }

                selectionKeyIterator.remove();
            }
        }

        private void readMessage(SocketChannel channel) {
            ByteBuffer buffer = ByteBuffer.allocate(48);

            try {
                ByteArrayOutputStream clientMessage = new ByteArrayOutputStream();

                int byteCount = channel.read(buffer);
                System.out.println("byteCount: " + byteCount);

                while (byteCount > 0) {
                    buffer.flip();

                    while (buffer.hasRemaining()){
                        clientMessage.write(buffer.get());
                    }

                    buffer.clear();
                    byteCount = channel.read(buffer);
                }

                System.out.println("Got message: " + clientMessage.toString("UTF-8"));
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        private void writeMessage(SocketChannel channel) {
            ByteBuffer message = ByteBuffer.wrap("Hello\n".getBytes());
            try {
                channel.write(message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}