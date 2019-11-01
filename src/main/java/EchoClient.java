import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class EchoClient {
    public static void main(String[] args) throws IOException {
        // String host = args[0];
        // int port = Integer.parseInt(args[1]);
        String host = "localhost";
        int port = 4444;

        try(
            Socket socket = new Socket(host, port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in))
        ) {
            String userInput;

            // System.out.print("> ");
            while ((userInput = stdin.readLine()) != null) {
                out.println(userInput);
                String serverResponse = in.readLine(); // Blocking

                System.out.printf("Echo: %s%n", serverResponse);
                if (serverResponse.equals("Bye!")) {
                    break;
                }
                // System.out.print("\033[2J" + "> " + userInput);
            }
        }
    }
}
