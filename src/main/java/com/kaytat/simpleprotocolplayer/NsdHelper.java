package com.kaytat.simpleprotocolplayer;

import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NsdHelper {
    Context mContext;
    NsdManager mNsdManager;
    NsdManager.ResolveListener mResolveListener;
    NsdManager.DiscoveryListener mDiscoveryListener;
    public static final String SERVICE_TYPE = "_pulse-server._tcp.";
    public static final String TAG = "NsdHelper";
    NsdServiceInfo mService;
    Socket socket;
    String tcpRate, tcpServer, tcpPort = null;
    Integer tcpChannels;


    public NsdHelper(Context context) {
        mContext = context;
        mNsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    public void initializeNsd() {
        initializeResolveListener();
    }

    public void initializeDiscoveryListener() {
        mDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(TAG, "Service discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "Service discovery success " + service);
                if (service.getServiceType().equals(SERVICE_TYPE)) {
                    mNsdManager.resolveService(service, mResolveListener);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.e(TAG, "service lost" + service);
                if (mService == service) {
                    mService = null;
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
            }
        };
    }

    public void initializeResolveListener() {
        mResolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Resolve failed" + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Resolve Succeeded. " + serviceInfo);

                tcpServer = serviceInfo.getHost().getHostAddress();
                try {
                    socket = new Socket(tcpServer, 4712);
                    socket.setSoTimeout(1000);
                    String Result = _sendRawRequest("list-modules");

                    String[] arguments = Result.split(" ");
                    for (String argument : arguments) {

                        //extract Port
                        if (argument.contains("port=")) {
                            Pattern pPort = Pattern.compile("port=(.*)");
                            Matcher mPort = pPort.matcher(argument);
                            mPort.find();
                            tcpPort = mPort.group(1).replaceAll(">", "");
                        }
                        //extract Rate
                        if (argument.contains("rate=")) {
                            Pattern pRate = Pattern.compile("rate=(.*)");
                            Matcher mRate = pRate.matcher(argument);
                            mRate.find();
                            tcpRate = mRate.group(1).replaceAll(">", "");
                        }
                        //extract Channels
                        if (argument.contains("channels=")) {
                            Pattern pChannels = Pattern.compile("channels=(.*)");
                            Matcher mRChannels = pChannels.matcher(argument);
                            mRChannels.find();
                            tcpChannels = Integer.valueOf(mRChannels.group(1).replaceAll(">", ""));
                        }
                    }
                    Intent intent = new Intent("nsdDetect");
                    intent.putExtra("Server", tcpServer);
                    intent.putExtra("Port", tcpPort);
                    intent.putExtra("Rate", tcpRate);
                    intent.putExtra("Channels", tcpChannels);
                    LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
                    Log.i(TAG, "Updating Interface...");

                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Server Error" + e);
                    stopDiscovery();
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Server Error" + e);
                    stopDiscovery();
                }
                mService = serviceInfo;
            }
        };
    }

    public void discoverServices() {
        stopDiscovery();  // Cancel any existing discovery request
        initializeDiscoveryListener();
        mNsdManager.discoverServices(
                SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
    }

    public void stopDiscovery() {
        if (mDiscoveryListener != null) {
            try {
                mNsdManager.stopServiceDiscovery(mDiscoveryListener);
            } finally {
            }
            mDiscoveryListener = null;
        }
    }

    private String _sendRawRequest(String command) {
        String result = "";
        try {
            PrintStream out = new PrintStream(socket.getOutputStream(), true);
            out.print(command + "\r\n");
            InputStream instr = socket.getInputStream();
            try {
                byte[] buff = new byte[1024];
                int ret_read = 0;
                int lc = 0;
                do {
                    ret_read = instr.read(buff);
                    lc++;
                    if (ret_read > 0) {
                        String line = new String(buff, 0, ret_read);

                        if (line.endsWith(">>> ") && lc > 1) {
                            result += line.substring(0, line.length() - 4);
                            break;
                        }
                        result += line.trim();
                    }
                } while (ret_read > 0);
            } catch (SocketTimeoutException e) {
                // Timeout -> as newer PA versions (>=5.0) do not send the >>> we have no chance
                // to detect the end of the answer, except by this timeout
            } catch (IOException e) {
            }
            instr.close();
            out.close();
            socket.close();
            return result;
        } catch (IOException e) {
        }
        return result;
    }
}
