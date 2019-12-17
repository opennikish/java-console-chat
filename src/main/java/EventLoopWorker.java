import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;


public class EventLoopWorker implements Runnable {

    private Logger logger = LoggerFactory.getLogger(EventLoopWorker.class);

    private ConcurrentLinkedDeque<SocketChannel> clientQueue;

    private HashSet<SocketChannel> activeClients = new HashSet<>();

    private Selector selector;

    private ByteBuffer readBuffer = ByteBuffer.allocate(1024);
    private ByteArrayOutputStream readResult = new ByteArrayOutputStream();

    public EventLoopWorker(Selector selector, ConcurrentLinkedDeque<SocketChannel> clientQueue) {
        this.selector = selector;
        this.clientQueue = clientQueue;
    }

    @Override
    public void run() {

        while (!Thread.currentThread().isInterrupted()) {
            registerNewClients();

            try {
                logger.info("Loop iteration");

                int readyChannelCount = this.selector.select(); // Blocking
                logger.info("Selected: {}", readyChannelCount);

                if (readyChannelCount > 0) {
                    this.processReadyChannels();
                }
            } catch (IOException ex) {
                logger.error("Could not select:", ex);
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
                } catch (ClosedChannelException ex) {
                    logger.error("Could not register socket to the selector:", ex);
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
            SocketChannel clientSocketChannel = (SocketChannel) selectionKey.channel();

            if (selectionKey.isReadable()) {
                try {
                    String message = this.readMessage(clientSocketChannel);

                    this.broadcastMessage(clientSocketChannel, message);
                } catch (IOException ex) {
                    // On read: Connection reset by peer
                    // On write: Broken Pipe
                    logger.error("Could not read message from the client", ex);
                    this.disconnectClient(clientSocketChannel);
                }
            }

            // `selector` doesn't remove the SelectionKey instances from the set itself.
            // It should be removed so that you do not get it again in the next calls of `selector.select()`
            selectionKeyIterator.remove();
        }
    }

    // @todo: Move to Broadcaster
    private void broadcastMessage(SocketChannel sender, String message) throws IOException {
        for (SocketChannel client : this.activeClients) {
            if (client != sender) {
                this.writeMessage(client, message);
            }
        }
    }

    private void disconnectClient(SocketChannel client) {
        logger.info("Disconnect client"); // @todo: Use some id (guid, ip, etc)
        try {
            client.close();

            // SelectionKey key = client.keyFor(this.selector);
            // key.cancel();
        } catch (IOException ex) {
            logger.error("Could not close the client channel", ex);
        }

        this.activeClients.remove(client);
    }


    private String readMessage(SocketChannel channel) throws IOException {
        int byteCount = channel.read(this.readBuffer);
        logger.info("byteCount: {}", byteCount);

        // Note: to verify if client is disconnected: IOException "Broken Pipe" exception on write, `-1` on read
        if (byteCount == -1) {
            throw new IOException("Looks like the client has disconnected");
        }

        while (byteCount > 0) {
            this.readBuffer.flip();

            while (this.readBuffer.hasRemaining()) {
                readResult.write(this.readBuffer.get());
            }

            this.readBuffer.clear();
            byteCount = channel.read(this.readBuffer);
        }

        String clientMessage = this.readResult.toString("UTF-8");
        logger.info("Got message: {}", clientMessage);

        this.readResult.reset();

        return clientMessage;
    }

    private void writeMessage(SocketChannel channel, String message) throws IOException {
        ByteBuffer messageBytes = ByteBuffer.wrap(message.getBytes());

        channel.write(messageBytes);
    }
}
