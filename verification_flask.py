import requests
import time
import threading
from server_flask import app

def run_server():
    app.run(port=8000)

def verify_xiaoai_server():
    # Start server in thread
    t = threading.Thread(target=run_server)
    t.daemon = True
    t.start()
    time.sleep(2) # Wait for startup

    base_url = "http://localhost:8000"

    print("1. Verifying Static Files...")
    r = requests.get(base_url + "/")
    if r.status_code == 200 and "Posture Monitor" in r.text:
        print("   Static index.html served: OK")
    else:
        print(f"   Static serve FAILED: {r.status_code}")

    print("2. Verifying API - Say...")
    try:
        r = requests.post(base_url + "/api/say", json={"text": "Test"})
        print(f"   Response: {r.json()}")
        if r.status_code == 200:
            print("   API /api/say: OK")
        else:
            print(f"   API /api/say FAILED: {r.status_code}")
    except Exception as e:
        print(f"   API check FAILED: {e}")

    print("3. Verifying API - Relax (Mock)...")
    try:
        r = requests.post(base_url + "/api/relax")
        print(f"   Response: {r.json()}")
        if r.status_code == 200 or r.status_code == 409:
            print("   API /api/relax: OK")
        else:
            print(f"   API /api/relax FAILED: {r.status_code}")
    except Exception as e:
        print(f"   API check FAILED: {e}")

if __name__ == "__main__":
    verify_xiaoai_server()
