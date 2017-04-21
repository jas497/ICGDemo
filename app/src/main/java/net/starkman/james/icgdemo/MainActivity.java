package net.starkman.james.icgdemo;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.Click;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.SystemService;
import org.androidannotations.annotations.ViewById;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.logging.SocketHandler;

@EActivity
public class MainActivity extends Activity implements SensorEventListener {

    @SystemService
    protected SensorManager mSensorManager;
    private Sensor mSensorAcc = null;
    @ViewById(R.id.text_current)
    protected TextView mCurrentValue;
    private long mLastSample = System.nanoTime();
    private static final long THROTTLE_ACC_US = 1_000_000;

    private Socket mSocket;

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
    }

    @Override
    protected void onStart() {
        super.onStart();
        initSocket();
    }

    @Click(R.id.start)
    protected void onClickStart() {
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
    protected void initSocket() {
        try {
            mSocket = new Socket("192.168.10.1", 3398);
            Log.i("EECS398", "mSocket: " + mSocket.isConnected());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Click(R.id.stop)
    protected void onClickStop() {
        mSensorManager.unregisterListener(this, mSensorAcc);
    }

    @Background
    protected void sendToGateway(float[] vals) {
        String out = Arrays.toString(vals);
        if (mSocket != null) {
            String path = "/caqms/recv";
            try {
                mSocket = new Socket("192.168.10.1", 3398);
                String data = URLEncoder.encode("data", "UTF-8") + "=" + URLEncoder.encode(out, "UTF-8");
                BufferedWriter wr = new BufferedWriter(new OutputStreamWriter(mSocket.getOutputStream(), "UTF8"));
                wr.write("POST " + path + " HTTP/1.0\r\n");
                wr.write("Content-Length: " + data.length() + "\r\n");
                wr.write("Content-Type: application/x-www-form-urlencoded\r\n");
                wr.write("\r\n");
                wr.write(data);
                wr.flush();
                mSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = System.nanoTime();
        if (now - mLastSample > THROTTLE_ACC_US) {
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
}
