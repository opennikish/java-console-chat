import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;


// @todo: Try not use ConcurrentLinkedQueue#size - it's heavy
public class EventLoopWorker implements Runnable {

    private Logger logger = LoggerFactory.getLogger(EventLoopWorker.class);

    private ConcurrentLinkedQueue<SocketChannel> newClientsQueue;
    private ConcurrentLinkedQueue<String> outsideIncomingMessages;
    private List<EventLoopWorker> neighborWorkers;

    private List<SocketChannel> activeClients = new ArrayList<>();

    private Selector selector;

    private ByteBuffer readBuffer = ByteBuffer.allocate(1024);
    private ByteArrayOutputStream readResult = new ByteArrayOutputStream();

    // For future optimization
    private AtomicBoolean isActive = new AtomicBoolean(false);

    public EventLoopWorker(
        Selector selector,
        ConcurrentLinkedQueue<SocketChannel> newClientsQueue,
        ConcurrentLinkedQueue<String> outsideIncomingMessages,
        List<EventLoopWorker> neighborWorkers
    ) {
        this.selector = selector;
        this.newClientsQueue = newClientsQueue;
        this.outsideIncomingMessages = outsideIncomingMessages;
        this.neighborWorkers = neighborWorkers;
    }

    public Selector getSelector() {
        return selector;
    }

    public AtomicBoolean isActive() {
        return isActive;
    }

    public ConcurrentLinkedQueue<SocketChannel> getNewClientsQueue() {
        return newClientsQueue;
    }

    public ConcurrentLinkedQueue<String> getOutsideIncomingMessages() {
        return outsideIncomingMessages;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            registerNewClients(); // @todo: think about call this method from Acceptor
            processOutsideIncomingMessages();

            // @todo: Think about to extract to separate method
            try {
                logger.info("Loop iteration");

                this.isActive.set(false);
                int readyChannelCount = this.selector.select(); // Blocking
                this.isActive.set(true);

                logger.info("Selected: {}", readyChannelCount);

                if (readyChannelCount > 0) {
                    this.processReadyChannels();
                }
            } catch (IOException ex) {
                logger.error("Could not select:", ex);
            }
        }
    }

    private void processOutsideIncomingMessages() {
        if (this.outsideIncomingMessages.size() > 0) {
            logger.info("Got the message(s) from other partition(s)");

            try {
                String message;
                while ((message = this.outsideIncomingMessages.poll()) != null) {
                    this.broadcastMessage(message);
                }
            } catch (IOException ex) {
                logger.error("Could not broadcast outside incoming message");
            }
        }
    }

    private void registerNewClients() {
        if (this.newClientsQueue.size() > 0) {
            SocketChannel clientSocketChannel;
            while ((clientSocketChannel = this.newClientsQueue.poll()) != null) {
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

            logger.info("selectionKey readyOps: {}", selectionKey.readyOps());

            if (selectionKey.isReadable()) {
                try {
                    String message = this.readMessage(clientSocketChannel);

                    // @todo: Think about batching instead of single send to n-clients
                    this.broadcastMessage(clientSocketChannel, message);

                    this.broadcastMessageToNeighborWorkers(message);
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

    private void broadcastMessageToNeighborWorkers(String message) {
        for (EventLoopWorker neighbor : this.neighborWorkers) {
            if (neighbor != this) {
                neighbor.getOutsideIncomingMessages().offer(message);
                if (!neighbor.isActive().get()) {
                    // Wake up worker thread since it could sleep if all current clients keeps silent
                    neighbor.getSelector().wakeup();
                }
            }
        }
    }

    private void broadcastMessage(SocketChannel sender, String message) throws IOException {
        for (SocketChannel client : this.activeClients) {
            if (client != sender) {
                // @todo: If one socket failed to sent - then the message will not be sent to the left sockets
                // Surround with try/catch for each `writeMessage` call instead of making global try/catch at caller
                this.writeMessage(client, message);
            }
        }
    }

    // @todo: Refactor, add `ignoreSender` or smth
    private void broadcastMessage(String message) throws IOException {
        this.broadcastMessage(null, message);
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

        // @todo: Think about to wrap it to business exceptin (it could be `ClientDisconnectedException extends IOException`)
        // Note: to verify if client is disconnected: IOException "Broken Pipe" exception on write, `-1` on read
        // @todo: see java.nio.channels.ClosedChannelException
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

        // @todo: Does need to check that client has been disconnected?

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
