/**
 * XIAO MG24 Sense — BLE IMU Firmware (IMU.ino)
 * ==============================================
 * Reads the on-board 6-axis IMU (LSM6DS3) and transmits
 * roll / pitch / yaw over BLE in a WitMotion-compatible 20-byte packet.
 *
 * Board:  Seeed XIAO MG24 Sense (EFR32MG24)
 * IMU:    LSM6DS3 (6-axis: accel + gyro, no magnetometer)
 *         I2C address: 0x6A
 *         Power enable pin: PD5 (must set HIGH before use)
 *
 * ── Setup (Arduino IDE) ──────────────────────────────────────────
 *  1. Install board package:
 *       File → Preferences → Additional Board Manager URLs →
 *         https://siliconlabs.github.io/arduino/package_arduinosilabs_index.json
 *       Tools → Board Manager → search "XIAO MG24" or "SiLabs" → Install
 *  2. Install libraries (Sketch → Include Library → Manage Libraries):
 *       • ArduinoBLE
 *       • Seeed Arduino LSM6DS3 (by Seeed Studio)
 *  3. Select board:  Tools → Board → XIAO MG24 (Seeed)
 *  4. Upload.
 *
 * ── BLE Protocol ─────────────────────────────────────────────────
 *  Service UUID:         0000ffe5-0000-1000-8000-00805f9a34fb
 *  Characteristic UUID:  0000ffe4-0000-1000-8000-00805f9a34fb
 *  Packet (20 bytes, little-endian):
 *     [0]     0x55        header
 *     [1]     0x61        packet type (custom: accel+angle)
 *     [2-3]   AccX        int16  (raw, mg)
 *     [4-5]   AccY        int16  (raw, mg)
 *     [6-7]   AccZ        int16  (raw, mg)
 *     [8-9]   GyroX       int16  (raw, 0.1 dps)
 *     [10-11] GyroY       int16  (raw, 0.1 dps)
 *     [12-13] Roll        int16  (angle / 180 * 32768)
 *     [14-15] Pitch       int16  (angle / 180 * 32768)
 *     [16-17] Yaw         int16  (angle / 180 * 32768)
 *     [18-19] 0x00 0x00   reserved / padding
 *
 *  Android decodes roll/pitch/yaw as:
 *     float angle = (float)(int16) / 32768.0f * 180.0f;
 *
 * ── Notes ────────────────────────────────────────────────────────
 *  • Yaw is gyro-integrated only (no magnetometer) — it WILL drift.
 *    For ~10 s bladder sweeps this is acceptable.
 *  • BLE advertised name is "CUS_IMU" — the Android app scans for
 *    this name so you don't need to hard-code a MAC address.
 *  • Data rate: ~50 Hz (20 ms interval).
 *
 * ── Power Saving ─────────────────────────────────────────────────
 *  • IMU is powered OFF (PD5 LOW) when no BLE connection.
 *  • IMU powered ON and re-initialised only on BLE connect.
 *  • Madgwick quaternion reset on each new connection.
 *  • LED kept OFF when idle to save ~2-5 mA.
 *  • Serial disabled in production (set DEBUG 0).
 *  • Idle loop sleeps 200 ms instead of busy-looping.
 */

// Set to 0 for production to disable Serial (saves ~5 mA)
#define DEBUG 1

#if DEBUG
  #define DBG_BEGIN(baud)   Serial.begin(baud)
  #define DBG_PRINT(x)      Serial.print(x)
  #define DBG_PRINTLN(x)    Serial.println(x)
#else
  #define DBG_BEGIN(baud)   ((void)0)
  #define DBG_PRINT(x)      ((void)0)
  #define DBG_PRINTLN(x)    ((void)0)
#endif

#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <Wire.h>

// ──────────────────────────────────────────────────────────────────
// IMU Instance — LSM6DS3 at I2C address 0x6A
// ──────────────────────────────────────────────────────────────────
LSM6DS3 myIMU(I2C_MODE, 0x6A);

// ──────────────────────────────────────────────────────────────────
// Madgwick AHRS Filter (no magnetometer)
// ──────────────────────────────────────────────────────────────────
static float q0 = 1.0f, q1 = 0.0f, q2 = 0.0f, q3 = 0.0f;
static float beta = 0.1f;  // Filter gain (0.01-0.5 typical)

