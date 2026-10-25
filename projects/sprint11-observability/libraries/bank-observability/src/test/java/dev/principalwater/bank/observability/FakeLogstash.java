package dev.principalwater.bank.observability;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ServerSocketFactory;

// TCP-приёмник вместо Logstash: каждая строка соединения — одно JSON-событие (json_lines).
final class FakeLogstash implements AutoCloseable {
    private final ServerSocket server;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    FakeLogstash(ServerSocketFactory sockets) throws IOException {
        server = sockets.createServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("fake-logstash-accept").start(this::accept);
    }

    int port() {
        return server.getLocalPort();
    }

    String next(Duration timeout) throws InterruptedException {
        return lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofPlatform().daemon().name("fake-logstash-read").start(() -> read(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void read(Socket socket) {
        try (socket;
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException handshakeOrClose) {
            // Отказ рукопожатия или закрытие клиента — ожидаемый ход сценариев с недоверием.
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
