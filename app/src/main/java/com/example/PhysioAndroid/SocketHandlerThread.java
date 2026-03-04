package com.example.PhysioAndroid;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;

public class SocketHandlerThread extends HandlerThread  {
    DataOutputStream dos;
    public static final String SERVER_IP = "http://carriertech.uk"; //server IP address
    public static final int SERVER_PORT = 8888;
    public Socket socket = null;
    public Handler mHandler;
    byte[] data;
    private BufferedReader mBufferIn;


    public SocketHandlerThread(String name) {
        super(name);

    }
    public static long getFuckingID(){
        return Thread.currentThread().getId();
    }


    @Override
    public void onLooperPrepared() {
        Log.e("captureth","looper was called" + Thread.currentThread().getId());
        try {
            Log.e("captureth","trying");            InetAddress serverAddr = InetAddress.getByName(SERVER_IP);
            socket = new Socket(serverAddr, SERVER_PORT);
            Log.e("capturethread", "the socket is = " + socket);
            dos = new DataOutputStream(socket.getOutputStream());
            mBufferIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        } catch (IOException e) {
            Log.e("capturethread", "failed = " + e);
        }
        Log.e("captureth","dafuq");
        //mHandler = new Handler(this.getLooper(),this);
        mHandler = new Handler(this.getLooper()) {

            @Override
            public void handleMessage(Message msg) {

                //mHandler.obtainMessage();
                data = msg.getData().getByteArray("data");
                float pitchy = msg.getData().getFloat("pitchy");
                Log.e("capturethread", "received msg of Array Length - " + data.length);
                try {
                dos.write(data);
                dos.writeBytes("ENDOFIMAGE");
                String json = "{\"imageType\":\"guidance\", \"pitchy\":" + pitchy + ", \"target\":\"shoulder\"}";
                dos.writeUTF(json);
                dos.writeBytes("ENDOFFILE");
            } catch (IOException e) {
                e.printStackTrace();
            }
            }

        };

    }


}








