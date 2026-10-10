package koboolean.staticrouter.outbound;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal RFC 6455 server used only to exercise
 * infra-node's {@code WebSocketOutboundNodeConnection} against a real socket, without
 * pulling in a servlet container or a third-party WebSocket library.
 *
 * Accepts the handshake, records the request headers, and decodes/queues
 * client text frames. It does not send any application-level message back -
 * the client under test reaches CONNECTED purely from a successful
 * handshake, matching the outbound protocol contract.
 */
final class FakeConsoleServer implements AutoCloseable {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final BlockingQueue<String> receivedTextFrames = new LinkedBlockingQueue<>();
    private final BlockingQueue<Map<String, String>> handshakeHeaders = new LinkedBlockingQueue<>();
    private final AtomicInteger closeFrames = new AtomicInteger();
    private volatile CountDownLatch handshakeGate = new CountDownLatch(0);
    private volatile Socket currentSocket;

    FakeConsoleServer() throws IOException {
        this.serverSocket = new ServerSocket(0);
        executor.submit(this::acceptLoop);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                currentSocket = socket;
                executor.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
            handshakeHeaders.offer(headers);

            // Lets a test hold the handshake "in flight" (request received, 101 not yet sent).
            if (!handshakeGate.await(10, TimeUnit.SECONDS)) {
                return;
            }

            String accept = computeAccept(headers.get("Sec-WebSocket-Key"));
            OutputStream out = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            String text;
            while ((text = readTextFrame(in)) != null) {
                receivedTextFrames.offer(text);
            }
        } catch (Exception e) {
            // Connection ended (client closed / dropConnection was called); nothing to do.
        }
    }

    private String computeAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String readTextFrame(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 == -1) {
            return null;
        }
        int b2 = in.read();
        if (b2 == -1) {
            return null;
        }

        boolean masked = (b2 & 0x80) != 0;
        long length = b2 & 0x7F;
        if (length == 126) {
            length = ((long) (in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | (in.read() & 0xFF);
            }
        }

        byte[] mask = new byte[4];
        if (masked) {
            readFully(in, mask);
        }

        byte[] payload = new byte[(int) length];
        readFully(in, payload);

        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }

        int opcode = b1 & 0x0F;
        if (opcode == 0x8) {
            closeFrames.incrementAndGet();
            return null; // close frame
        }

        return new String(payload, StandardCharsets.UTF_8);
    }

    private void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                throw new EOFException();
            }
            offset += read;
        }
    }

    String awaitTextFrame(long timeoutSeconds) throws InterruptedException {
        return receivedTextFrames.poll(timeoutSeconds, TimeUnit.SECONDS);
    }

    Map<String, String> awaitHandshakeHeaders(long timeoutSeconds) throws InterruptedException {
        return handshakeHeaders.poll(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Holds every subsequent handshake after its request headers are recorded, until
     * {@link #releaseHandshakes()} is called.
     */
    void holdHandshakes() {
        handshakeGate = new CountDownLatch(1);
    }

    void releaseHandshakes() {
        handshakeGate.countDown();
    }

    int closeFramesReceived() {
        return closeFrames.get();
    }

    void dropCurrentConnection() throws IOException {
        Socket socket = currentSocket;
        if (socket != null) {
            socket.close();
        }
    }

    /**
     * Sends a single unmasked text frame to the currently connected client, as a real Console
     * would (RFC 6455 requires server-to-client frames to be unmasked, unlike client frames).
     */
    void sendTextFrame(String text) throws IOException {
        Socket socket = currentSocket;
        if (socket == null) {
            throw new IOException("No active connection to send to");
        }

        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        synchronized (out) {
            out.write(0x81); // FIN + text frame opcode
            int length = payload.length;
            if (length <= 125) {
                out.write(length);
            } else if (length <= 0xFFFF) {
                out.write(126);
                out.write((length >> 8) & 0xFF);
                out.write(length & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) ((length >> (8 * i)) & 0xFF));
                }
            }
            out.write(payload);
            out.flush();
        }
    }

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        serverSocket.close();
    }
}
