package com.example.PhysioAndroid;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.example.physioandroid.R;

import java.util.ArrayList;


public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE = 100;
    private static final String PREFS_NAME = "physio_prefs";
    private static final String PREF_IMU_TYPE = "imu_type";
    private static final String PREF_IMU_SELECTED_MAC = "imu_selected_mac";
    private static final String PREF_IMU_SELECTED_NAME = "imu_selected_name";
    private static final long BLE_SCAN_DURATION_MS = 10000L;
    private ToggleButton toggleButton;
    private String chosenProbe;
    private EditText editPatientName;
    private EditText editStudyNotes;
    private Spinner imuSpinner;
    private Spinner imuDeviceSpinner;
    private Button scanImuDevicesButton;
    private EditText editImuBleName;
    private ArrayAdapter<String> imuDeviceAdapter;
    private final ArrayList<BluetoothDevice> discoveredBleDevices = new ArrayList<>();
    private final ArrayList<String> discoveredBleDeviceLabels = new ArrayList<>();
    private final Handler bleHandler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean bleScanInProgress = false;
    private final String[] imuOptions = {"XIAO (CUS_IMU)", "WitMotion", "None"};
    private final String[] imuValues  = {"cus_imu",          "witmotion", "none"};

    

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Build the permission list — include BLE runtime permissions on Android 12+
        String[] permissionArr;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionArr = new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            permissionArr = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        }

        if (!Settings.canDrawOverlays(this))  {

            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                return;
            }

            Intent myIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            myIntent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(myIntent, 1);
        }


        // Check whether ANY of the required permissions are still missing and request them all at once
        boolean permissionsMissing = false;
        for (String perm : permissionArr) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsMissing = true;
                break;
            }
        }
        if (permissionsMissing) {
            ActivityCompat.requestPermissions(this, permissionArr, 1);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 0);
            }
        }



        // start projection
        Button shoulderButton = findViewById(R.id.shoulderButton);
        Button abdoButton = findViewById(R.id.abdoButton);
        Button quadButton = findViewById(R.id.quadButton);

        setButtonClickListener(shoulderButton, "Shoulder");
        setButtonClickListener(abdoButton, "Abdominal");
        setButtonClickListener(quadButton, "Quadriceps");

        

        editPatientName = findViewById(R.id.editPatientName);
        editStudyNotes = findViewById(R.id.editStudyNotes);

        toggleButton = findViewById(R.id.toggleProbe);
        toggleButton.setOnCheckedChangeListener((view,  isChecked) -> {
            if (isChecked) {
                chosenProbe = "Butterfly";
            } else {
                chosenProbe = "UProbe";

            }

        });

        // IMU Sensor selector
        imuSpinner = findViewById(R.id.imuSpinner);
        imuDeviceSpinner = findViewById(R.id.imuDeviceSpinner);
        scanImuDevicesButton = findViewById(R.id.scanImuDevicesButton);
        editImuBleName = findViewById(R.id.editImuBleName);

        ArrayAdapter<String> imuAdapter = new ArrayAdapter<>(this,
                R.layout.spinner_item_white, imuOptions);
        imuAdapter.setDropDownViewResource(R.layout.spinner_dropdown_black);
        imuSpinner.setAdapter(imuAdapter);

        imuDeviceAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_white,
                new ArrayList<String>());
        imuDeviceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_black);
        imuDeviceSpinner.setAdapter(imuDeviceAdapter);
        showNoDevicesLabel();

        // Restore saved IMU selection
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedImuType = prefs.getString(PREF_IMU_TYPE, "cus_imu");
        for (int idx = 0; idx < imuValues.length; idx++) {
            if (imuValues[idx].equals(savedImuType)) {
                imuSpinner.setSelection(idx);
                break;
            }
        }
        // Restore saved BLE name
        String savedBleName = prefs.getString("imu_ble_name", "");
        if (savedBleName != null && !savedBleName.trim().isEmpty()) {
            editImuBleName.setText(savedBleName);
        }

        // Auto-save BLE name whenever the user edits it
        editImuBleName.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString("imu_ble_name", s.toString().trim())
                        .apply();
            }
        });

        // Save IMU selection whenever it changes
        imuSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_IMU_TYPE, imuValues[position])
                    .apply();
                // Set a sensible default BLE name when switching type (only if field is empty)
                String currentName = editImuBleName.getText().toString().trim();
                if (currentName.isEmpty()) {
                    if ("cus_imu".equals(imuValues[position])) {
                        editImuBleName.setText("CUS_IMU");
                    } else if ("witmotion".equals(imuValues[position])) {
                        editImuBleName.setText("WT901BLE");
                    }
                }
                updateImuDeviceControls();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        imuDeviceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= discoveredBleDevices.size()) return;
                BluetoothDevice device = discoveredBleDevices.get(position);
                if (device == null || device.getAddress() == null) return;
                String name = (device.getName() == null || device.getName().trim().isEmpty())
                        ? "Unknown"
                        : device.getName().trim();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_IMU_SELECTED_MAC, device.getAddress())
                        .putString(PREF_IMU_SELECTED_NAME, name)
                        .apply();
                // Auto-fill the BLE name field from the selected device
                editImuBleName.setText(name);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        scanImuDevicesButton.setOnClickListener(v -> startBleDeviceScan());
        updateImuDeviceControls();
    }

    private void setButtonClickListener(Button button, String bodyPart) {
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ScreenCaptureService.bodyPart = bodyPart;
                ScreenCaptureService.orientation = "Transverse";
                ScreenCaptureService.patientName = editPatientName.getText().toString().trim();
                ScreenCaptureService.studyNotes = editStudyNotes.getText().toString().trim();
                ScreenCaptureService.startFan = System.currentTimeMillis();
                // Save BLE name so the service can read it on start
                String bleName = editImuBleName.getText().toString().trim();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString("imu_ble_name", bleName)
                        .apply();
                startProjection();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // If BLE permissions were just granted, kick off the IMU scan if the service is running
        if (hasBleScanPermissions()) {
            startService(ScreenCaptureService.getRetryImuIntent(this));
            if (isImuEnabled()) {
                startBleDeviceScan();
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopBleDeviceScan();
        bleHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                startService(ScreenCaptureService.getStartIntent(this, resultCode, data));
            }
        }
        minimizeApp();
    }

    /****************************************** UI Widget Callbacks *******************************/
    private void startProjection() {

        MediaProjectionManager mProjectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);


    }
    public void closeApp(View view){
        ScreenCaptureService.closeApp = true;
    }
    private void stopProjection() {

        startService(com.example.PhysioAndroid.ScreenCaptureService.getStopIntent(this));
    }
    private void minimizeApp() {
        Intent startMain = new Intent(Intent.ACTION_MAIN);
        startMain.addCategory(Intent.CATEGORY_HOME);
        startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent launchIntent;
        if (chosenProbe == "Butterfly") {
             launchIntent = getPackageManager().getLaunchIntentForPackage("com.butterflynetinc.helios");
        }else{
             launchIntent = getPackageManager().getLaunchIntentForPackage("com.healson.uprobe.export");
        }
        if (launchIntent != null) {
            startActivity(launchIntent);//null pointer check in case package name was not found
        }else{
            Log.e("test",String.format("Can't find  %s", chosenProbe));
        }


    }

    private void startBleDeviceScan() {
        if (!isImuEnabled()) {
            android.widget.Toast.makeText(this, "Enable an IMU type first", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasBleScanPermissions()) {
            requestBlePermissions();
            return;
        }
        if (bleScanInProgress) return;

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null || bluetoothManager.getAdapter() == null) {
            android.widget.Toast.makeText(this, "Bluetooth adapter unavailable", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (!adapter.isEnabled()) {
            android.widget.Toast.makeText(this, "Enable Bluetooth and scan again", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        bluetoothLeScanner = adapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            android.widget.Toast.makeText(this, "BLE scanner unavailable", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        discoveredBleDevices.clear();
        discoveredBleDeviceLabels.clear();
        imuDeviceAdapter.clear();
        imuDeviceAdapter.add("Scanning...");
        imuDeviceAdapter.notifyDataSetChanged();

        bleScanInProgress = true;
        scanImuDevicesButton.setText("Scanning...");

        bluetoothLeScanner.startScan(deviceScanCallback);
        bleHandler.postDelayed(this::stopBleDeviceScan, BLE_SCAN_DURATION_MS);
    }

    private void stopBleDeviceScan() {
        if (!bleScanInProgress) return;
        bleScanInProgress = false;
        scanImuDevicesButton.setText("Scan devices");
        try {
            if (bluetoothLeScanner != null) {
                bluetoothLeScanner.stopScan(deviceScanCallback);
            }
        } catch (Exception ignored) {}

        if (discoveredBleDeviceLabels.isEmpty()) {
            showNoDevicesLabel();
            android.widget.Toast.makeText(this, "No BLE devices found", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            imuDeviceAdapter.clear();
            imuDeviceAdapter.addAll(discoveredBleDeviceLabels);
            imuDeviceAdapter.notifyDataSetChanged();
            restoreSavedDeviceSelection();
        }
    }

    private void showNoDevicesLabel() {
        imuDeviceAdapter.clear();
        imuDeviceAdapter.add("No devices found");
        imuDeviceAdapter.notifyDataSetChanged();
    }

    private void restoreSavedDeviceSelection() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedMac = prefs.getString(PREF_IMU_SELECTED_MAC, "");
        if (savedMac == null || savedMac.trim().isEmpty()) return;

        for (int idx = 0; idx < discoveredBleDevices.size(); idx++) {
            BluetoothDevice device = discoveredBleDevices.get(idx);
            if (device != null && savedMac.equalsIgnoreCase(device.getAddress())) {
                imuDeviceSpinner.setSelection(idx);
                return;
            }
        }
    }

    private String formatDeviceLabel(BluetoothDevice device) {
        String name = (device.getName() == null || device.getName().trim().isEmpty())
                ? "Unknown"
                : device.getName().trim();
        String mac = device.getAddress() == null ? "no-mac" : device.getAddress();
        return name + " (" + mac + ")";
    }

    private boolean hasBleScanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, 1);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1);
        }
    }

    private boolean isImuEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String imuType = prefs.getString(PREF_IMU_TYPE, "cus_imu");
        return !"none".equals(imuType);
    }

    private void updateImuDeviceControls() {
        boolean enable = isImuEnabled();
        imuDeviceSpinner.setEnabled(enable);
        scanImuDevicesButton.setEnabled(enable);
        editImuBleName.setEnabled(enable);
        if (!enable) {
            editImuBleName.setText("");
            editImuBleName.setHint("IMU disabled");
            showNoDevicesLabel();
        } else {
            editImuBleName.setHint("BLE name e.g. WT901BLE");
        }
    }

    private final ScanCallback deviceScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null || device.getAddress() == null) return;

            runOnUiThread(() -> {
                for (BluetoothDevice existing : discoveredBleDevices) {
                    if (existing != null && device.getAddress().equalsIgnoreCase(existing.getAddress())) {
                        return;
                    }
                }
                discoveredBleDevices.add(device);
                discoveredBleDeviceLabels.add(formatDeviceLabel(device));
                imuDeviceAdapter.clear();
                imuDeviceAdapter.addAll(discoveredBleDeviceLabels);
                imuDeviceAdapter.notifyDataSetChanged();
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                stopBleDeviceScan();
                android.widget.Toast.makeText(MainActivity.this, "Scan failed (" + errorCode + ")", android.widget.Toast.LENGTH_SHORT).show();
            });
        }
    };


}