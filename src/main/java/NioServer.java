import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NioServer {

    // Note: to verify if client is disconnected: IOException "Broken Pipe" exception on write, `-1` on read (not sure)

    public static void main(String[] args) throws IOException {
        // @todo: Move params to config
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.socket().bind(new InetSocketAddress("0.0.0.0",4444));

        Selector selector = Selector.open();
        ConcurrentLinkedDeque<SocketChannel> clientQueue = new ConcurrentLinkedDeque<>();

        // @todo: Find strategy how to extend worker count. For now only one worker supported.
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(new Worker(selector, clientQueue));

        while (true) {
            SocketChannel clientSocketChannel = serverSocketChannel.accept(); // Blocking
            System.out.println("New client connected");

            clientSocketChannel.configureBlocking(false);

            // Note: All register calls must be from the same thread that is doing selecting or deadlocks will occur
            clientQueue.offer(clientSocketChannel);

            // Wake up worker thread since it could sleep if all current clients keeps silent
            selector.wakeup();
        }
    }

    static class Worker implements Runnable {

        private ConcurrentLinkedDeque<SocketChannel> clientQueue;

        private HashSet<SocketChannel> activeClients = new HashSet<>();

        private Selector selector;

        private ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        private ByteArrayOutputStream readResult = new ByteArrayOutputStream();

        public Worker(Selector selector, ConcurrentLinkedDeque<SocketChannel> clientQueue) {
            this.selector = selector;
            this.clientQueue = clientQueue;
        }

        @Override
        public void run() {

            while (!Thread.currentThread().isInterrupted()) {
                registerNewClients();

                try {
                    System.out.println("> Loop iteration");

                    int readyChannelCount = this.selector.select(); // Blocking
                    System.out.println("> Selected: " + readyChannelCount);

                    if (readyChannelCount > 0) {
                        this.processReadyChannels();
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
                        activeClients.add(clientSocketChannel);
                    } catch (ClosedChannelException e) {
                        e.printStackTrace();
                    }

                }
            }
        }

        private void processReadyChannels() {
            final Set<SelectionKey> selectionKeys = this.selector.selectedKeys();
            Iterator<SelectionKey> selectionKeyIterator = selectionKeys.iterator();

            while (selectionKeyIterator.hasNext()) {
                // Is the set of keys such that each key's channel was detected to be ready
                SelectionKey selectionKey = selectionKeyIterator.next();
                SocketChannel clientSocketChannel = (SocketChannel)selectionKey.channel();

                if (selectionKey.isReadable()) {
                    try {
                        String message = this.readMessage(clientSocketChannel);

                        this.broadcastMessage(clientSocketChannel, message);
                    } catch (IOException ex) {
                        // On read: Connection reset by peer
                        // On write: Broken Pipe
                        ex.printStackTrace();
                        this.disconnectClient(clientSocketChannel);
                    }
                }

                // `selector` doesn't remove the SelectionKey instances from the set itself.
                // It should be removed so that you do not get it again in the next calls of `selector.select()`
                selectionKeyIterator.remove();
            }
        }

        private void broadcastMessage(SocketChannel sender, String message) throws IOException {
            for (SocketChannel client : this.activeClients) {
                if (client != sender) {
                    this.writeMessage(client, message);
                }
            }
        }

        private void disconnectClient(SocketChannel client) {
            try {
                client.close();

                // SelectionKey key = client.keyFor(this.selector);
                // key.cancel();
            } catch (IOException e) {
                e.printStackTrace();
            }

            this.activeClients.remove(client);
        }


        private String readMessage(SocketChannel channel) throws IOException {
            int byteCount = channel.read(this.readBuffer);
            System.out.println("byteCount: " + byteCount);

            if (byteCount == -1) {
                throw new IOException("Looks like the client has disconnected");
            }

            while (byteCount > 0) {
                this.readBuffer.flip();

                while (this.readBuffer.hasRemaining()){
                    readResult.write(this.readBuffer.get());
                }

                this.readBuffer.clear();
                byteCount = channel.read(this.readBuffer);
            }

            String clientMessage = this.readResult.toString("UTF-8");
            this.readResult.reset();

            System.out.printf("Got message: %s", clientMessage);

            return clientMessage;
        }

        private void writeMessage(SocketChannel channel, String message) throws IOException {
            ByteBuffer messageBytes = ByteBuffer.wrap(message.getBytes());

            channel.write(messageBytes);
        }
    }

}
