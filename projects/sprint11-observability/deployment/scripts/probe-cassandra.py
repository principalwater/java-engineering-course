"""Check CQL v4 OPTIONS/SUPPORTED without credentials or another JVM."""

import socket


def probe() -> None:
    with socket.create_connection(("127.0.0.1", 9042), timeout=2) as connection:
        # Version 4, no flags, stream 0, OPTIONS opcode 0x05, empty body.
        connection.sendall(bytes.fromhex("04 00 00 00 05 00 00 00 00"))
        with connection.makefile("rb") as stream:
            header = stream.read(9)
            if len(header) != 9 or header[:5] != bytes.fromhex("84 00 00 00 06"):
                raise RuntimeError("CQL endpoint did not return SUPPORTED")
            size = int.from_bytes(header[5:], "big")
            if not 0 < size <= 64 * 1024:
                raise RuntimeError("CQL capability response exceeded its budget")
            body = stream.read(size)
            if len(body) != size or b"CQL_VERSION" not in body:
                raise RuntimeError("CQL capabilities are incomplete")


if __name__ == "__main__":
    probe()
