import http.server
import socketserver
import mimetypes
import os

PORT = 8080

# Ensure WASM MIME type is registered
mimetypes.add_type('application/wasm', '.wasm')

class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        # Enable CORS for local testing flexibility
        self.send_header('Access-Control-Allow-Origin', '*')
        super().end_headers()

    def do_POST(self):
        # Mock API endpoints for simple testing without Flask
        if self.path.startswith('/api/'):
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"status": "success", "message": "Mock Server Action"}')
        else:
            self.send_error(404)

print(f"Simple Server serving at http://localhost:{PORT}")
print("Press Ctrl+C to stop.")

with socketserver.TCPServer(("", PORT), Handler) as httpd:
    httpd.serve_forever()