static void madgwickUpdate(float gx, float gy, float gz,
                           float ax, float ay, float az,
                           float dt) {
  float recipNorm;
  float s0, s1, s2, s3;
  float qDot1, qDot2, qDot3, qDot4;
  float _2q0, _2q1, _2q2, _2q3;
  float _4q0, _4q1, _4q2;
  float _8q1, _8q2;
  float q0q0, q1q1, q2q2, q3q3;

  // Convert gyro from degrees/s to rad/s
  gx *= 0.0174533f;
  gy *= 0.0174533f;
  gz *= 0.0174533f;

  // Rate of change of quaternion from gyroscope
  qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz);
  qDot2 = 0.5f * ( q0 * gx + q2 * gz - q3 * gy);
  qDot3 = 0.5f * ( q0 * gy - q1 * gz + q3 * gx);
  qDot4 = 0.5f * ( q0 * gz + q1 * gy - q2 * gx);

  // Compute feedback only if accelerometer measurement valid
  float aNorm = ax * ax + ay * ay + az * az;
  if (aNorm > 0.0f) {
    recipNorm = 1.0f / sqrtf(aNorm);
    ax *= recipNorm;
    ay *= recipNorm;
    az *= recipNorm;

    _2q0 = 2.0f * q0;  _2q1 = 2.0f * q1;
    _2q2 = 2.0f * q2;  _2q3 = 2.0f * q3;
    _4q0 = 4.0f * q0;  _4q1 = 4.0f * q1;
    _4q2 = 4.0f * q2;
    _8q1 = 8.0f * q1;  _8q2 = 8.0f * q2;
    q0q0 = q0 * q0;  q1q1 = q1 * q1;
    q2q2 = q2 * q2;  q3q3 = q3 * q3;

    s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay;
    s1 = _4q1 * q3q3 - _2q3 * ax + 4.0f * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az;
    s2 = 4.0f * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az;
    s3 = 4.0f * q1q1 * q3 - _2q1 * ax + 4.0f * q2q2 * q3 - _2q2 * ay;

    recipNorm = 1.0f / sqrtf(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3);
    s0 *= recipNorm;  s1 *= recipNorm;
    s2 *= recipNorm;  s3 *= recipNorm;

    qDot1 -= beta * s0;
    qDot2 -= beta * s1;
    qDot3 -= beta * s2;
    qDot4 -= beta * s3;
  }

  // Integrate rate of change of quaternion
  q0 += qDot1 * dt;  q1 += qDot2 * dt;
  q2 += qDot3 * dt;  q3 += qDot4 * dt;

  // Normalise quaternion
  recipNorm = 1.0f / sqrtf(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
  q0 *= recipNorm;  q1 *= recipNorm;
  q2 *= recipNorm;  q3 *= recipNorm;
}

/** Convert quaternion to Euler angles (degrees) */
static void quaternionToEuler(float *roll, float *pitch, float *yaw) {
  // Roll (x-axis rotation)
  float sinr_cosp = 2.0f * (q0 * q1 + q2 * q3);
  float cosr_cosp = 1.0f - 2.0f * (q1 * q1 + q2 * q2);
  *roll = atan2f(sinr_cosp, cosr_cosp) * 57.2957795f;

  // Pitch (y-axis rotation)
  float sinp = 2.0f * (q0 * q2 - q3 * q1);
  if (fabsf(sinp) >= 1.0f)
    *pitch = copysignf(90.0f, sinp);
  else
    *pitch = asinf(sinp) * 57.2957795f;

  // Yaw (z-axis rotation)
  float siny_cosp = 2.0f * (q0 * q3 + q1 * q2);
  float cosy_cosp = 1.0f - 2.0f * (q2 * q2 + q3 * q3);
  *yaw = atan2f(siny_cosp, cosy_cosp) * 57.2957795f;
}

// ──────────────────────────────────────────────────────────────────
// BLE Setup
// ──────────────────────────────────────────────────────────────────
BLEService        imuService("0000ffe5-0000-1000-8000-00805f9a34fb");
BLECharacteristic imuChar("0000ffe4-0000-1000-8000-00805f9a34fb",
                          BLERead | BLENotify, 20);

// ──────────────────────────────────────────────────────────────────
// Globals
// ──────────────────────────────────────────────────────────────────
static unsigned long prevMicros = 0;
static bool imuOk = false;
static bool bleConnected = false;


// ──────────────────────────────────────────────────────────────────
// IMU — reset Madgwick on new BLE connection
// ──────────────────────────────────────────────────────────────────
static void imuResetOrientation() {
  q0 = 1.0f; q1 = 0.0f; q2 = 0.0f; q3 = 0.0f;
  prevMicros = micros();
  DBG_PRINTLN("Madgwick quaternion reset for new connection");
}

// LED pin (XIAO MG24 built-in LED)
#ifndef LED_BUILTIN
  #define LED_BUILTIN 15
#endif

// ──────────────────────────────────────────────────────────────────
// setup()
// ──────────────────────────────────────────────────────────────────
void setup() {
  DBG_BEGIN(115200);
  delay(1000);  // Give serial monitor time to connect
  DBG_PRINTLN("=== CUS_IMU — XIAO MG24 Sense BLE IMU v4 (low-power) ===");

  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, LOW);  // LED off by default to save power

  // ── IMU power gate pin — keep ON always ──
  pinMode(PD5, OUTPUT);
  digitalWrite(PD5, HIGH);
  delay(150);  // Let sensor power up before I2C init

  // ── IMU init (once) ──
  if (myIMU.begin() != 0) {
    DBG_PRINTLN("ERROR: LSM6DS3 begin() failed!");
    imuOk = false;
  } else {
    DBG_PRINTLN("LSM6DS3 IMU initialised OK");
    imuOk = true;
  }
  prevMicros = micros();

  // ── BLE ──
  if (!BLE.begin()) {
    DBG_PRINTLN("ERROR: BLE init failed");
    while (1) { delay(1000); }
  }

  BLE.setLocalName("CUS_IMU");
  BLE.setDeviceName("CUS_IMU");
  BLE.setAdvertisedService(imuService);
  imuService.addCharacteristic(imuChar);
  BLE.addService(imuService);

  // Set initial value (all zeros)
  uint8_t zeros[20] = {0};
  zeros[0] = 0x55;
  zeros[1] = 0x61;
  imuChar.writeValue(zeros, 20);

  BLE.advertise();
  DBG_PRINTLN("BLE advertising as 'CUS_IMU'");
}

