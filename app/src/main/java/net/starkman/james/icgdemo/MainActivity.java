package net.starkman.james.icgdemo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.Click;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.SystemService;
import org.androidannotations.annotations.ViewById;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cc.mvdan.accesspoint.WifiApControl;

@EActivity
public class MainActivity extends Activity implements SensorEventListener {
    private static final String TAG = "EECS398";
    private static final long THROTTLE_ACC_NS = 500_000_000;
    private static final int PORT_NUMBER = 3398;
    private static final Pattern REGEX_LENS_SSID_PSK = Pattern.compile("^\\$(\\d+)\\r\\n.*\\r\\n\\$(\\d+)\\r\\n.*\\r\\n$"); // "$6\r\nfoobar\r\n"
    private static final int MY_PERMISSIONS_REQUEST_WRITE_SETTINGS = 398;

    @SystemService
    protected SensorManager mSensorManager;
    /**
     * Not using annotation because needs getApplicationContext().
     */
    protected WifiManager mWifiManager;
    @ViewById(R.id.text_current)
    protected TextView mCurrentValue;
    private Sensor mSensorAcc = null;
    private long mLastSample = System.nanoTime();
    private Socket mSocket;
    private BufferedWriter mBufferedWriter;

    /**
     * http://stackoverflow.com/a/35833800
     */
    public static float roundToDigits(float number, int scale) {
        int pow = 10;
        for (int i = 1; i < scale; i++)
            pow *= 10;
        float tmp = number * pow;
        return (float) (int) ((tmp - (int) tmp) >= 0.5f ? tmp + 1 : tmp) / pow;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean writeable = Settings.System.canWrite(this);
            if (!writeable) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
            if (this.checkSelfPermission(Manifest.permission.WRITE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_SETTINGS}, MY_PERMISSIONS_REQUEST_WRITE_SETTINGS);
                Log.i(TAG, "begged");
            }
        }
    }

    @Click(R.id.start)
    protected void onClickStart() {
        makeOwnAccessPoint();
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            mSensorAcc = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        if (mSensorAcc == null) {
            mCurrentValue.setText(R.string.cannot_get_accelerometer);
        } else {
            mSensorManager.registerListener(this, mSensorAcc, SensorManager.SENSOR_DELAY_NORMAL, 10_000);
        }
    }

    @Background
    protected void connectToGatewayAsClient(String ssid, String psk) {
        try {
            killOwnAccessPoint();
            Thread.sleep(5_000);
            Log.i(TAG, "woken");
            connectToGateway(ssid, psk);
            Thread.sleep(10_000);
            Log.i(TAG, "woken");
            openSocketOnGateway();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Click(R.id.open_socket)
    protected void onClickOpenSocketOnGateway() {
        openSocketOnGateway();
    }

    @Background
    protected void openSocketOnGateway() {
        try {
            Log.i(TAG, "trying to open socket on icg");
            mSocket = new Socket("192.168.10.1", PORT_NUMBER);
            mSocket.setKeepAlive(true);
            mBufferedWriter = new BufferedWriter(new OutputStreamWriter(mSocket.getOutputStream(), "UTF8"));
            Log.i(TAG, "mSocket: " + mSocket.isConnected());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * https://docs.oracle.com/javase/tutorial/displayCode.html?code=https://docs.oracle.com/javase/tutorial/networking/sockets/examples/KnockKnockServer.java
     */
    private void initGatewayListenerServer() {
        try (
                ServerSocket serverSocket = new ServerSocket(PORT_NUMBER);
                Socket clientSocket = serverSocket.accept();
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))
        ) {
            Log.i(TAG, "started listener server");
            char inputChar;

            String myIp = WifiApControl.getInstance(this.getApplicationContext()).getInet4Address().toString();
            out.println(myIp);

            StringBuilder inputLineBuilder = new StringBuilder(1024);
            while ((inputChar = (char) in.read()) != (char) -1) {
                inputLineBuilder.append(inputChar);
            }
            String inputLine = inputLineBuilder.toString();
            Log.i(TAG, "inputLine = " + inputLine);
            Matcher mLen = REGEX_LENS_SSID_PSK.matcher(inputLine);
            if (mLen.find()) {
                int lenSSID = Integer.parseInt(mLen.group(1));
                int lenPSK = Integer.parseInt(mLen.group(2));
                Log.i(TAG, "Lengths: ssid = " + lenSSID + " and psk = " + lenPSK);
                Matcher mKeys = Pattern.compile("^\\$\\d+\\r\\n(.{" + lenSSID + "})\\r\\n\\$\\d+\\r\\n(.{" + lenPSK + "})\\r\\n$").matcher(inputLine);
                if (mKeys.find()) {
                    String ssid = mKeys.group(1);
                    String psk = mKeys.group(2);
                    if (!(ssid.equals("") || psk.equals(""))) {
                        Log.i(TAG, "ssid = " + ssid + " and psk = " + psk);
                        connectToGatewayAsClient(ssid, psk); // backgrounded
                    }
                }
            }
            clientSocket.close();
            serverSocket.close();
        } catch (IOException e) {
            System.out.println("Exception caught when trying to listen on port " + PORT_NUMBER + " or listening for a connection");
            System.out.println(e.getMessage());
        }
    }

    /**
     * http://stackoverflow.com/a/8818490
     *
     * @param networkSSID     Likely "intwine-icg-GQ3T" (or similar), but may be anything.
     * @param networkPassword Comes from gateway, likely "password".
     */
    private void connectToGateway(String networkSSID, String networkPassword) {
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + networkSSID + "\"";
//        conf.wepKeys[0] = "\"" + networkPassword + "\"";
//        conf.wepTxKeyIndex = 0;
//        conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
//        conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
        conf.preSharedKey = "\"" + networkPassword + "\"";
        int networkId = mWifiManager.addNetwork(conf);
        mWifiManager.disconnect();
        mWifiManager.enableNetwork(networkId, true);
        mWifiManager.reconnect();
    }

    @Background
    protected void makeOwnAccessPoint() {
        WifiApControl apControl = WifiApControl.getInstance(this.getApplicationContext());

        boolean enabled = apControl.isEnabled();
        int state = apControl.getState();
        Log.i(TAG, "wifi enabled and state: " + enabled + " and " + state);

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "CAQMS";
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        Inet4Address addr4 = apControl.getInet4Address();
        Inet6Address addr6 = apControl.getInet6Address();
        Log.i(TAG, "I am: " + addr4 + " aka " + addr6);

        mWifiManager.setWifiEnabled(false);
        apControl.setEnabled(config, true);
        Log.i(TAG, "Made own AP");

        enabled = apControl.isEnabled();
        state = apControl.getState();
        Log.i(TAG, "wifi enabled and state: " + enabled + " and " + state);

        initGatewayListenerServer();
    }

    private void killOwnAccessPoint() {
        WifiApControl apControl = WifiApControl.getInstance(this.getApplicationContext());
        apControl.disable();
        mWifiManager.setWifiEnabled(true);
    }

    @Click(R.id.stop)
    protected void onClickStop() {
        killOwnAccessPoint();
        mSensorManager.unregisterListener(this, mSensorAcc);
    }

    @Background
    protected void sendToGateway(float[] vals) {
        String out = Arrays.toString(vals);
        if (mSocket != null && mSocket.isConnected()) {
//            String path = "/caqms/recv";
            try {
//                String data = URLEncoder.encode("data", "UTF-8") + "=" + URLEncoder.encode(out, "UTF-8");
//                mBufferedWriter.write("GET " + path + "/?" + data + " HTTP/1.0\r\n");
//                mBufferedWriter.write("Host: 192.168.10.1\r\n");
//                mBufferedWriter.write("Connection: keep-alive\r\n");
                mBufferedWriter.write(out);
                mBufferedWriter.write('\n');
                mBufferedWriter.flush();
            } catch (SocketException e) {
                // broken pipe; ^C on listener
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = System.nanoTime();
        if (now - mLastSample > THROTTLE_ACC_NS) {
            mLastSample = now;
            if (event.sensor == mSensorAcc) {
                float[] vals = event.values;
                for (int i = 0; i < vals.length; i++) {
                    vals[i] = roundToDigits(vals[i], 3);
                }
                mCurrentValue.setText(Arrays.toString(vals));
                sendToGateway(vals);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // ignore
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        Log.i(TAG, "grantResults = " + Arrays.toString(grantResults));
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_WRITE_SETTINGS: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // worked
                    Log.i(TAG, "received permission");
                } else {
                    Log.i(TAG, "fail");
                }
            }
        }
    }
}
