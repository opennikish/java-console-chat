import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {

    public static void main(String... args) throws IOException {
        System.out.println("Starting echo server..");

        int port = 4444;

        try (
            ServerSocket serverSocket = new ServerSocket(port);
            Socket clientSocket = serverSocket.accept(); // Blocking
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))
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