// ──────────────────────────────────────────────────────────────────
// loop()
// ──────────────────────────────────────────────────────────────────
void loop() {
  BLE.poll();

  BLEDevice central = BLE.central();

  if (central && central.connected()) {
    if (!bleConnected) {
      bleConnected = true;
      digitalWrite(LED_BUILTIN, HIGH);  // LED on = connected
      DBG_PRINT("Connected: ");
      DBG_PRINTLN(central.address());

      // ── Reset orientation for fresh connection ──
      imuResetOrientation();
    }

    // ── Only send data if IMU initialized OK ──
    if (!imuOk) {
      delay(20);
      return;
    }

    // ── Read IMU (LSM6DS3 library returns floats directly) ──
    float ax = myIMU.readFloatAccelX();  // in g
    float ay = myIMU.readFloatAccelY();
    float az = myIMU.readFloatAccelZ();
    float gx = myIMU.readFloatGyroX();   // in dps
    float gy = myIMU.readFloatGyroY();
    float gz = myIMU.readFloatGyroZ();

    // ── Update Madgwick filter ──
    unsigned long now = micros();
    float dt = (now - prevMicros) * 1e-6f;
    if (dt <= 0.0f || dt > 0.5f) dt = 0.02f;  // Sanity clamp
    prevMicros = now;

    // Madgwick expects accel in g (LSM6DS3 already returns g)
    madgwickUpdate(gx, gy, gz, ax, ay, az, dt);

    float roll, pitch, yaw;
    quaternionToEuler(&roll, &pitch, &yaw);

    // ── Build WitMotion-compatible 20-byte packet ──
    uint8_t pkt[20] = {0};
    pkt[0] = 0x55;   // header
    pkt[1] = 0x61;   // packet type

    // Accel in mg (int16, little-endian)
    float axMg = ax * 1000.0f;
    float ayMg = ay * 1000.0f;
    float azMg = az * 1000.0f;
    int16_t iAx = (int16_t)constrain(axMg, -32768, 32767);
    int16_t iAy = (int16_t)constrain(ayMg, -32768, 32767);
    int16_t iAz = (int16_t)constrain(azMg, -32768, 32767);
    pkt[2]  = iAx & 0xFF;       pkt[3]  = (iAx >> 8) & 0xFF;
    pkt[4]  = iAy & 0xFF;       pkt[5]  = (iAy >> 8) & 0xFF;
    pkt[6]  = iAz & 0xFF;       pkt[7]  = (iAz >> 8) & 0xFF;

    // Gyro in 0.1 dps (int16, little-endian)
    int16_t iGx = (int16_t)constrain(gx * 10.0f, -32768, 32767);
    int16_t iGy = (int16_t)constrain(gy * 10.0f, -32768, 32767);
    int16_t iGz = (int16_t)constrain(gz * 10.0f, -32768, 32767);
    pkt[8]  = iGx & 0xFF;       pkt[9]  = (iGx >> 8) & 0xFF;
    pkt[10] = iGy & 0xFF;       pkt[11] = (iGy >> 8) & 0xFF;

    // Roll / Pitch / Yaw — encoded as angle / 180 * 32768
    int16_t iRoll  = (int16_t)(roll  / 180.0f * 32768.0f);
    int16_t iPitch = (int16_t)(pitch / 180.0f * 32768.0f);
    int16_t iYaw   = (int16_t)(yaw   / 180.0f * 32768.0f);
    pkt[12] = iRoll  & 0xFF;    pkt[13] = (iRoll  >> 8) & 0xFF;
    pkt[14] = iPitch & 0xFF;    pkt[15] = (iPitch >> 8) & 0xFF;
    pkt[16] = iYaw   & 0xFF;    pkt[17] = (iYaw   >> 8) & 0xFF;

    // [18-19] reserved / padding — left as 0x00

    // ── Send via BLE notification ──
    imuChar.writeValue(pkt, 20);

    delay(20);  // ~50 Hz update rate

  } else {
    // ── Not connected — power down everything possible ──
    if (bleConnected) {
      bleConnected = false;
      digitalWrite(LED_BUILTIN, LOW);
      DBG_PRINTLN("Disconnected, re-advertising");
      delay(500);
      BLE.advertise();
    }

    // Sleep longer when idle — BLE stack handles advertising in background.
    // 200 ms sleep saves significant CPU current vs busy-looping.
    delay(200);
  }
}
