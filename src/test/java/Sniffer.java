import java.io.*;
import java.net.Socket;
import java.util.Collection;

import static java.util.Arrays.asList;

public class Sniffer {

    public static void main(String[] args) throws IOException {
        String host = "localhost";
        int port = 18080;
        Collection<String> requestLines = asList("HEAD /sample.css HTTP/1.1", "Accept-Encoding: blah");

        Socket socket = new Socket(host, port);

        try (BufferedWriter out = new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream(), "UTF8"));
             InputStream responseStream = socket.getInputStream()) {
            sendMessage(out, requestLines);
            readResponse(responseStream, System.out);
        }

    }

    private static void sendMessage(BufferedWriter out, Collection<String> requestLines) throws IOException {
        System.out.println(" * Request");

        for (String line : requestLines) {
            System.out.println(line);
            out.write(line + "\r\n");
        }

        out.write("\r\n");
        out.flush();
    }

    private static void readResponse(InputStream in, PrintStream out) throws IOException {
        System.out.println("\n * Response");

        byte[] buffer = new byte[4096];
        int read;
        while ( (read = in.read(buffer)) > -1 ) {
            for (int i = 0; i < read; i++) {
                byte b = buffer[i];
                if (b == 10) {
                    out.println("\\n");
                } else if (b == 13) {
                    out.print("\\r");
                } else if (b < 32) {
                    out.print("[" + ((int)b) + "]");
                } else {
                    out.print((char)b);
                }
            }
        }

        out.println("---EOF---");

    }


}
