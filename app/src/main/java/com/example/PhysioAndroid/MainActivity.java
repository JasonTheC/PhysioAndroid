package com.example.PhysioAndroid;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ToggleButton;

import com.example.physioandroid.R;


public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE = 100;
    private static final String PREFS_NAME = "physio_prefs";
    private ToggleButton toggleButton;
    private String chosenProbe;
    private EditText editPatientName;
    private EditText editStudyNotes;
    private Spinner imuSpinner;
    private final String[] imuOptions = {"XIAO MG24 Sense (CUS_IMU)", "WitMotion BWT901", "None (disabled)"};
    private final String[] imuValues  = {"cus_imu",                    "witmotion",        "none"};

    

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
        ArrayAdapter<String> imuAdapter = new ArrayAdapter<>(this,
                R.layout.spinner_item_white, imuOptions);
        imuAdapter.setDropDownViewResource(R.layout.spinner_dropdown_black);
        imuSpinner.setAdapter(imuAdapter);

        // Restore saved IMU selection
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedImuType = prefs.getString("imu_type", "cus_imu");
        for (int idx = 0; idx < imuValues.length; idx++) {
            if (imuValues[idx].equals(savedImuType)) {
                imuSpinner.setSelection(idx);
                break;
            }
        }

        // Save IMU selection whenever it changes
        imuSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString("imu_type", imuValues[position])
                    .apply();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
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
                startProjection();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // If BLE permissions were just granted, kick off the IMU scan if the service is running
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean bleGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (bleGranted) {
                startService(ScreenCaptureService.getRetryImuIntent(this));
            }
        }
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


}