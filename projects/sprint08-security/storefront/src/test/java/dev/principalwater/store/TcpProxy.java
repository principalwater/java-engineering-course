package dev.principalwater.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Прокси перед настоящим сервером, который умеет отказывать так, как отказывает хранилище: принимает соединение
 * и молчит (BLACKHOLE), перестаёт пересылать байты открытого соединения (STALL) или обрывает все соединения и
 * отказывает в новых (CUT, как остановленный сервер).
 */
public final class TcpProxy implements AutoCloseable {
    public enum Mode { FORWARD, STALL, BLACKHOLE, CUT }

    private final ServerSocket server;
    private final String upstreamHost;
    private final int upstreamPort;
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.FORWARD;

    public TcpProxy(String upstreamHost, int upstreamPort) throws IOException {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        daemon("tcp-proxy-accept", this::accept);
    }

    public int port() {
        return server.getLocalPort();
    }

    public void mode(Mode next) {
        mode = next;
        if (next == Mode.CUT) {
            open.forEach(TcpProxy::quietly);
            open.clear();
        }
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                var client = server.accept();
                if (mode == Mode.CUT) {
                    quietly(client);
                    continue;
                }
                open.add(client);
                if (mode == Mode.BLACKHOLE) {
                    continue;
                }
                var upstream = new Socket(upstreamHost, upstreamPort);
                open.add(upstream);
                daemon("tcp-proxy-up", () -> pump(client, upstream));
                daemon("tcp-proxy-down", () -> pump(upstream, client));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void pump(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                while (mode != Mode.FORWARD && !server.isClosed()) {
                    Thread.sleep(10);
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException | InterruptedException ended) {
            // Один из концов закрыт: соединение завершено.
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        open.forEach(TcpProxy::quietly);
    }

    private static void quietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Закрытие заглушки не влияет на результат проверки.
        }
    }

    private static void daemon(String name, Runnable task) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
