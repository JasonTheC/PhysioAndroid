r        }).start();
    }

    /**
     * Clean up old scans (older than 7 days) to prevent storage from filling up
     * Keep recent scans in case upload failed and needs retry
     */
    private void cleanupOldScans() {
        new Thread(() -> {
            try {
                if (storageRoot == null || !storageRoot.exists()) {
                    return;
                }
                
                long now = System.currentTimeMillis();
                long sevenDaysAgo = now - (7L * 24 * 60 * 60 * 1000);
                int deletedScans = 0;
                
                android.util.Log.d("ScanActivity", "Cleaning up scans older than 7 days from storage");
                
                // Walk through storage/{email}/{datetime}/{uuid}/ structure
                File[] emailDirs = storageRoot.listFiles();
                if (emailDirs != null) {
                    for (File emailDir : emailDirs) {
                        if (!emailDir.isDirectory()) continue;
                        
                        File[] dateTimeDirs = emailDir.listFiles();
                        if (dateTimeDirs != null) {
                            for (File dateTimeDir : dateTimeDirs) {
                                if (!dateTimeDir.isDirectory()) continue;
                                
                                // Check if this scan is older than 7 days
                                if (dateTimeDir.lastModified() < sevenDaysAgo) {
                                    deleteRecursive(dateTimeDir);
                                    deletedScans++;
                                    android.util.Log.d("ScanActivity", "Deleted old scan: " + dateTimeDir.getName());
                                }
                            }
                            
                            // Remove empty email directories
                            if (emailDir.listFiles() == null || emailDir.listFiles().length == 0) {
                                emailDir.delete();
                            }
                        }
                    }
                }
                
                if (deletedScans > 0) {
                    android.util.Log.d("ScanActivity", "Cleanup complete - deleted " + deletedScans + " old scans");
                }
            } catch (Exception e) {
                android.util.Log.e("ScanActivity", "Error during cleanup", e);
            }
        }).start();
    }
    
    /**
     * Recursively delete a directory and all its contents
     */
    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    // ==================== WitMotion BLE IMU ====================

    private void requestBlePermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT at runtime
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_BLE_PERMISSIONS);
                return;
            }
        } else {
            // Pre-Android 12 needs location for BLE scanning
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_BLE_PERMISSIONS);
                return;
            }
        }
        startIMUScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startIMUScan();
            } else {
                android.util.Log.w("ScanActivity", "BLE permissions denied - IMU will not be available");
                Toast.makeText(this, "Bluetooth permissions denied - IMU disabled", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startIMUScan() {
        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = btManager.getAdapter();
        bluetoothAdapter.getBluetoothLeScanner().startScan(imuScanCallback);
        android.util.Log.d("ScanActivity", "Started BLE scan for WitMotion IMU");
    }

    private final ScanCallback imuScanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (!imuConnected && IMU_MAC_ADDRESS.equals(device.getAddress())) {
                imuConnected = true;
                bluetoothAdapter.getBluetoothLeScanner().stopScan(imuScanCallback);
                bluetoothGatt = device.connectGatt(ScanActivity.this, false, imuGattCallback);
                android.util.Log.d("ScanActivity", "Connecting to WitMotion IMU");
            }
        }
    };

    private final BluetoothGattCallback imuGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
                android.util.Log.d("ScanActivity", "IMU connected, discovering services");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                imuConnected = false;
                gatt.close();
                android.util.Log.d("ScanActivity", "IMU disconnected");
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattCharacteristic characteristic = gatt
                    .getService(UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb"))
                    .getCharacteristic(UUID.fromString("0000ffe4-0000-1000-8000-00805f9a34fb"));
                gatt.setCharacteristicNotification(characteristic, true);
                BluetoothGattDescriptor desc = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(desc);
                android.util.Log.d("ScanActivity", "IMU notifications enabled");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            int i = 2;
            imuRoll = (float) (((data[i + 11]) << 8) | ((data[i + 10]) & 255)) / 32768 * 180;
            imuPitch = (float) (((data[i + 13]) << 8) | ((data[i + 12]) & 255)) / 32768 * 180;
            imuYaw = (float) (((data[i + 15]) << 8) | ((data[i + 14]) & 255)) / 32768 * 180;
        }
    };

    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {
        stopCapture();
        stopAIGuidance();
        
        // Disconnect BLE IMU
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        
        // Clean up TFLite resources
        if (tfliteInference != null) {
            tfliteInference.close();
            tfliteInference = null;
        }
        
        PostMessageGlobal.removeReceive(Define.KEY_GLOBAL_MESSAGE);
        super.onDestroy();
    }
}
