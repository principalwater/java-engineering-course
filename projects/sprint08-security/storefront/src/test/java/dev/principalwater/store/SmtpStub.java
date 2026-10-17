package dev.principalwater.store;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Минимальный SMTP-сервер: принимает письма по настоящему протоколу, чтобы JavaMail и его таймауты работали как в бою. */
public final class SmtpStub {
    public static final List<Mail> MAILS = new CopyOnWriteArrayList<>();
    public static final AtomicBoolean REJECT = new AtomicBoolean();
    public static final int PORT = start();

    private SmtpStub() { }

    public static void reset() {
        MAILS.clear();
        REJECT.set(false);
    }

    private static int start() {
        try {
            var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            var acceptor = new Thread(() -> accept(server), "smtp-stub");
            acceptor.setDaemon(true);
            acceptor.start();
            return server.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("SMTP stub could not start", failure);
        }
    }

    private static void accept(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                var socket = server.accept();
                var worker = new Thread(() -> serve(socket), "smtp-stub-session");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private static void serve(Socket socket) {
        // ISO-8859-1 сохраняет байты письма без потерь: разбор MIME выполняет сам JavaMail.
        try (socket; var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
             var out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1))) {
            reply(out, REJECT.get() ? "421 stub is not accepting mail" : "220 stub ESMTP");
            var recipients = new ArrayList<String>();
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("RCPT TO:")) {
                    recipients.add(line.substring("RCPT TO:".length()).trim());
                    reply(out, "250 OK");
                } else if (command.equals("DATA")) {
                    reply(out, "354 end with <CRLF>.<CRLF>");
                    var data = new StringBuilder();
                    for (String row = in.readLine(); row != null && !row.equals("."); row = in.readLine()) {
                        data.append(row.startsWith("..") ? row.substring(1) : row).append("\r\n");
                    }
                    MAILS.add(new Mail(List.copyOf(recipients), data.toString().getBytes(StandardCharsets.ISO_8859_1)));
                    reply(out, "250 queued");
                } else if (command.equals("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else {
                    reply(out, "250 OK");
                }
            }
        } catch (IOException disconnected) {
            // Клиент закрыл соединение без QUIT: для заглушки это не ошибка.
        }
    }

    private static void reply(BufferedWriter out, String line) throws IOException {
        out.write(line + "\r\n");
        out.flush();
    }

    public record Mail(List<String> recipients, byte[] raw) {
        public MimeMessage message() throws MessagingException {
            return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
        }

        public String body() throws MessagingException, IOException {
            return (String) message().getContent();
        }
    }
}
