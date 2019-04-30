package com.permobil.psds.wearos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.kinvey.android.Client;
import com.kinvey.android.store.DataStore;
import com.kinvey.android.sync.KinveyPushResponse;
import com.kinvey.android.sync.KinveySyncCallback;
import com.kinvey.java.KinveyException;
import com.kinvey.java.core.KinveyClientCallback;
import com.kinvey.java.model.KinveyPullResponse;
import com.kinvey.java.store.StoreType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;


public class SensorService extends Service {

    private static final String TAG = "PermobilSensorService";

    private Client mKinveyClient;
    private DataStore<PSDSData> psdsDataStore;
    private Handler mHandler;
    private String sdCardPath;
    private WakeLock mWakeLock;
    private String userIdentifier;
    private String deviceUUID;
    private FileWriter writer;
    private SensorManager mSensorManager;
    private LocationManager mLocationManager;
    private Runnable mHandlerTask;
    private SensorEventListener mListener;

//    private String dataFileName = sdCardPath + "/permobil_sensor_data.txt";
//    private File dataFile = new File(dataFileName);

    private Sensor mLinearAcceleration;
    private Sensor mGravity;
    private Sensor mMagneticField;
    private Sensor mRotationVector;
    private Sensor mGameRotationVector;
    private Sensor mGyroscope;
    private Sensor mProximity;
    private Sensor mOffBodyDetect;

    public static ArrayList<SensorServiceData> sensorServiceDataList = new ArrayList<>();

