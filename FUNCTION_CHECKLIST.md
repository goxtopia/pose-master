# Function Checklist: Posture Monitor (Web vs Android)

This checklist confirms the feature parity between the original Web implementation and the new Android Native implementation.

| Feature | Web (HTML/JS) | Android (Kotlin) | Status |
| :--- | :--- | :--- | :--- |
| **Core Technology** | MediaPipe Pose (JS) | MediaPipe Pose (Tasks Vision) | ✅ Synced |
| **Model** | Default (Lite/Full auto) | **Normal** (`pose_landmarker_full.task`) | ✅ Synced |
| **Camera** | Front Camera (Default) | CameraX (Front/Back Switchable) | ✅ Synced (Android +Back) |
| **Drawing** | Canvas API (Landmarks + Connections) | Custom View `onDraw` (Landmarks + Connections) | ✅ Synced |
| **Motion Detection** | Pixel Diff (Canvas 64x36) | Pixel Diff (Bitmap 64x36) | ✅ Synced |
| **Motion Sensitivity** | Configurable via Slider | Configurable via SeekBar | ✅ Synced |
| **Posture Logic** | `PostureMonitor.js` | `PostureMonitor.kt` | ✅ Synced |
| **Timers** | Joints, Body, Gaze | Joints, Body, Gaze | ✅ Synced |
| **Config Limits** | Configurable Inputs (s) | Configurable Inputs (s) | ✅ Synced |
| **Sensitivity** | Configurable via Slider | Configurable via SeekBar | ✅ Synced |
| **User Away Logic** | 5 Min Timeout | 5 Min Timeout | ✅ Synced |
| **Alerting (Visual)** | Overlay "MOVE!" | Overlay "MOVE!" | ✅ Synced |
| **Alerting (Backend)** | Fetch API to `/api/say`, `/api/relax` | OkHttp to `/api/say`, `/api/relax` | ✅ Synced |
| **Escalating Alerts** | Frequency increases with overtime | Frequency increases with overtime | ✅ Synced |
| **Config Persistence** | Load/Save from `/api/config` | Load/Save from `/api/config` | ✅ Synced |
| **Server Connection** | Same Origin (Relative URL) | Manual IP Entry (e.g. `10.0.2.2`) | ✅ Synced (Adapted) |
| **UI** | HTML/CSS Overlay | Native Layout (ConstraintLayout) | ✅ Synced |

## Implementation Notes

1.  **Android Assets**: The Android app expects the MediaPipe model file `pose_landmarker_full.task` to be present in `android/app/src/main/assets/`.
2.  **Networking**: The Android app requires the user to input the server IP address (default `10.0.2.2` for Emulator -> Localhost) to communicate with the Python backend.
3.  **Permissions**: Android app handles Runtime Camera Permissions.
4.  **Camera Switching**: Android app adds a button to switch between front and back cameras, a feature not explicitly present in the simple Web UI.
