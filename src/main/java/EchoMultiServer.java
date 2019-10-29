import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EchoMultiServer {

    public static void main(String[] args) throws IOException {
        int port = 4444;
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();

                // @todo: Add try / catch
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                executor.execute(new Handler(in, out, executor));
            }
        }
    }

    static class Handler implements Runnable {
        private final BufferedReader in;
        private final PrintWriter out;
        private ExecutorService executor;

        public Handler(BufferedReader in, PrintWriter out, ExecutorService executor) {
            this.in = in;
            this.out = out;
            this.executor = executor;
        }

        @Override
        public void run() {
            try {
                handle();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void handle() throws IOException {
            boolean isTerminated = false;

            if (this.in.ready()) {
                String input = in.readLine();
                System.out.printf("Received message: %s%n", input);

                if (input.equals("/quit")) {
                    System.out.println("Closing connection..");
                    out.println("Bye!");
                    isTerminated = true;
                //    @todo: Close resources (in, out, clientSocket)
                } else {
                    out.println(input);
                }
            }

            if (!isTerminated) {
                // this.executor.execute(new Handler2(in, out, executor));
                this.executor.execute(this);
            }
        }
    }

}
