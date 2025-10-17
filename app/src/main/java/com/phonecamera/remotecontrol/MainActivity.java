package com.phonecamera.remotecontrol;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    
    private static final String TAG = "RemoteCameraControl";
    private static final int COMMAND_PORT = 9999;
    private static final int VIDEO_PORT = 9998;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    
    private TextureView textureView;
    private TextView statusText;
    private TextView ipText;
    private Button startButton;
    
    private CameraManager cameraManager;
    private String[] cameraIds;
    private int currentCameraIndex = 0;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    
    private ServerSocket commandServer;
    private ServerSocket videoServer;
    private Socket commandClient;
    private Socket videoClient;
    private boolean isRunning = false;
    private Thread commandThread;
    private Thread videoThread;
    
    private final LinkedList<byte[]> frameQueue = new LinkedList<>();
    private final Object frameLock = new Object();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        textureView = findViewById(R.id.textureView);
        statusText = findViewById(R.id.statusText);
        ipText = findViewById(R.id.ipText);
        startButton = findViewById(R.id.startButton);
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.CAMERA, 
                            Manifest.permission.INTERNET}, 
                REQUEST_CAMERA_PERMISSION);
        }
        
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraIds = cameraManager.getCameraIdList();
            Log.d(TAG, "Found " + cameraIds.length + " cameras");
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing cameras", e);
        }
        
        String ipAddress = getIPAddress();
        ipText.setText("IP: " + ipAddress + "\nCommand Port: " + COMMAND_PORT + 
                      "\nVideo Port: " + VIDEO_PORT);
        
        startButton.setOnClickListener(v -> {
            if (!isRunning) {
                startServer();
            } else {
                stopServer();
            }
        });
    }
    
    private String getIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP", e);
        }
        return "Unknown";
    }
    
    private void startServer() {
        isRunning = true;
        startButton.setText("Stop Server");
        updateStatus("Starting server...");
        
        commandThread = new Thread(this::runCommandServer);
        commandThread.start();
        
        videoThread = new Thread(this::runVideoServer);
        videoThread.start();
        
        openCamera(currentCameraIndex);
    }
    
    private void stopServer() {
        isRunning = false;
        startButton.setText("Start Server");
        updateStatus("Server stopped");
        
        closeCamera();
        
        try {
            if (commandClient != null) commandClient.close();
            if (videoClient != null) videoClient.close();
            if (commandServer != null) comman
