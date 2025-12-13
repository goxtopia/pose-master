import logging
import time
import threading
import os
import sys
import json
import mimetypes
import webbrowser
from flask import Flask, request, jsonify, send_from_directory

# Ensure correct MIME types for WASM
mimetypes.add_type('application/wasm', '.wasm')
mimetypes.add_type('application/javascript', '.js')
mimetypes.add_type('application/javascript', '.mjs')

# --- Mock mijiaAPI if not available or fails (for Sandbox robustness) ---
try:
    from mijiaAPI import mijiaAPI, mijiaDevice
    MIJIA_AVAILABLE = True
except ImportError:
    MIJIA_AVAILABLE = False
    print("WARNING: mijiaAPI not found. Running in MOCK mode.")

# --- 配置日志 ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("XiaoAiServer")
# 屏蔽 mijiaAPI 的调试日志
if MIJIA_AVAILABLE:
    logging.getLogger("mijiaAPI").setLevel(logging.WARNING)
logging.getLogger("werkzeug").setLevel(logging.WARNING) # 减少 Flask 的日志噪音

# --- 控制器核心逻辑 ---
class XiaoAiController:
    _instance = None
    _lock = threading.Lock()

    def __init__(self):
        self.api = None
        self.device = None
        self.device_name = 'Mi AI Speaker Play Plus' # 请确保名称与米家App一致
        
        # 状态控制
        self.is_running_scene = False # 标记是否正在运行“舒缓音乐”场景
        self.stop_event = threading.Event() # 用于打断睡眠的信号

    @classmethod
    def get_instance(cls):
        if not cls._instance:
            with cls._lock:
                if not cls._instance:
                    cls._instance = cls()
        return cls._instance

    def _init_device(self):
        """懒加载连接设备"""
        if not MIJIA_AVAILABLE:
            logger.info("[MOCK] Initializing Mock Device...")
            return "MOCK_DEVICE"

        if self.device is None:
            try:
                logger.info("Initializing Mijia API...")
                # Try-catch the login because it might require interactive QR code which fails here
                try:
                    self.api = mijiaAPI()
                    # self.api.login() # Assuming automatic if token exists, or this might block
                except Exception as e:
                    logger.error(f"Login failed (interactive?): {e}")
                    # In sandbox, we proceed as mock if login fails
                    return None

                self.device = mijiaDevice(self.api, dev_name=self.device_name, sleep_time=1)
                logger.info(f"Device '{self.device_name}' connected.")
            except Exception as e:
                logger.error(f"Failed to initialize device: {e}")
                self.device = None
        return self.device

    def _ensure_connection(self):
        """确保设备已连接，失败则抛出异常"""
        if not self._init_device():
            if MIJIA_AVAILABLE:
                raise Exception("Device connection failed")
            else:
                return # Mock always succeeds

    # --- 核心功能实现 ---

    def run_relax_scene_thread(self):
        """后台线程：运行舒缓音乐流程"""
        if self.is_running_scene:
            logger.warning("Relax scene is already running.")
            return False

        def task():
            self.is_running_scene = True
            self.stop_event.clear() # 重置停止信号
            
            try:
                self._ensure_connection()
                logger.info("Starting relax scene...")

                if MIJIA_AVAILABLE and self.device:
                    # 1. 播放欢迎语
                    self.device.run_action('play-text', _in=['你好，臭竹米，我是小爱同学，我现在要给你来点舒缓的音乐'])
                    
                    # 等待说完 (如果收到停止信号，立即退出)
                    if self.stop_event.wait(3): return 

                    # 2. 播放水流声
                    self.device.run_action('execute-text-directive', _in=['播放水流的声音', True])
                    logger.info("Playing nature sounds...")

                    # 3. 播放持续时间 (例如 40秒)，支持中途打断
                    if self.stop_event.wait(40): 
                        logger.info("Scene interrupted by stop command.")
                    else:
                        logger.info("Scene finished normally. Stopping playback.")
                        self.device.run_action('execute-text-directive', _in=['停止播放', True])
                else:
                    logger.info("[MOCK] Playing relax scene...")
                    time.sleep(1)

            except Exception as e:
                logger.error(f"Error in relax scene: {e}")
            finally:
                self.is_running_scene = False

        t = threading.Thread(target=task)
        t.daemon = True
        t.start()
        return True

    def speak_text(self, text):
        """直接让小爱说话"""
        self._ensure_connection()
        logger.info(f"TTS: {text}")
        if MIJIA_AVAILABLE and self.device:
            return self.device.run_action('play-text', _in=[text])
        else:
            logger.info(f"[MOCK] Speaking: {text}")
            return True

    def execute_directive(self, command):
        """执行通用指令 (相当于对着小爱说 command)"""
        self._ensure_connection()
        logger.info(f"Command: {command}")
        if MIJIA_AVAILABLE and self.device:
            return self.device.run_action('execute-text-directive', _in=[command, True])
        else:
            logger.info(f"[MOCK] Executing: {command}")
            return True

    def stop_all(self):
        """停止播放并打断当前场景"""
        self._ensure_connection()
        logger.info("Stopping all playback...")
        
        self.stop_event.set()
        
        if MIJIA_AVAILABLE and self.device:
            return self.device.run_action('execute-text-directive', _in=['停止播放', True])
        else:
            logger.info("[MOCK] Stopped.")
            return True


