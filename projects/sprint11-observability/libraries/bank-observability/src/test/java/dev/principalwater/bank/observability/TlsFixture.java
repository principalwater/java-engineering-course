package dev.principalwater.bank.observability;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

// Самоподписанная пара для сервера и PKCS12-хранилище доверия клиента, созданные keytool из JDK
// теста: сертификаты не хранятся в репозитории и живут только во временном каталоге.
final class TlsFixture {
    static final String PASSWORD = "fixture-password";
    private final Path directory;
    private final String name;

    TlsFixture(Path directory, String name) throws Exception {
        this(directory, name, "san=dns:localhost,ip:127.0.0.1");
    }

    TlsFixture(Path directory, String name, String subjectAlternativeName) throws Exception {
        this.directory = directory;
        this.name = name;
        keytool(
                "-genkeypair",
                "-alias",
                name,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=localhost",
                "-ext",
                subjectAlternativeName,
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                keyStore().toString(),
                "-storepass",
                PASSWORD);
        keytool(
                "-exportcert",
                "-alias",
                name,
                "-keystore",
                keyStore().toString(),
                "-storepass",
                PASSWORD,
                "-file",
                certificate().toString());
        keytool(
                "-importcert",
                "-noprompt",
                "-alias",
                name,
                "-file",
                certificate().toString(),
                "-storetype",
                "PKCS12",
                "-keystore",
                trustStore().toString(),
                "-storepass",
                PASSWORD);
    }

    Path keyStore() {
        return directory.resolve(name + "-key.p12");
    }

    Path trustStore() {
        return directory.resolve(name + "-trust.p12");
    }

    private Path certificate() {
        return directory.resolve(name + ".crt");
    }

    SSLServerSocketFactory serverSockets() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStore())) {
            keys.load(input, PASSWORD.toCharArray());
        }
        KeyManagerFactory factory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        return context.getServerSocketFactory();
    }

    private static void keytool(String... arguments) throws IOException, InterruptedException {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command =
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(executable.toString()),
                                java.util.stream.Stream.of(arguments))
                        .toList();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + new String(output));
        }
    }
}
