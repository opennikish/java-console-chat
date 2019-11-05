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
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();

        serverSocketChannel.socket().bind(new InetSocketAddress("0.0.0.0",4444));
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

            // Wake up processor thread since it could sleep if all current clients keeps silent
            selector.wakeup();
        }
    }

    static class Processor implements Runnable {

        private ConcurrentLinkedDeque<SocketChannel> clientQueue;

        private HashSet<SocketChannel> activeClients = new HashSet<>();

        private Selector selector;

        public Processor(Selector selector, ConcurrentLinkedDeque<SocketChannel> clientQueue) {
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
            ByteBuffer buffer = ByteBuffer.allocate(48);

            ByteArrayOutputStream clientMessage = new ByteArrayOutputStream();

            int byteCount = channel.read(buffer);
            System.out.println("byteCount: " + byteCount);

            if (byteCount == -1) {
                throw new IOException("Looks like the client has disconnected");
            }

            while (byteCount > 0) {
                buffer.flip();

                while (buffer.hasRemaining()){
                    clientMessage.write(buffer.get());
                }

                buffer.clear();
                byteCount = channel.read(buffer);
            }

            System.out.println("Got message: " + clientMessage.toString("UTF-8"));
            return clientMessage.toString("UTF-8");
        }

        private void writeMessage(SocketChannel channel, String message) throws IOException {
            ByteBuffer messageBytes = ByteBuffer.wrap(message.getBytes());

            channel.write(messageBytes);
        }
    }

}