    public SensorService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SensorService onCreate...");
        @SuppressLint("HardwareIds")
        String uuid = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );
        this.deviceUUID = uuid;
        this.mHandler = new Handler();
        this.sdCardPath = Environment.getExternalStorageDirectory().getPath();

        this.mKinveyClient = ((App) getApplication()).getSharedClient();
        Log.d(TAG, "Kinvey Client from App.java found and set in service.");

        // Get the Kinvey Data Collection for storing data
        this.psdsDataStore = DataStore.collection("PSDSData", PSDSData.class, StoreType.SYNC, mKinveyClient);
        Log.d(TAG, "PSDSDataStore: " + this.psdsDataStore.getCollectionName());

        // Get the LocationManager so we can send last known location with the record when saving to Kinvey
        mLocationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        Log.d(TAG, "Location Manager: " + mLocationManager);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int sensorDelay;
        int maxReportingLatency;
        // the intent that starts the service can pass the sensor delay and Max Reporting Latency
        Bundle extras = intent.getExtras();
        if (extras != null) {
            // check for sensor delay from intent
            int delay = extras.getInt(Constants.SENSOR_DELAY, 0);
            sensorDelay = delay != 0 ? delay : SensorManager.SENSOR_DELAY_UI;
            // check for reporting delay
            int reportingDelay = extras.getInt(Constants.MAX_REPORTING_DELAY, 0);
            maxReportingLatency = reportingDelay != 0 ? reportingDelay : 50000000;
            String savedStudyId = getSharedPreferences("com.permobil.psds.wearos", Context.MODE_PRIVATE).getString(Constants.SAVED_STUDY_ID, "");
            userIdentifier = savedStudyId;
        } else {
            sensorDelay = SensorManager.SENSOR_DELAY_UI;
            maxReportingLatency = 50000000;
            userIdentifier = null;
        }

        try {
            writer = new FileWriter(new File(sdCardPath, "permobil_sensors_" + System.currentTimeMillis() + ".txt"));
            Log.d(TAG, "New FileWriter: " + writer.toString());
        } catch (IOException e) {
            Log.e(TAG, "Error creating new FileWriter: " + e.getMessage());
            e.printStackTrace();
        }

        // Handle wake_lock so data collection can continue even when screen turns off
        // without wake_lock the service will stop bc the CPU gives up
        this._handleWakeLockSetup();

        boolean didRegisterSensors = this._registerDeviceSensors(sensorDelay, maxReportingLatency);
        Log.d(TAG, "Did register Sensors: " + didRegisterSensors);

        mHandlerTask = new Runnable() {
            @Override
            public void run() {
                _writeAndUpload();
                mHandler.postDelayed(mHandlerTask, 60 * 1000);
            }
        };

        mHandlerTask.run();

        return START_STICKY; // START_STICKY is used for services that are explicitly started and stopped as needed
    }

    private void _writeAndUpload() {
//        try {
//            writer.write(String.valueOf(SensorService.sensorServiceDataList));
//            Log.d(TAG, "Wrote the sensor data to file.");
//        } catch (IOException e) {
//            System.out.println("Exception");
//        }
        Log.d(TAG, "_writeAndUpload()...");
        PSDSData data = new PSDSData();
        Log.d(TAG, "new PSDSData created: " + data);
        data.device_uuid = this.deviceUUID;

        // BRAD - thinking that the GC messages are about the static arraylist and retaining sensor data
        Log.d(TAG, "ServiceDataList size: " + sensorServiceDataList.size());
        data.sensor_data = SensorService.sensorServiceDataList;
        Log.d(TAG, "PSDSData sensor_data values: " + data.sensor_data);
        SensorService.sensorServiceDataList = new ArrayList<>();
//        data.sensor_list = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        data.user_identifier = this.userIdentifier;

        // if we have location permission write the location to record, if not, just print WARNING to LogCat, not sure on best handling for UX right now.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Unable to get device location because LOCATION permission has not been granted.");
            data.location = null;
        } else {
            Location loc = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc != null) {
                PSDSLocation psdsloc = new PSDSLocation(loc.getLatitude(), loc.getLongitude(), loc.getTime());
                data.location = psdsloc;
            } else {
                data.location = null;
            }
            Log.d(TAG, "Data location: " + data.location);
        }

        try {
            psdsDataStore.save(data, new KinveyClientCallback<PSDSData>() {
                @Override
                public void onSuccess(PSDSData result) {
                    Log.d(TAG, "Entity saved to client: " + result);
                    psdsDataStore.sync(new KinveySyncCallback() {
                        @Override
                        public void onSuccess(KinveyPushResponse kinveyPushResponse, KinveyPullResponse kinveyPullResponse) {

                        }

                        @Override
                        public void onPullStarted() {

                        }

                        @Override
                        public void onPushStarted() {

                        }

                        @Override
                        public void onPullSuccess(KinveyPullResponse kinveyPullResponse) {

                        }

                        @Override
                        public void onPushSuccess(KinveyPushResponse kinveyPushResponse) {
                            Log.d(TAG, "Data pushed to Kinvey successfully. Check kinvey console.");
                            sendMessageToActivity("Data service syncing data to backend successfully.");
                        }

                        @Override
                        public void onFailure(Throwable throwable) {
                            Log.e(TAG, "Kinvey push failure message" +
                                    ": " + throwable.getMessage());
                            Log.e(TAG, "Kinvey push failure cause: " + throwable.getCause());
                            sendMessageToActivity(throwable.getMessage());

                        }
                    });
                }

                @Override
                public void onFailure(Throwable error) {
                    Log.e(TAG, "Failed to save to Kinvey: " + error.getMessage());
                }
            });
        } catch (KinveyException ke) {
            Log.e(TAG, "Error saving kinvey record for sensor data. " + ke.getReason());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (this.mWakeLock != null) {
            this.mWakeLock.release();
        }
        if (writer != null) {
            try {
                Log.d(TAG, "Closing the file writer...");
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing FileWriter: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // remove handler tasks
        mHandler.removeCallbacks(mHandlerTask);
    }

    public class SensorListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mListener != null) {
                HashMap<String, Float> sensorData = new HashMap<>();

                int sensorType = event.sensor.getType();
                // depending on the the sensor type set the result to return in the listener
                if (sensorType == Sensor.TYPE_ACCELEROMETER
                        || sensorType == Sensor.TYPE_LINEAR_ACCELERATION
                        || sensorType == Sensor.TYPE_GRAVITY
                        || sensorType == Sensor.TYPE_GYROSCOPE
                        || sensorType == Sensor.TYPE_MAGNETIC_FIELD) {
                    sensorData.put("x", event.values[0]);
                    sensorData.put("y", event.values[1]);
                    sensorData.put("z", event.values[2]);
                } else if (sensorType == Sensor.TYPE_ROTATION_VECTOR) {
                    sensorData.put("x", event.values[0]);
                    sensorData.put("y", event.values[1]);
                    sensorData.put("z", event.values[2]);
                    sensorData.put("cos", event.values[3]);
                    sensorData.put("heading_accuracy", event.values[4]);
                } else if (sensorType == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    sensorData.put("x", event.values[0]);
                    sensorData.put("y", event.values[1]);
                    sensorData.put("z", event.values[2]);
                    sensorData.put("cos", event.values[3]);
                } else if (sensorType == Sensor.TYPE_STATIONARY_DETECT) {
                    sensorData.put("stationary", event.values[0]);
                } else if (sensorType == Sensor.TYPE_PROXIMITY) {
                    sensorData.put("proximity", event.values[0]);
                } else if (sensorType == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
                    sensorData.put("state", event.values[0]);
                } else if (sensorType == Sensor.TYPE_HEART_RATE) {
                    sensorData.put("heart_rate", event.values[0]);
                }

                // create new SensorServiceData
                SensorServiceData data = new SensorServiceData(
                        event.sensor.getType(),
                        event.timestamp,
                        sensorData
                );

                SensorService.sensorServiceDataList.add(data);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // TODO Auto-generated method stub
        }

    }

    private boolean _registerDeviceSensors(int delay, int reportingLatency) {
        mSensorManager = (SensorManager) getApplicationContext()
                .getSystemService(SENSOR_SERVICE);
        // make sure we have the sensor manager for the device
        if (mSensorManager != null) {
            Log.d(TAG, "Sensor Manager: " + mSensorManager.toString());

            Log.d(TAG, "Creating sensor listener...");
            mListener = new SensorListener();

            // register all the sensors we want to track data for
            mLinearAcceleration = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (mLinearAcceleration != null)
                mSensorManager.registerListener(mListener, mLinearAcceleration, delay, reportingLatency);

            mGravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (mGravity != null)
                mSensorManager.registerListener(mListener, mGravity, SensorManager.SENSOR_DELAY_NORMAL, 50000000);

            mMagneticField = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mMagneticField != null)
                mSensorManager.registerListener(mListener, mMagneticField, delay, reportingLatency);

            mRotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (mRotationVector != null)
                mSensorManager.registerListener(mListener, mRotationVector, delay, reportingLatency);

            mGameRotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (mGameRotationVector != null)
                mSensorManager.registerListener(mListener, mGameRotationVector, delay, reportingLatency);

            mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (mGyroscope != null)
                mSensorManager.registerListener(mListener, mGyroscope, delay, reportingLatency);

            mProximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (mProximity != null)
                mSensorManager.registerListener(mListener, mProximity, delay, reportingLatency);

            mOffBodyDetect = mSensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
            if (mOffBodyDetect != null)
                mSensorManager.registerListener(mListener, mOffBodyDetect, delay, reportingLatency);
        } else {
            Log.e(TAG, "Sensor Manager was not found, so sensor service is unable to register sensor listener events.");
        }

        return true;
    }

    private void _handleWakeLockSetup() {
        PowerManager mgr = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PermobilWear:SensorServiceWakeLock");
        mWakeLock.acquire();
        Log.d(TAG, "PermobilWear:SensorServiceWakeLock has been acquired.");
    }

    private void sendMessageToActivity(String msg) {
        Intent intent = new Intent(Constants.SENSOR_SERVICE_MESSAGE_INTENT_KEY);
        // You can also include some extra data.
        intent.putExtra(Constants.SENSOR_SERVICE_MESSAGE, msg);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

}