# --- Flask Web Server 设置 ---

if getattr(sys, 'frozen', False):
    # Running in a bundle
    BASE_DIR = sys._MEIPASS
    APP_ROOT = os.path.dirname(sys.executable) # Path to the executable
else:
    # Running in a normal Python environment
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    APP_ROOT = BASE_DIR

CONFIG_FILE = os.path.join(APP_ROOT, 'config.json')

app = Flask(__name__, static_folder=BASE_DIR)
controller = XiaoAiController.get_instance()

@app.route('/api/relax', methods=['POST', 'GET'])
def api_relax():
    """接口：播放令人放松的音乐 (后台运行)"""
    success = controller.run_relax_scene_thread()
    if success:
        return jsonify({"status": "success", "message": "Relaxing music started"}), 200
    else:
        return jsonify({"status": "ignored", "message": "Already running"}), 409

@app.route('/api/say', methods=['POST'])
def api_say():
    """接口：TTS (播放文字)"""
    data = request.json or {}
    text = data.get('text') or request.args.get('text')
    
    if not text:
        return jsonify({"status": "error", "message": "Missing 'text' parameter"}), 400
    
    try:
        controller.speak_text(text)
        return jsonify({"status": "success", "message": f"Said: {text}"}), 200
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/api/action', methods=['POST'])
def api_action():
    """接口：执行任意指令 (如：关灯、查询天气)"""
    data = request.json or {}
    command = data.get('command') or request.args.get('command')
    
    if not command:
        return jsonify({"status": "error", "message": "Missing 'command' parameter"}), 400
    
    try:
        controller.execute_directive(command)
        return jsonify({"status": "success", "message": f"Executed: {command}"}), 200
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/api/stop', methods=['POST', 'GET'])
def api_stop():
    """接口：停止播放/重置"""
    try:
        controller.stop_all()
        return jsonify({"status": "success", "message": "Stopped"}), 200
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/api/config', methods=['GET', 'POST'])
def api_config():
    """接口：读取/保存配置"""
    if request.method == 'GET':
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                    return jsonify(json.load(f)), 200
            except Exception as e:
                logger.error(f"Failed to read config: {e}")
                return jsonify({}), 200
        else:
            return jsonify({}), 200
    
    elif request.method == 'POST':
        try:
            data = request.json
            with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
                json.dump(data, f, indent=4, ensure_ascii=False)
            logger.info("Config saved.")
            return jsonify({"status": "success"}), 200
        except Exception as e:
            logger.error(f"Failed to save config: {e}")
            return jsonify({"status": "error", "message": str(e)}), 500

# --- Serve Static Files (Frontend) ---

@app.route('/')
def serve_index():
    return send_from_directory(BASE_DIR, 'index.html')

@app.route('/<path:path>')
def serve_file(path):
    return send_from_directory(BASE_DIR, path)

if __name__ == "__main__":
    # 尝试登录 (CLI交互)
    if MIJIA_AVAILABLE:
        try:
            print("Attempting to login to Mijia API... (Check console for QR code if needed)")
            # 无论如何都尝试登录
            mijiaAPI().login()
        except Exception as e:
            print(f"Login check failed: {e}")

    logger.info("Starting XiaoAi Server on port 8080...")
    
    # Auto-open browser
    def open_browser():
        webbrowser.open("http://127.0.0.1:8080")
    
    threading.Timer(1.5, open_browser).start()

    # Using 8080 to match user's apparent environment
    app.run(host='0.0.0.0', port=8080, debug=False)
