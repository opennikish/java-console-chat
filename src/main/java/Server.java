import java.io.IOException;

public class Server {

    public static void main(String... args) throws IOException {
        // @todo: Pass config to constructor
        Acceptor acceptor = new Acceptor();
        acceptor.start();
    }

}