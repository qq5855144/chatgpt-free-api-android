import socket, sys

def cdp_http(sockname, path):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(5)
    s.connect('\0' + sockname)
    req = 'GET %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' % path
    s.sendall(req.encode())
    data = b''
    try:
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            data += chunk
    except socket.timeout:
        pass
    s.close()
    return data

if __name__ == '__main__':
    sock = sys.argv[1] if len(sys.argv) > 1 else 'webview_devtools_remote_4630'
    path = sys.argv[2] if len(sys.argv) > 2 else '/json/list'
    out = cdp_http(sock, path)
    print(out.decode('utf-8', errors='replace')[:4000])