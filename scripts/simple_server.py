

import sys
import socket



def connect_to_server(remote_addr, local_addr=None):
    pass


def start_server(local_addr):
    s = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
    s.bind(local_addr)
    s.listen(1)
    return s

sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)






if sys.argv[1] == 'test1':
    fasdf
elif sys.argv[1] == 'test2':
    adsfa


local_addr = ('::1', 1337)
sock = start_server(local_addr)
print('Listening on:', local_addr)
conn, addr = sock.accept()

print('Connection address:', addr)
while True:
    data = conn.recv(4096)
    if data: break

    print("received data:", data)
    conn.send(data)

conn.close()
