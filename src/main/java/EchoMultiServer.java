import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// @todo: Use separate class for the server
public class EchoMultiServer {

    public static void main(String[] args) throws IOException {
        // @todo: Move to config:
        int port = 4444;
        int threadSize = 2;

        ExecutorService executor = Executors.newFixedThreadPool(threadSize);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept(); // Blocking

                registerClient(executor, clientSocket);
            }
        }
    }

    private static void registerClient(ExecutorService executor, Socket clientSocket) {
        try {
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            executor.execute(new Handler(clientSocket, in, out, executor));
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static class Handler implements Runnable {
        private final Socket clientSocket;
        private final BufferedReader in;
        private final PrintWriter out;
        private final ExecutorService executor;

        public Handler(Socket clientSocket, BufferedReader in, PrintWriter out, ExecutorService executor) {
            this.clientSocket = clientSocket;
            this.in = in;
            this.out = out;
            this.executor = executor;
        }

        @Override
        public void run() {
            try {
                this.handle();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        private void handle() throws IOException {
            boolean isTerminated = false;

            if (this.in.ready()) {
                String input = in.readLine();
                System.out.printf("Received message: %s%n", input);

                if (input.equals("/quit")) {
                    isTerminated = true;
                    this.disconnectClient();
                } else {
                    out.println(input);
                }
            }

            if (!isTerminated) {
                this.executor.execute(this);
            }
        }

        private void disconnectClient() throws IOException {
            System.out.println("Closing connection..");
            out.println("Bye!");

            this.clientSocket.close();
            this.in.close();
            this.out.close();
        }
    }

}