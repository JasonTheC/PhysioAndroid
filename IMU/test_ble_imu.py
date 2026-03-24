#!/usr/bin/env python3
"""
test_ble_imu.py — Connect to CUS_IMU (XIAO MG24 Sense) and print live IMU data.

Requirements:
    pip install bleak

Usage:
    python3 test_ble_imu.py
"""

import asyncio
import struct
import sys
from bleak import BleakScanner, BleakClient

DEVICE_NAME   = "CUS_IMU"
SERVICE_UUID  = "0000ffe5-0000-1000-8000-00805f9a34fb"
CHAR_UUID     = "0000ffe4-0000-1000-8000-00805f9a34fb"

HEADER        = 0x55
PKT_TYPE      = 0x61


def parse_packet(data: bytearray) -> dict | None:
    """
    Decode the 20-byte WitMotion-compatible packet.

    Packet layout (little-endian):
      [0]     0x55        header
      [1]     0x61        packet type
      [2-3]   AccX        int16  (mg)
      [4-5]   AccY        int16  (mg)
      [6-7]   AccZ        int16  (mg)
      [8-9]   GyroX       int16  (0.1 dps → divide by 10 for dps)
      [10-11] GyroY       int16  (0.1 dps)
      [12-13] Roll        int16  (angle / 180 * 32768)
      [14-15] Pitch       int16  (angle / 180 * 32768)
      [16-17] Yaw         int16  (angle / 180 * 32768)
      [18-19] reserved
    """
    if len(data) < 20:
        return None
    if data[0] != HEADER or data[1] != PKT_TYPE:
        return None

    ax, ay, az = struct.unpack_from("<hhh", data, 2)          # mg
    gx, gy     = struct.unpack_from("<hh",  data, 8)          # 0.1 dps
    roll, pitch, yaw = struct.unpack_from("<hhh", data, 12)   # encoded

    return {
        "acc_x_mg":   ax,
        "acc_y_mg":   ay,
        "acc_z_mg":   az,
        "gyro_x_dps": gx / 10.0,
        "gyro_y_dps": gy / 10.0,
        "roll_deg":   roll  / 32768.0 * 180.0,
        "pitch_deg":  pitch / 32768.0 * 180.0,
        "yaw_deg":    yaw   / 32768.0 * 180.0,
    }


pkt_count = 0

def notification_handler(sender, data: bytearray):
    global pkt_count
    pkt_count += 1

    # Print raw hex for first 5 packets and every 50th after that
    if pkt_count <= 5 or pkt_count % 50 == 0:
        print(f"[RAW #{pkt_count:4d}] ({len(data):2d}B) {data.hex(' ')}")

    parsed = parse_packet(data)
    if parsed is None:
        print(f"[WARN] bad packet ({len(data)} bytes): {data.hex()}")
        return

    print(
        f"Roll={parsed['roll_deg']:+7.2f}°  "
        f"Pitch={parsed['pitch_deg']:+7.2f}°  "
        f"Yaw={parsed['yaw_deg']:+8.2f}°  │  "
        f"Ax={parsed['acc_x_mg']:+6.0f}mg  "
        f"Ay={parsed['acc_y_mg']:+6.0f}mg  "
        f"Az={parsed['acc_z_mg']:+6.0f}mg  │  "
        f"Gx={parsed['gyro_x_dps']:+7.1f}°/s  "
        f"Gy={parsed['gyro_y_dps']:+7.1f}°/s"
    )


async def remove_from_bluez_cache(address: str):
    """
    Ask BlueZ to forget a device so stale GATT data doesn't cause
    'failed to discover services, device disconnected'.
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            "bluetoothctl", "remove", address,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        await asyncio.wait_for(proc.wait(), timeout=5)
    except (FileNotFoundError, asyncio.TimeoutError):
        pass  # bluetoothctl not installed or timed out — ignore


MAX_CONNECT_ATTEMPTS = 3


async def connect_with_retry(address: str) -> "BleakClient":
    """
    Attempt to connect with retries.  On the first failure we clear
    the BlueZ cache (stale GATT data is the #1 cause of the
    'failed to discover services, device disconnected' error).
    """
    last_err = None
    for attempt in range(1, MAX_CONNECT_ATTEMPTS + 1):
        try:
            # Use the MAC address string — passing the BLEDevice object
            # can carry stale BlueZ metadata that causes service-discovery
            # failures on reconnection.
            client = BleakClient(address, timeout=30.0)
            await client.connect()
            return client
        except Exception as e:
            last_err = e
            print(f"  [attempt {attempt}/{MAX_CONNECT_ATTEMPTS}] connect failed: {e}")
            # Clear BlueZ cache before retrying
            await remove_from_bluez_cache(address)
            await asyncio.sleep(2 * attempt)  # back off 2s, 4s, …

    raise last_err  # type: ignore[misc]


async def main():
    print(f"Scanning for '{DEVICE_NAME}'…  (Ctrl-C to quit)")

    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=10.0)
    if device is None:
        print(f"ERROR: '{DEVICE_NAME}' not found. Make sure the board is powered and advertising.")
        print("       If the Android phone is currently connected to the IMU, disconnect it first —")
        print("       CUS_IMU only supports one connection at a time.")
        sys.exit(1)

    print(f"Found: {device.name}  [{device.address}]")

    client = await connect_with_retry(device.address)
    try:
        print(f"Connected. Subscribing to notifications…\n")
        print(
            f"{'Roll':>10}  {'Pitch':>10}  {'Yaw':>10}  "
            f"{'AccX':>8}  {'AccY':>8}  {'AccZ':>8}  "
            f"{'GyroX':>9}  {'GyroY':>9}"
        )
        print("-" * 90)

        await client.start_notify(CHAR_UUID, notification_handler)

        try:
            while client.is_connected:
                await asyncio.sleep(1)
        except KeyboardInterrupt:
            pass
        finally:
            try:
                await client.stop_notify(CHAR_UUID)
            except Exception:
                pass
            print("\nDisconnected.")
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass


if __name__ == "__main__":
    asyncio.run(main())
