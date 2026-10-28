"""
Talks just enough of the Postgres protocol by hand to print the real bytes of a startup.
Not a client; a learning tool. Used to make the hex dumps in docs/protocol-notes.md.

    python docs/tools/wire_peek.py localhost 6432 bob shop
"""
import socket
import struct
import sys

host, port, user, db = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]


def dump(label, data):
    print(f"--- {label} ({len(data)} bytes)")
    for i in range(0, len(data), 16):
        chunk = data[i:i + 16]
        hexes = " ".join(f"{b:02x}" for b in chunk)
        text = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        print(f"  {i:04x}  {hexes:<48}  {text}")


s = socket.create_connection((host, port))

ssl_request = struct.pack("!II", 8, 80877103)
dump("client -> SSLRequest", ssl_request)
s.sendall(ssl_request)
dump("server -> answer", s.recv(1))

params = b"user\0" + user.encode() + b"\0database\0" + db.encode() + b"\0application_name\0wire_peek\0\0"
startup = struct.pack("!II", 8 + len(params), 196608) + params
dump("client -> StartupMessage", startup)
s.sendall(startup)

reply = s.recv(4096)
dump("server -> first reply", reply)
kind = chr(reply[0])
length = struct.unpack("!I", reply[1:5])[0]
code = struct.unpack("!I", reply[5:9])[0]
print(f"type={kind!r} length={length} auth code={code} ({ {10: 'SASL', 5: 'MD5', 0: 'OK', 3: 'cleartext'}.get(code, '?') })")
if code == 10:
    print("mechanisms:", [m.decode() for m in reply[9:5 + length - 1].split(b"\0") if m])
s.close()
