/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2014 kaytat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kaytat.simpleprotocolplayer;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.io.DataInputStream;
import java.net.Socket;

/**
 * Service that handles media playback. This is the Service through which we perform all the media
 * handling in our application.
 */
public class MusicService extends Service implements MusicFocusable {

    // The tag we put on debug messages
    final static String TAG = "SimpleProtocol";

    static final int DEFAULT_AUDIO_PORT = 12345;
    static final int DEFAULT_SAMPLE_RATE = 44100;
    static final boolean DEFAULT_STEREO = true;
    static final int DEFAULT_BUFFER_MS = 50;

    // These are the Intent actions that we are prepared to handle. Notice that the fact these
    // constants exist in our class is a mere convenience: what really defines the actions our
    // service can handle are the <action> tags in the <intent-filters> tag for our service in
    // AndroidManifest.xml.
    public static final String ACTION_PLAY = "com.kaytat.simpleprotocolplayer.action.PLAY";
    public static final String ACTION_STOP = "com.kaytat.simpleprotocolplayer.action.STOP";

    public static final String DATA_IP_ADDRESS = "ip_addr";
    public static final String DATA_AUDIO_PORT = "audio_port";
    public static final String DATA_SAMPLE_RATE = "sample_rate";
    public static final String DATA_STEREO = "stereo";
    public static final String DATA_BUFFER_MS = "buffer_ms";

    // The volume we set the media player to when we lose audio focus, but are allowed to reduce
    // the volume instead of stopping playback.
    public static final float DUCK_VOLUME = 0.1f;

    // Media track
    private AudioTrack mTrack = null;
    private ThreadStoppable bufferToAudioTrackWorkerThread = null;
    private ThreadStoppable networkReadWorkerThread = null;

    // our AudioFocusHelper object, if it's available (it's available on SDK level >= 8)
    // If not available, this will be null. Always check for null before using!
    AudioFocusHelper mAudioFocusHelper = null;

    // indicates the state our service:
    enum State {
        Stopped, // media player is stopped and not prepared to play
        Playing  // playback active (media player ready!)
    }

    State mState = State.Stopped;

    // do we have audio focus?
    // do we have audio focus?
    enum AudioFocus {
        NoFocusNoDuck,    // we don't have audio focus, and can't duck
        NoFocusCanDuck,   // we don't have focus, but can play at a low volume ("ducking")
        Focused           // we have full audio focus
    }
    AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;

    // Wifi lock that we hold when streaming files from the internet, in order to prevent the
    // device from shutting off the Wifi radio
    WifiLock mWifiLock;

    // The ID we use for the notification (the onscreen alert that appears at the notification
    // area at the top of the screen as an icon -- and as text as well if the user expands the
    // notification area).
    final int NOTIFICATION_ID = 1;

    Notification mNotification = null;

    @Override
    public void onCreate() {
        Log.i(TAG, "Creating service");

        // Create the Wifi lock (this does not acquire the lock, this just creates it)
        mWifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, "mylock");

