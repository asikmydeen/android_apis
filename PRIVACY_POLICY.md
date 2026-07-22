# Privacy Policy for SensIO

**Effective Date:** July 21, 2026  
**Last Updated:** July 21, 2026

SensIO ("we", "our", or "us") respects your privacy. This Privacy Policy explains how data is handled when you use the SensIO mobile application and its local API server features.

---

## 1. Overview & Data Ownership

SensIO is a **privacy-first, local-first developer tool**. It transforms your Android device into a local REST and WebSocket API server to interface with local scripts, robotics, and AI agents.

* **Zero Cloud Collection:** We do not collect, store, transmit, or monetize any of your personal data or sensor telemetry to external cloud servers or third parties.
* **Local Processing:** All device data (including sensors, location, microphone audio levels, camera captures, USB serial streams, and screen touches) is processed exclusively on your device and transmitted only to endpoints on your local network or user-configured private VPN tunnels (e.g., Tailscale, Cloudflare Tunnel).

---

## 2. Information Handled by the Local API Server

SensIO acts as a server on your local device. The following data categories may be processed locally and exposed via HTTP/WebSocket endpoints secured by your local device authentication settings:

* **IMU Motion Sensors:** Accelerometer, gyroscope, linear acceleration, rotation vector, and orientation for gesture detection.
* **Touch Coordinates:** Screen interaction coordinates $(X, Y)$ and pressure indicators when local touch tracking is active.
* **Location Data:** Fine and coarse GPS/network location coordinates (used strictly for local API retrieval when granted).
* **Audio Telemetry:** Microphone Root Mean Square (RMS) decibel volume levels (audio samples are processed in-memory for noise detection and are not saved).
* **Camera Capture:** Camera snapshots captured via local API request are saved locally on your device storage and served directly to your authorized API client.
* **USB Host Serial:** Data sent to or received from connected USB serial peripherals (e.g., Arduino, FTDI) is bridged locally.

---

## 3. Data Protection and Authentication

* **Local Token Authentication:** SensIO enforces Bearer Token authentication when exposed outside of localhost (`127.0.0.1`), ensuring unauthorized devices on your network cannot access your device APIs.
* **User Control:** Every collector (Location, Sensors, Microphone, Touchscreen, USB) can be individually toggled on or off at any time in the app's Settings menu.

---

## 4. Third-Party Services

SensIO does not integrate third-party analytics, tracking SDKs, or advertising networks.

If you choose to use third-party network tunneling software (such as Tailscale or Cloudflare) to access your local SensIO API remotely, your traffic is governed by the respective privacy policies of those services.

---

## 5. Children's Privacy

SensIO does not knowingly collect or solicit any personal information from children. Because all data remains entirely local to the user's device, no personal data is gathered.

---

## 6. Permissions

SensIO requests Android permissions (e.g., Location, Camera, Audio Recording, Foreground Service, Accessibility) solely to provide the local API functionality requested by the user. These permissions are never used to harvest data in the background for third parties.

---

## 7. Changes to This Privacy Policy

We may update this Privacy Policy from time to time. Any updates will be reflected by updating the "Effective Date" at the top of this document and pushing updates to our public repository.

---

## 8. Contact Us

If you have any questions or concerns regarding this Privacy Policy, please open an issue on our GitHub repository:  
[https://github.com/asikmydeen/android_apis](https://github.com/asikmydeen/android_apis)
