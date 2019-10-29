import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EchoMultiServer {

    public static void main(String[] args) throws IOException {
        int port = 4444;
        ExecutorService executor = Executors.newFixedThreadPool(10);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                executor.execute(new Handler(clientSocket));
            }
        }
    }

    static class Handler implements Runnable {
        private Socket clientSocket;

        public Handler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try {
                this.handle();
            } catch (IOException ex) {
                System.err.printf("Something went wrong: %s%n", ex.getMessage());
            }
        }

        private void handle() throws IOException {
            try (
                PrintWriter out = new PrintWriter(this.clientSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(this.clientSocket.getInputStream()))
            ) {
                String input;
                while ((input = in.readLine()) != null) {
                    System.out.printf("Received message: %s%n", input);

                    if (input.equals("stop")) {
                        System.out.println("Closing connection..");
                        out.println("Bye!");
                        break;
                    }

                    out.println(input);
                }

                System.out.println("Finish");
            }
        }

    }

}