        // create the Audio Focus Helper, if the Audio Focus feature is available (SDK 8 or above)
        if (android.os.Build.VERSION.SDK_INT >= 8)
            mAudioFocusHelper = new AudioFocusHelper(getApplicationContext(), this);
        else
            mAudioFocus = AudioFocus.Focused; // no focus feature, so we always "have" audio focus
    }

    /**
     * Called when we receive an Intent. When we receive an intent sent to us via startService(),
     * this is the method that gets called. So here we react appropriately depending on the
     * Intent's action, which specifies what is being requested of us.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (action.equals(ACTION_PLAY)) {
            processPlayRequest(intent);
        }
        else if (action.equals(ACTION_STOP)) {
            processStopRequest();
        }

        return START_NOT_STICKY; // Means we started the service, but don't want it to
        // restart in case it's killed.
    }

    void processPlayRequest(Intent i) {
        if (mState == State.Stopped) {
            tryToGetAudioFocus();
            playStream(
                    i.getStringExtra(DATA_IP_ADDRESS),
                    i.getIntExtra(DATA_AUDIO_PORT, DEFAULT_AUDIO_PORT),
                    i.getIntExtra(DATA_SAMPLE_RATE, DEFAULT_SAMPLE_RATE),
                    i.getBooleanExtra(DATA_STEREO, DEFAULT_STEREO),
                    i.getIntExtra(DATA_BUFFER_MS, DEFAULT_BUFFER_MS));
        }
    }

    void processStopRequest() {
        if (mState == State.Playing) {
            mState = State.Stopped;

            // let go of all resources...
            relaxResources();
            giveUpAudioFocus();

            // service is no longer necessary. Will be started again if needed.
            stopSelf();
        }
    }

    void stopAndInterrupt(ThreadStoppable t) {
        if (t != null) {
            try {
                t.customStop();
                t.interrupt();

                // Do not join since this can take some time.  The
                // workers should be able to shutdown independently.
                // t.join();
            }
            catch (Exception e) {
                Log.e(TAG, "join exception:" + e);
            }
        }
    }

    /**
     * Releases resources used by the service for playback. This includes the "foreground service"
     * status and notification, the wake locks and the AudioTrack
     */
    void relaxResources() {
        // stop being a foreground service
        stopForeground(true);

        // we can also release the Wifi lock, if we're holding it
        if (mWifiLock.isHeld()) mWifiLock.release();

        // Wait for worker thread to stop if running
        stopAndInterrupt(bufferToAudioTrackWorkerThread);
        stopAndInterrupt(networkReadWorkerThread);

        // Make sure to release any resources
        if (mTrack != null) {
            mTrack = null;
        }
    }

    void tryToGetAudioFocus() {
        if (mAudioFocus != AudioFocus.Focused && mAudioFocusHelper != null
                && mAudioFocusHelper.requestFocus())
            mAudioFocus = AudioFocus.Focused;
    }

    void giveUpAudioFocus() {
        if (mAudioFocus == AudioFocus.Focused && mAudioFocusHelper != null
                && mAudioFocusHelper.abandonFocus())
            mAudioFocus = AudioFocus.NoFocusNoDuck;
    }

    /**
     * Reconfigures AudioTrack according to audio focus settings and starts/restarts it.
     */
    void configVolume() {
        if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
            // If we don't have audio focus and can't duck, we have to pause, even if mState
            // is State.Playing. But we stay in the Playing state so that we know we have to resume
            // playback once we get the focus back.
            if (mState == State.Playing) {
                processStopRequest();
            }
        }

        else if (mAudioFocus == AudioFocus.NoFocusCanDuck) {
            mTrack.setStereoVolume(DUCK_VOLUME, DUCK_VOLUME);  // we'll be relatively quiet
        }
        else {
            mTrack.setStereoVolume(1.0f, 1.0f); // we can be loud
        }
    }

    static private final int NUM_PKTS = 3;

    // The amount of data to read from the network before sending to AudioTrack
    private int packet_size;
    private final Object filledLock = new Object();
    private int filled = 0;
    private byte[][] byteArray = new byte[NUM_PKTS][];

    void initNetworkData(
            int sample_rate,
            boolean stereo,
            int minBuf,
            int buffer_ms) {

        // Assume 16 bits per sample
        int bytesPerSecond = sample_rate * 2;
        if (stereo) {
            bytesPerSecond *= 2;
        }

        packet_size = (bytesPerSecond * buffer_ms) / 1000;
        if ((packet_size & 1) != 0) {
            packet_size++;
        }

        Log.d(TAG, "initNetworkData:bytes / second:" + (bytesPerSecond));
        Log.d(TAG, "initNetworkData:minBuf:" + minBuf);
        Log.d(TAG, "initNetworkData:packet_size:" + packet_size);

        for (int i = 0; i < NUM_PKTS; i++) {
            byteArray[i] = new byte[packet_size];
        }
    }

    private class ThreadStoppable extends Thread {
        boolean running = true;
        public void customStop() {
            running = false;
        }
    }

    /**
     * Worker thread that takes data from the buffer and sends it to audio track
     */
    private class BufferToAudioTrackThread extends ThreadStoppable {
        static final String TAG = "BTATThread";

        private AudioTrack mTrack;

        public BufferToAudioTrackThread(AudioTrack track) {
            mTrack = track;
        }

        @Override
        public void run() {
            Log.i(TAG, "start");

            mTrack.play();

            try {
                int idx = 0;
                while (running) {
                    synchronized (filledLock) {
                        while (filled == 0) {
                            filledLock.wait();
                            if (!running) {
                                throw new Exception("Not running");
                            }
                        }
                    }

                    mTrack.write(byteArray[idx], 0, packet_size);
                    idx++;
                    if (idx == NUM_PKTS) {
                        idx = 0;
                    }
                    synchronized (filledLock) {
                        filled--;
                        filledLock.notifyAll();
                    }
                }
            }
            catch (Exception e) {
                Log.e(TAG, "exception:" + e);
            }

            // Do some cleanup
            mTrack.stop();
            mTrack.release();
            mTrack = null;
            Log.i(TAG, "done");
        }
    }

    /**
     * Worker thread reads data from the network
     */
    private class NetworkReadThread extends ThreadStoppable {
        static final String TAG = "NRThread";

        Context context;
        String ipAddr;
        int port;
        Socket socket;

        // socket timeout at 5 seconds
        static final int SOCKET_TIMEOUT = 5 * 1000;

        public NetworkReadThread(Context context, String ipAddr, int port) {
            this.context = context;
            this.ipAddr = ipAddr;
            this.port = port;
        }

        @Override
        public void run() {
            Log.i(TAG, "start");

            byte[] tmpBuf = new byte[packet_size];
            try {
                // Create the TCP socket and setup some parameters
                socket = new Socket(ipAddr, port);
                DataInputStream is = new DataInputStream(
                        socket.getInputStream());
                try {
                    socket.setSoTimeout(SOCKET_TIMEOUT);
                    socket.setTcpNoDelay(true);
                } catch (Exception e) {
                    Log.i(TAG, "exception:" + e);
                    return;
                }

                Log.i(TAG, "running");
                int idx = 0;

                while (running) {
                    synchronized (filledLock) {
                        while (filled == NUM_PKTS) {
                            filledLock.wait();
                            if (!running) {
                                throw new Exception("Not running");
                            }
                        }
                    }

                    // Get a packet
                    is.readFully(byteArray[idx]);
                    idx++;
                    if (idx == NUM_PKTS) {
                        idx = 0;
                    }
                    synchronized (filledLock) {
                        filled++;
                        if (filled == NUM_PKTS) {
                            Log.i(TAG, "flushing");
                            // Filled up.  Throw away everything that's in the network queue.
                            int rd;
                            do {
                                rd = is.read(tmpBuf);
                            } while (rd == packet_size && running);
                        }
                        filledLock.notifyAll();
                    }
                }
            }
            catch (Exception e) {
                Log.i(TAG, "exception:" + e);
            }

            try {
                if (socket != null) {
                    socket.close();
                }
                if (running) {
                    // Broke out of loop unexpectedly.  Shutdown.
                    Handler h = new Handler(context.getMainLooper());
                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "Unable to stream", Toast.LENGTH_SHORT).show();
                            processStopRequest();
                        }
                    };
                    h.post(r);
                }
            }
            catch (Exception e) {
                Log.i(TAG, "exception while closing:" + e);
            }

            Log.i(TAG, "done");
        }
    }

    /**
     * Play the stream using the given IP address and port
     */
    void playStream(String serverAddr,
                    int serverPort,
                    int sample_rate,
                    boolean stereo,
                    int buffer_ms) {
        int format = stereo ?
                AudioFormat.CHANNEL_OUT_STEREO :
                AudioFormat.CHANNEL_OUT_MONO;

        // Sanitize input, just in case
        if (sample_rate <= 0) {
            sample_rate = DEFAULT_SAMPLE_RATE;
        }

        if (buffer_ms <= 5) {
            buffer_ms = DEFAULT_BUFFER_MS;
        }

        int minBuf = AudioTrack.getMinBufferSize(
                sample_rate,
                format,
                AudioFormat.ENCODING_PCM_16BIT);

        initNetworkData(sample_rate, stereo, minBuf, buffer_ms);

        mState = State.Stopped;
        relaxResources();
        filled = 0;

        // The agreement here is that mTrack will be shutdown by the helper
        mTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                sample_rate, format,
                AudioFormat.ENCODING_PCM_16BIT, minBuf,
                AudioTrack.MODE_STREAM);

        bufferToAudioTrackWorkerThread = new BufferToAudioTrackThread(mTrack);
        networkReadWorkerThread = new NetworkReadThread(this, serverAddr, serverPort);

        bufferToAudioTrackWorkerThread.start();
        networkReadWorkerThread.start();

        mState = State.Playing;
        configVolume();

        setUpAsForeground("Streaming from " + serverAddr);
    }

    /**
     * Configures service as a foreground service. A foreground service is a service that's doing
     * something the user is actively aware of (such as playing music), and must appear to the
     * user as a notification. That's why we create the notification here.
     */
    void setUpAsForeground(String text) {
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0,
                new Intent(getApplicationContext(), MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        mNotification = new Notification();
        mNotification.tickerText = text;
        mNotification.icon = R.drawable.ic_stat_playing;
        mNotification.flags |= Notification.FLAG_ONGOING_EVENT;
        mNotification.setLatestEventInfo(getApplicationContext(), "SimpleProtocolPlayer",
                text, pi);
        startForeground(NOTIFICATION_ID, mNotification);
    }

    @Override
    public void onGainedAudioFocus() {
        Log.i(TAG, "Gained audio focus");
        mAudioFocus = AudioFocus.Focused;

        // restart media player with new focus settings
        if (mState == State.Playing)
            configVolume();
    }

    @Override
    public void onLostAudioFocus(boolean canDuck) {
        Log.i(TAG, "Lost audio focus: canDuck:" + canDuck);
        mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck : AudioFocus.NoFocusNoDuck;

        // start/restart/pause media player with new focus settings
        if (mState == State.Playing)
            configVolume();
    }

    @Override
    public void onDestroy() {
        // Service is being killed, so make sure we release our resources
        mState = State.Stopped;
        relaxResources();
        giveUpAudioFocus();
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }
}
