package com.wakeup.voice.voice_wakeup;

import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.allen.library.SuperTextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import lecho.lib.hellocharts.model.Line;
import lecho.lib.hellocharts.model.LineChartData;
import lecho.lib.hellocharts.model.PointValue;
import lecho.lib.hellocharts.view.LineChartView;

import static java.lang.Math.max;
import static java.lang.Math.pow;


public class MainActivity extends AppCompatActivity {

    //分析
    private SuperTextView tv;
    private LineChartView lineChartView;
    //private MyTSF mytsf;
    private TensorflowRunner tensorflowRunner;
    private PreProcess preProcess;
    private String melBankPath = "fbank_26.txt";
    private int categoral = 5;

    private static final int REQUEST_RECORD_AUDIO = 13;
    private static final int SAMPLE_RATE = 16000;
    private static final int SAMPLE_DURATION_MS = 1000;
    private Thread recordingThread;
    private Thread recognitionThread;
    boolean shouldContinue = true;
    boolean shouldContinueRecognition = true;
    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    private static final int RECORDING_LENGTH = (int) (SAMPLE_RATE * SAMPLE_DURATION_MS / 1000);
    short[] recordingBuffer = new short[RECORDING_LENGTH];
    int recordingOffset = 0;
    private final ReentrantLock recordingBufferLock = new ReentrantLock();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        tensorflowRunner = new TensorflowRunner(getApplicationContext(), new TensorflowRunner.TensorflowRunnerListener() {
            @Override
            public void callback(float[] data, int len) {
                float res[][] = new float[len][categoral];
                for (int j = 0; j < len; j++) {
                    System.arraycopy(data, j * 5, res[j], 0, 5);
                }
                float[][] smooth_res = smooth(res);
                belive(smooth_res);
            }
        });
        requestMicrophonePermission();
        startRecording();
        startRecognition();
    }

    private void requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_RECORD_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
            //startRecognition();
        }
    }

    public synchronized void startRecording() {
        if (recordingThread != null) {
            return;
        }
        shouldContinue = true;
        recordingThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                record();
                            }
                        });
        recordingThread.start();
    }

    public synchronized void stopRecording() {
        if (recordingThread == null) {
            return;
        }
        shouldContinue = false;
        recordingThread = null;
    }

    private void record() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        // Estimate the buffer size we'll need for this device.
        int bufferSize =
                AudioRecord.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2;
        }
        short[] audioBuffer = new short[bufferSize / 2];

        AudioRecord record =
                new AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return;
        }

        record.startRecording();

        Log.v(LOG_TAG, "Start recording");

        // Loop, gathering audio data and copying it to a round-robin buffer.
        while (shouldContinue) {
            int numberRead = record.read(audioBuffer, 0, audioBuffer.length);
            int maxLength = recordingBuffer.length;
            int newRecordingOffset = recordingOffset + numberRead;
            int secondCopyLength = Math.max(0, newRecordingOffset - maxLength);
            int firstCopyLength = numberRead - secondCopyLength;//newRecordingOffset 可能超过recording buffer最大长度，循环存放
            // We store off all the data for the recognition thread to access. The ML
            // thread will copy out of this buffer into its own, while holding the
            // lock, so this should be thread safe.
            recordingBufferLock.lock();
            try {
                System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, firstCopyLength);
                System.arraycopy(audioBuffer, firstCopyLength, recordingBuffer, 0, secondCopyLength);
                recordingOffset = newRecordingOffset % maxLength;
            } finally {
                recordingBufferLock.unlock();
            }
        }

        record.stop();
        record.release();
    }

    public synchronized void startRecognition() {
        if (recognitionThread != null) {
            return;
        }
        shouldContinueRecognition = true;
        recognitionThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                recognize();
                            }
                        });
        recognitionThread.start();
    }

    public synchronized void stopRecognition() {
        if (recognitionThread == null) {
            return;
        }
        shouldContinueRecognition = false;
        recognitionThread = null;
    }

    private void recognize() {
        Log.v(LOG_TAG, "Start recognition");

        short[] inputBuffer = new short[RECORDING_LENGTH];
        float[] floatInputBuffer = new float[RECORDING_LENGTH];

        // Loop, grabbing recorded data and running the recognition model on it.
        while (shouldContinueRecognition) {
            // The recording thread places data in this round-robin buffer, so lock to
            // make sure there's no writing happening and then copy it to our own
            // local version.
            Log.i(LOG_TAG,"start recognize");
            recordingBufferLock.lock();
            try {
                int maxLength = recordingBuffer.length;
                int firstCopyLength = maxLength - recordingOffset;
                int secondCopyLength = recordingOffset;
                System.arraycopy(recordingBuffer, recordingOffset, inputBuffer, 0, firstCopyLength);
                System.arraycopy(recordingBuffer, 0, inputBuffer, firstCopyLength, secondCopyLength);
            } finally {
                recordingBufferLock.unlock();
            }

            // We need to feed in float values between -1.0f and 1.0f, so divide the
            // signed 16-bit inputs.
            for (int i = 0; i < RECORDING_LENGTH; ++i) {
                floatInputBuffer[i] = inputBuffer[i] / 32768.0f;
            }
            //VAD
            jiexi(floatInputBuffer);
            Log.i(LOG_TAG,"end recognize");
        }
        Log.v(LOG_TAG, "End recognition");
    }

    private void initView() {
        tv = (SuperTextView) findViewById(R.id.textView);
        tv.setLeftString("score:");
        lineChartView = (LineChartView) findViewById(R.id.lineChartView);
        preProcess = new PreProcess(getApplicationContext(), null, melBankPath);
    }

    public void jiexi(float[] input) {
        //if (mytsf == null)
        //return;
        double[] new_input = new double[input.length];
        for (int i = 0; i < input.length; i++)
            new_input[i] = (double) input[i];
        preProcess.setWavData(new_input);
        final double[][] resule = preProcess.getMFCC();
        double[][] delta1 = preProcess.getDelta(resule, 1);
        double[][] delta2 = preProcess.getDelta(resule, 2);
        double[][] res = preProcess.concatenate(resule, delta1, delta2);
        res = preProcess.normalize(res);
        double[][] new_res = preProcess.get_martix(res, 30, 10);
        tensorflowRunner.add(new_res);
    }

    public void belive(float x[][]) {
        float[] result = new float[x.length];
        int wmax = 50;
        int hmax;
        for (int i = 0; i < x.length; i++) {
            result[i] = 1;
            hmax = max(0, i - wmax);
            for (int j = 1; j < categoral; j++) {
                float max1 = x[hmax][j];
                for (int k = hmax; k < i + 1; k++)
                    if (max1 < x[k][j])
                        max1 = x[k][j];
                result[i] *= max1;
            }
            result[i] = (float) pow(result[i], 0.25);
        }
        List<PointValue> values = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < result.length; i++) {
            values.add(new PointValue(i, result[i]));
        }
        Line line = new Line(values);
        line.setHasPoints(false);
        line.setCubic(true);
        lines.add(line);
        LineChartData data = new LineChartData(lines);
        lineChartView.setLineChartData(data);
        float m_max = result[0];
        for (float aResult : result)
            if (m_max < aResult)
                m_max = aResult;
        tv.setCenterString(String.valueOf(m_max));
    }

    public float[][] smooth(float x[][]) {
        int wsmooth = 30;
        int hsmooth;
        float[][] result = new float[x.length][x[0].length];
        for (int i = 0; i < x[0].length; i++) {
            for (int j = 0; j < x.length; j++) {
                hsmooth = max(0, j - wsmooth);
                for (int k = hsmooth; k < j + 1; k++)
                    result[j][i] += x[k][i];
                result[j][i] = result[j][i] / (j - hsmooth + 1);
            }
        }
        return result;
    }

}

