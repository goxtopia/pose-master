// Alert Logic (XiaoAi Integration)
async function triggerXiaoAiAlert(type) {
    let endpoint = '/api/relax'; // Default fallback
    let payload = {};

    // Random Message Banks
    const messages = {
        joints: [
            '长时间保持一个姿势了，活动一下关节吧',
            '动动胳膊动动腿，身体更健康',
            '僵硬了吗？扭扭脖子吧',
            '换个姿势吧，身体会感谢你的'
        ],
        body: [
            '坐太久了，起来走两步吧',
            '椅子长钉子了吗？起来活动下',
            '久坐伤身，去接杯水吧',
            '该站起来活动活动了'
        ],
        gaze: [
            '眼睛累了吗，看看远处吧',
            '眺望一下远方，保护视力',
            '闭目养神一会儿吧',
            '给眼睛放个假，看看窗外'
        ]
    };

    function getRandomMessage(type) {
        const list = messages[type];
        if (!list || list.length === 0) return '';
        return list[Math.floor(Math.random() * list.length)];
    }

    // Map alert type to action
    switch (type) {
        case 'joints':
            endpoint = '/api/say';
            payload = { text: getRandomMessage('joints') };
            break;
        case 'body':
            endpoint = '/api/say'; // Or /api/relax if music is preferred for long static
            payload = { text: getRandomMessage('body') };
            break;
        case 'gaze':
            endpoint = '/api/say';
            payload = { text: getRandomMessage('gaze') };
            break;
        default:
            endpoint = '/api/relax';
            break;
    }

    try {
        console.log(`Triggering Alert: ${type}`);
        const res = await fetch(endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        if (res.status === 200) {
            console.log(`XiaoAi Alert Triggered: ${type}`);
        }
    } catch (e) {
        console.error("Failed to trigger XiaoAi alert:", e);
    }
}

// Pixel Motion Detector (Lightweight)
class MotionDetector {
    constructor(width = 64, height = 36) {
        this.canvas = document.createElement('canvas');
        this.canvas.width = width;
        this.canvas.height = height;
        this.ctx = this.canvas.getContext('2d', { willReadFrequently: true });
        this.prevData = null;
        this.threshold = 30; // Pixel diff threshold
        this.percentThreshold = 0.02; // 2% of pixels must change
    }

    checkMotion(videoSource) {
        this.ctx.drawImage(videoSource, 0, 0, this.canvas.width, this.canvas.height);
        const frame = this.ctx.getImageData(0, 0, this.canvas.width, this.canvas.height);
        const data = frame.data;
        
        if (!this.prevData) {
            this.prevData = data;
            return true;
        }

        let diffCount = 0;
        const totalPixels = data.length / 4;
        
        for (let i = 0; i < data.length; i += 8) {
            const rDiff = Math.abs(data[i] - this.prevData[i]);
            const gDiff = Math.abs(data[i+1] - this.prevData[i+1]);
            const bDiff = Math.abs(data[i+2] - this.prevData[i+2]);
            
            if (rDiff + gDiff + bDiff > this.threshold * 3) {
                diffCount++;
            }
        }

        this.prevData = data;
        const changedPercent = (diffCount * 2) / totalPixels;
        return changedPercent > this.percentThreshold;
    }

    updateSensitivity(value) {
        // value is 1-100 (high value = high sensitivity = low threshold)
        // threshold default 30, range maybe 10-60
        // percentThreshold default 0.02, range 0.005 - 0.05
        
        const inverted = 101 - value;
        this.threshold = 10 + (inverted / 100) * 50; 
        this.percentThreshold = 0.005 + (inverted / 100) * 0.05;
        console.log(`Motion Sensitivity Updated: ${value} (Thresh: ${this.threshold.toFixed(1)}, %: ${this.percentThreshold.toFixed(4)})`);
    }
}

// EMA Smoother
class PointSmoother {
    constructor(alpha = 0.5) {
        this.alpha = alpha;
        this.history = null;
    }

    smooth(newValue) {
        if (!this.history) {
            this.history = { ...newValue };
            return this.history;
        }
        this.history.x = this.alpha * newValue.x + (1 - this.alpha) * this.history.x;
        this.history.y = this.alpha * newValue.y + (1 - this.alpha) * this.history.y;
        this.history.z = this.alpha * newValue.z + (1 - this.alpha) * this.history.z;
        this.history.visibility = this.alpha * newValue.visibility + (1 - this.alpha) * this.history.visibility;
        return this.history;
    }
}

// Stability Buffer
class StabilityBuffer {
    constructor(size = 30) {
        this.size = size;
        this.buffer = [];
    }

    push(point) {
        if (!point) return;
        this.buffer.push(point);
        if (this.buffer.length > this.size) {
            this.buffer.shift();
        }
    }

    getStats() {
        if (this.buffer.length < 5) return null;

        let sumX = 0, sumY = 0;
        this.buffer.forEach(p => { sumX += p.x; sumY += p.y; });
        const meanX = sumX / this.buffer.length;
        const meanY = sumY / this.buffer.length;

        let sumSqDiff = 0;
        this.buffer.forEach(p => {
            sumSqDiff += Math.pow(p.x - meanX, 2) + Math.pow(p.y - meanY, 2);
        });
        const variance = sumSqDiff / this.buffer.length;
        const stdDev = Math.sqrt(variance);

        return { x: meanX, y: meanY, stdDev: stdDev };
    }

    reset() {
        this.buffer = [];
    }
}

// Logic: Monitor posture
class PostureMonitor {
    constructor() {
        this.smoothers = {}; 
        this.bodyCenterSmoother = new PointSmoother(0.05); 
        this.stabilityBuffer = new StabilityBuffer(30);

        // Anchors
        this.anchorPose = null; 
        this.anchorYaw = null;
        this.anchorBodyCenter = null; 
        
        // Timers (Last Move Timestamps)
        this.lastBodyMove = Date.now();
        this.lastJointsMove = Date.now();
        this.lastGazeMove = Date.now();
        
        this.isStatic = false;
        
        // Config Defaults
        this.limits = {
            joints: 1500000,
            body: 1800000,
            gaze: 600000
        };
        
        this.movementThreshold = 0.05; 
        this.yawThreshold = 0.15; // Increased slightly to allow natural small turns
        this.smoothingAlpha = 0.2; 
        this.coarseThreshold = 0.05; 
        
        this.keyIndices = [0, 2, 5, 7, 8, 11, 12]; 
    }

    updateConfig(limits, sensitivity) {
        this.limits = {
            joints: limits.joints * 1000,
            body: limits.body * 1000,
            gaze: limits.gaze * 1000
        };
        this.movementThreshold = 0.15 - (sensitivity / 100) * 0.14; 
    }

    reset() {
        this.anchorPose = null;
        this.anchorYaw = null;
        this.anchorBodyCenter = null;
        
        const now = Date.now();
        this.lastBodyMove = now;
        this.lastJointsMove = now;
        this.lastGazeMove = now;
        
        this.isStatic = false;
        this.smoothers = {};
        this.bodyCenterSmoother = new PointSmoother(0.05);
        this.stabilityBuffer.reset();
    }

    calculateYaw(landmarks) {
        const nose = landmarks[0];
        const leftEar = landmarks[7];
        const rightEar = landmarks[8];
        if (nose.visibility < 0.5 || leftEar.visibility < 0.5 || rightEar.visibility < 0.5) return null;
        const midEarX = (leftEar.x + rightEar.x) / 2;
        const earDist = Math.abs(leftEar.x - rightEar.x);
        if (earDist === 0) return 0;
        return (nose.x - midEarX) / earDist;
    }

    calculateBodyCenter(landmarks) {
        let points = [landmarks[11], landmarks[12], landmarks[23], landmarks[24]];
        let sumX = 0, sumY = 0, count = 0;
        points.forEach(p => {
            if (p && p.visibility > 0.5) {
                sumX += p.x;
                sumY += p.y;
                count++;
            }
        });
        if (count === 0) return null;
        return { x: sumX / count, y: sumY / count, z: 0, visibility: 1 };
    }

    process(landmarks, motionDetected) {
        const now = Date.now();
        
        let jointsMoved = false;
        let gazeMoved = false;
        let bodyMoved = false;

        let bodyCenter = null;
        let bodyStats = null;

        if (motionDetected && landmarks) {
            // 1. Smooth
            const smoothedLandmarks = [];
            for (let i = 0; i < landmarks.length; i++) {
                if (!this.smoothers[i]) {
                    this.smoothers[i] = new PointSmoother(this.smoothingAlpha);
                }
                smoothedLandmarks[i] = this.smoothers[i].smooth(landmarks[i]);
            }

            // 2. Body Logic
            const rawCenter = this.calculateBodyCenter(smoothedLandmarks);
            if (rawCenter) {
                bodyCenter = this.bodyCenterSmoother.smooth(rawCenter);
                this.stabilityBuffer.push(bodyCenter);
                bodyStats = this.stabilityBuffer.getStats();
            }

            // 3. Gaze & Joints Logic
            const currentYaw = this.calculateYaw(smoothedLandmarks);

            // Initialize Anchors
            if (!this.anchorPose) {
                this.anchorPose = smoothedLandmarks.map(p => ({...p}));
                this.anchorYaw = currentYaw;
                this.anchorBodyCenter = bodyStats ? {x: bodyStats.x, y: bodyStats.y} : (bodyCenter ? {...bodyCenter} : null);
                this.lastBodyMove = now;
                this.lastJointsMove = now;
                this.lastGazeMove = now;
                return { 
                    timers: { joints: 0, body: 0, gaze: 0 }, 
                    alertType: null 
                };
            }

            // --- Check Joints (Fine Pose) ---
            let totalDelta = 0;
            let validPoints = 0;
            for (const idx of this.keyIndices) {
                const p1 = this.anchorPose[idx];
                const p2 = smoothedLandmarks[idx];
                if (p1.visibility > 0.5 && p2.visibility > 0.5) {
                    const dist = Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
                    totalDelta += dist;
                    validPoints++;
                }
            }
            const avgDelta = validPoints > 0 ? totalDelta / validPoints : 0;
            
            // Jitter Gate for Joints
            if (avgDelta > Math.max(0.015, this.movementThreshold)) {
                jointsMoved = true;
                this.anchorPose = smoothedLandmarks.map(p => ({...p}));
            }

            // --- Check Gaze ---
            if (currentYaw !== null && this.anchorYaw !== null) {
                const yawDelta = Math.abs(currentYaw - this.anchorYaw);
                if (yawDelta > this.yawThreshold) {
                    gazeMoved = true;
                    this.anchorYaw = currentYaw;
                }
            }

            // --- Check Body (Coarse Adaptive) ---
            if (bodyStats && this.anchorBodyCenter) {
                const dist = Math.sqrt(
                    Math.pow(bodyStats.x - this.anchorBodyCenter.x, 2) + 
                    Math.pow(bodyStats.y - this.anchorBodyCenter.y, 2)
                );
                
                const noiseFactor = Math.min(bodyStats.stdDev * 2, 0.05); 
                const effectiveThreshold = this.coarseThreshold + noiseFactor;

                if (dist > effectiveThreshold) {
                    bodyMoved = true;
                    this.anchorBodyCenter = {x: bodyStats.x, y: bodyStats.y};
                }
            }
        } 
        
        // --- Dependency Reset Logic ---
        
        // 1. If Body Moved (e.g., Walking), EVERYTHING resets.
        // It's impossible to have static gaze or joints if the whole body is walking around.
        if (bodyMoved) {
            this.lastBodyMove = now;
            this.lastJointsMove = now;
            this.lastGazeMove = now;
        } 
        // 2. If Joints Moved (e.g., Stretching arms), Joints reset.
        else if (jointsMoved) {
            this.lastJointsMove = now;
            // Does moving joints reset Gaze? Maybe not.
            // Does moving joints reset Body? No, you can sit and wave arms.
        }
        
        // 3. If Gaze Moved (Looking around), Gaze resets.
        if (gazeMoved) {
            this.lastGazeMove = now;
        }


        // --- Timer Calculation ---
        const tBody = (now - this.lastBodyMove);
        const tJoints = (now - this.lastJointsMove);
        const tGaze = (now - this.lastGazeMove);

        // --- Alert Trigger ---
        let alertType = null;
        
        // Priority: Body > Joints > Gaze (Arbitrary, can be changed)
        if (tBody > this.limits.body) {
            alertType = 'body';
        } else if (tJoints > this.limits.joints) {
            alertType = 'joints';
        } else if (tGaze > this.limits.gaze) {
            alertType = 'gaze';
        }

        return { 
            timers: { 
                joints: tJoints / 1000, 
                body: tBody / 1000, 
                gaze: tGaze / 1000 
            }, 
            alertType: alertType 
        };
    }
}

// Main Application
const videoElement = document.getElementsByClassName('input_video')[0];
const canvasElement = document.getElementsByClassName('output_canvas')[0];
const canvasCtx = canvasElement.getContext('2d');
const alertOverlay = document.getElementById('alert-overlay');
const statusText = document.getElementById('status-text');

// Timers UI
const timerJoints = document.getElementById('timer-joints');
const timerBody = document.getElementById('timer-body');
const timerGaze = document.getElementById('timer-gaze');

const deltaText = document.getElementById('delta-text');
const startBtn = document.getElementById('start-btn');

// Config Inputs
const inputJoints = document.getElementById('time-joints');
const inputBody = document.getElementById('time-body');
const inputGaze = document.getElementById('time-gaze');
const sensInput = document.getElementById('sensitivity');
const motionSensInput = document.getElementById('motion-sensitivity');

const monitor = new PostureMonitor();
const motionDetector = new MotionDetector();

let isRunning = false;
let lastAlertTime = 0;
let lastSentAlertType = null;

// User Away Logic
let lastPixelMotionTime = Date.now();
let isUserAway = false;
const AWAY_TIMEOUT = 5 * 60 * 1000; // 5 minutes

// MediaPipe Setup
const pose = new Pose({locateFile: (file) => {
  if (file.endsWith('.js')) {
    const path = `libs/${file}`;
    console.log(`Loading MediaPipe Asset (Local): ${file} -> ${path}`);
    return path;
  }
  const path = `https://cdn.jsdelivr.net/npm/@mediapipe/pose@0.5.1675469404/${file}`;
  console.log(`Loading MediaPipe Asset (CDN): ${file} -> ${path}`);
  return path;
}});

pose.setOptions({
  modelComplexity: 0,
  smoothLandmarks: true,
  enableSegmentation: false,
  minDetectionConfidence: 0.5,
  minTrackingConfidence: 0.5
});

pose.onResults(onResults);

// Pre-compute body-only connections (exclude face indices 0-10)
const BODY_CONNECTIONS = window.POSE_CONNECTIONS ? window.POSE_CONNECTIONS.filter(c => c[0] > 10 && c[1] > 10) : [];

function onResults(results) {
    canvasCtx.save();
    canvasCtx.clearRect(0, 0, canvasElement.width, canvasElement.height);
    canvasCtx.drawImage(results.image, 0, 0, canvasElement.width, canvasElement.height);

    const now = Date.now();
    // Check if we are verifying "Away" status
    // If no pixel motion recently, and we found landmarks -> User is static.
    // If no pixel motion recently, and NO landmarks -> User is away.
    const isVerificationCheck = (now - lastPixelMotionTime > AWAY_TIMEOUT);

    if (results.poseLandmarks) {
        if (isVerificationCheck) {
            // User found! Reset timeout (user is static but present)
            // But don't reset lastPixelMotionTime fully? 
            // Actually, if user is present, we just treat it as static. 
            // We want to avoid re-checking every frame if they are just sitting still.
            // Resetting lastPixelMotionTime would verify again in 5 mins.
            console.log("User verified present (Static).");
            lastPixelMotionTime = now; 
        }

        // Draw only body connections
        if (BODY_CONNECTIONS.length > 0) {
            drawConnectors(canvasCtx, results.poseLandmarks, BODY_CONNECTIONS,
                        {color: '#00FF00', lineWidth: 4});
        }
        
        // Draw only body landmarks (filter out 0-10)
        // Note: drawLandmarks iterates the array. We can pass a filtered array.
        // We shouldn't change the original array because it's used for calculation.
        const bodyLandmarks = results.poseLandmarks.filter((_, i) => i > 10);
        drawLandmarks(canvasCtx, bodyLandmarks,
                    {color: '#FF0000', lineWidth: 2});
        
        const center = monitor.calculateBodyCenter(results.poseLandmarks);
        if (center) {
            canvasCtx.beginPath();
            canvasCtx.arc(center.x * canvasElement.width, center.y * canvasElement.height, 10, 0, 2 * Math.PI);
            canvasCtx.fillStyle = "blue";
            canvasCtx.fill();
        }

        // If this came from a motion trigger, passing true. 
        // If from verification (no motion), passing false.
        updateMonitorState(results.poseLandmarks, !isVerificationCheck);
    } else {
        if (isVerificationCheck) {
             console.log("User not found. Entering AWAY mode.");
             isUserAway = true;
             monitor.reset();
             // We don't call updateMonitorState, effectively pausing alerts.
             return;
        }
        updateMonitorState(null, true);
    }
    canvasCtx.restore();
}

function updateMonitorState(landmarks, physicalMotion) {
    if (!isRunning) return;

    const state = monitor.process(landmarks, physicalMotion);
    
    // Update UI Timers
    timerJoints.innerText = state.timers.joints.toFixed(1) + 's';
    timerBody.innerText = state.timers.body.toFixed(1) + 's';
    timerGaze.innerText = state.timers.gaze.toFixed(1) + 's';

    // Color Logic (Visual warning)
    timerJoints.style.color = state.timers.joints > (monitor.limits.joints/1000 * 0.8) ? '#ffcc00' : '#4cd964';
    timerBody.style.color = state.timers.body > (monitor.limits.body/1000 * 0.8) ? '#ffcc00' : '#4cd964';
    timerGaze.style.color = state.timers.gaze > (monitor.limits.gaze/1000 * 0.8) ? '#ffcc00' : '#4cd964';


    if (state.alertType) {
        alertOverlay.classList.remove('hidden');
        alertOverlay.innerText = `⚠️ ${state.alertType.toUpperCase()} ALERT ⚠️`;
        
        const now = Date.now();
        // Escalating Alert Frequency Logic
        let alertInterval = 60000; // Base interval: 60s
        
        // Calculate overtime duration (how long past the limit?)
        // state.timers is current total duration in seconds.
        // limit is in ms.
        const currentDurationMs = state.timers[state.alertType] * 1000;
        const limitMs = monitor.limits[state.alertType];
        
        if (currentDurationMs > limitMs) {
            const overtimeMs = currentDurationMs - limitMs;
            // Formula: Decrease interval as overtime increases.
            // Example: 
            // Overtime 0s -> Interval 60s
            // Overtime 300s (5m) -> Interval ~30s
            // Overtime 600s (10m) -> Interval ~20s
            // Coefficient 300000 (5 minutes) implies that after 5 mins overtime, freq doubles.
            const coefficient = 300000; 
            alertInterval = 60000 / (1 + (overtimeMs / coefficient));
            
            // Cap at minimum 10s to avoid spamming too hard
            alertInterval = Math.max(10000, alertInterval);
        }

        // Trigger XiaoAi if interval passed OR if alert type changes
        if (now - lastAlertTime > alertInterval || state.alertType !== lastSentAlertType) {
            console.log(`Alerting! Interval: ${(alertInterval/1000).toFixed(1)}s (Overtime: ${((currentDurationMs - limitMs)/1000).toFixed(0)}s)`);
            triggerXiaoAiAlert(state.alertType);
            lastAlertTime = now;
            lastSentAlertType = state.alertType;
        }
    } else {
        alertOverlay.classList.add('hidden');
        // Optional: clear lastSentAlertType if we want to re-trigger same alert after silence? 
        // But the user complained about "cooldown", usually implying they want MORE alerts.
        // If alert clears, we probably should allow immediate re-trigger if it comes back?
        // Let's reset lastSentAlertType when clear.
        if (lastSentAlertType) {
            lastSentAlertType = null;
            // Also reset timer? No, let's keep the 60s cooldown if it flickers.
        }
    }
}

const camera = new Camera(videoElement, {
  onFrame: async () => {
    if (!isRunning) {
        await pose.send({image: videoElement});
        return;
    }

    const hasMotion = motionDetector.checkMotion(videoElement);
    const now = Date.now();
    
    // UI Update for Status
    if (isUserAway) {
        deltaText.innerText = "User Away (Paused)";
        deltaText.style.color = "#ff3b30"; // Red
    } else if (hasMotion) {
        deltaText.innerText = "Active";
        deltaText.style.color = "#4cd964"; // Green
    } else {
        deltaText.innerText = "Static (Low Power)";
        deltaText.style.color = "#007acc"; // Blue
    }

    if (hasMotion) {
        lastPixelMotionTime = now;
        if (isUserAway) {
            // User returned!
            console.log("User returned (motion detected). Resuming...");
            isUserAway = false;
            monitor.reset(); // Reset timers as user just came back
        }
        await pose.send({image: videoElement});
    } else {
        // No pixel motion
        if (isUserAway) {
            // User is away, waiting for motion. Do nothing.
            // But we must clear canvas or show feed?
            canvasCtx.save();
            canvasCtx.clearRect(0, 0, canvasElement.width, canvasElement.height);
            canvasCtx.drawImage(videoElement, 0, 0, canvasElement.width, canvasElement.height);
            // Optional: Draw "AWAY" overlay?
            canvasCtx.fillStyle = "rgba(0,0,0,0.5)";
            canvasCtx.fillRect(0,0, canvasElement.width, canvasElement.height);
            canvasCtx.fillStyle = "white";
            canvasCtx.font = "30px Arial";
            canvasCtx.fillText("USER AWAY", 20, 50);
            canvasCtx.restore();
            return; 
        }

        if (now - lastPixelMotionTime > AWAY_TIMEOUT) {
            // Check if user is really gone
            console.log("Idle timeout reached. Checking for user presence via Pose...");
            await pose.send({image: videoElement});
            // onResults needs to handle the logic. 
            // If pose detects landmarks, it calls updateMonitorState(landmarks, true/false).
            // But how do we distinguish "Checking for Away" vs "Normal Frame"?
            // We can check the time difference again in onResults or just let it flow.
            // Ideally, if Pose finds landmarks, we just treat it as "Static User" (motion=false).
            // If Pose finds NO landmarks, we set isUserAway=true.
        } else {
            // Optimization: Assume user present but static
            canvasCtx.save();
            canvasCtx.clearRect(0, 0, canvasElement.width, canvasElement.height);
            canvasCtx.drawImage(videoElement, 0, 0, canvasElement.width, canvasElement.height);
            canvasCtx.restore();
            updateMonitorState(null, false);
        }
    }
  },
  width: 640,
  height: 360
});

canvasElement.width = 640;
canvasElement.height = 360;

// Controls
function getConfigs() {
    return {
        joints: parseInt(inputJoints.value) || 30,
        body: parseInt(inputBody.value) || 300,
        gaze: parseInt(inputGaze.value) || 20
    };
}

// Config Persistence
async function loadConfig() {
    try {
        const res = await fetch('/api/config');
        const config = await res.json();
        
        if (config.joints) inputJoints.value = config.joints;
        if (config.body) inputBody.value = config.body;
        if (config.gaze) inputGaze.value = config.gaze;
        if (config.sensitivity) sensInput.value = config.sensitivity;
        if (config.motionSensitivity) motionSensInput.value = config.motionSensitivity;
        
        // Apply loaded configs immediately if needed
        monitor.updateConfig(getConfigs(), parseInt(sensInput.value));
        motionDetector.updateSensitivity(parseInt(motionSensInput.value));
        
        console.log("Config loaded:", config);
    } catch (e) {
        console.error("Failed to load config:", e);
    }
}

let saveTimeout = null;
function saveConfig() {
    if (saveTimeout) clearTimeout(saveTimeout);
    saveTimeout = setTimeout(async () => {
        const config = {
            ...getConfigs(),
            sensitivity: parseInt(sensInput.value),
            motionSensitivity: parseInt(motionSensInput.value)
        };
        try {
            await fetch('/api/config', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(config)
            });
            console.log("Config saved.");
        } catch (e) {
            console.error("Failed to save config:", e);
        }
    }, 1000); // Debounce 1s
}

// Initialize
loadConfig();

startBtn.addEventListener('click', () => {
    if (!isRunning) {
        monitor.updateConfig(
            getConfigs(),
            parseInt(sensInput.value)
        );
        motionDetector.updateSensitivity(parseInt(motionSensInput.value));
        monitor.reset();
        camera.start();
        isRunning = true;
        startBtn.innerText = "Stop Monitor";
        startBtn.style.backgroundColor = "#ff3b30";
    } else {
        fetch('/api/stop', { method: 'POST' });
        isRunning = false;
        startBtn.innerText = "Start Monitor";
        startBtn.style.backgroundColor = "#007acc";
        alertOverlay.classList.add('hidden');
        
        timerJoints.innerText = "0.0s";
        timerBody.innerText = "0.0s";
        timerGaze.innerText = "0.0s";
    }
});

// Live Updates & Auto-Save
[inputJoints, inputBody, inputGaze, sensInput].forEach(el => {
    el.addEventListener('change', () => {
        monitor.updateConfig(getConfigs(), parseInt(sensInput.value));
        saveConfig();
    });
});

motionSensInput.addEventListener('change', () => {
    motionDetector.updateSensitivity(parseInt(motionSensInput.value));
    saveConfig();
});
