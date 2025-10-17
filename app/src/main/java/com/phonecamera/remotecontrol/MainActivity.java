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
            if (commandServer != null) commandServer.close();
            if (videoServer != null) videoServer.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing servers", e);
        }
    }
    
    private void runCommandServer() {
        try {
            commandServer = new ServerSocket(COMMAND_PORT);
            updateStatus("Waiting for PC connection...");
            
            commandClient = commandServer.accept();
            updateStatus("PC connected!");
            
            DataInputStream input = new DataInputStream(commandClient.getInputStream());
            DataOutputStream output = new DataOutputStream(commandClient.getOutputStream());
            
            while (isRunning) {
                try {
                    int length = input.readInt();
                    byte[] data = new byte[length];
                    input.readFully(data);
                    
                    String jsonStr = new String(data);
                    JSONObject command = new JSONObject(jsonStr);
                    
                    JSONObject response = processCommand(command);
                    
                    byte[] responseData = response.toString().getBytes();
                    output.writeInt(responseData.length);
                    output.write(responseData);
                    output.flush();
                    
                } catch (Exception e) {
                    if (isRunning) {
                        Log.e(TAG, "Command error", e);
                    }
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Command server error", e);
        }
    }
    
    private void runVideoServer() {
        try {
            videoServer = new ServerSocket(VIDEO_PORT);
            
            videoClient = videoServer.accept();
            DataOutputStream output = new DataOutputStream(videoClient.getOutputStream());
            
            while (isRunning) {
                byte[] frameData;
                
                synchronized (frameLock) {
                    while (frameQueue.isEmpty() && isRunning) {
                        frameLock.wait(100);
                    }
                    
                    if (!isRunning) break;
                    frameData = frameQueue.poll();
                }
                
                if (frameData != null) {
                    output.writeInt(frameData.length);
                    output.write(frameData);
                    output.flush();
                }
            }
        } catch (Exception e) {
            if (isRunning) {
                Log.e(TAG, "Video server error", e);
            }
        }
    }
    
    private JSONObject processCommand(JSONObject command) {
        JSONObject response = new JSONObject();
        
        try {
            String cmd = command.getString("command");
            JSONObject data = command.optJSONObject("data");
            
            Log.d(TAG, "Processing command: " + cmd);
            
            switch (cmd) {
                case "get_cameras":
                    JSONArray cameras = new JSONArray();
                    for (int i = 0; i < cameraIds.length; i++) {
                        String facing = getCameraFacing(i);
                        cameras.put(facing);
                    }
                    response.put("success", true);
                    response.put("cameras", cameras);
                    break;
                    
                case "switch_camera":
                    int cameraId = data.getInt("camera_id");
                    if (cameraId >= 0 && cameraId < cameraIds.length) {
                        runOnUiThread(() -> switchCamera(cameraId));
                        response.put("success", true);
                    } else {
                        response.put("success", false);
                        response.put("error", "Invalid camera ID");
                    }
                    break;
                    
                case "disconnect":
                    response.put("success", true);
                    break;
                    
                default:
                    response.put("success", false);
                    response.put("error", "Unknown command");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing command", e);
            try {
                response.put("success", false);
                response.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        
        return response;
    }
    
    private String getCameraFacing(int index) {
        try {
            CameraCharacteristics characteristics = 
                cameraManager.getCameraCharacteristics(cameraIds[index]);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                float[] focalLengths = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                
                if (focalLengths != null && focalLengths.length > 0) {
                    float focal = focalLengths[0];
                    if (focal < 3.0f) {
                        return "Wide (Back)";
                    } else if (focal > 6.0f) {
                        return "Tele (Back)";
                    } else {
                        return "Main (Back)";
                    }
                }
                return "Back Camera " + index;
            } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return "Front Camera";
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting camera info", e);
        }
        return "Camera " + index;
    }
    
    private void switchCamera(int index) {
        if (index == currentCameraIndex) return;
        
        currentCameraIndex = index;
        closeCamera();
        openCamera(index);
        
        updateStatus("Switched to camera " + index);
    }
    
    private void openCamera(int index) {
        try {
            String cameraId = cameraIds[index];
            
            CameraCharacteristics characteristics = 
                cameraManager.getCameraCharacteristics(cameraId);
            
            imageReader = ImageReader.newInstance(1280, 720, 
                ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, 
                new Handler(Looper.getMainLooper()));
            
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }
                
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    Log.e(TAG, "Camera error: " + error);
                }
            }, new Handler(Looper.getMainLooper()));
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening camera", e);
        }
    }
    
    private void createCaptureSession() {
        try {
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(imageReader.getSurface());
            
            cameraDevice.createCaptureSession(surfaces, 
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        startCapture();
                    }
                    
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "Capture session configuration failed");
                    }
                }, new Handler(Looper.getMainLooper()));
                
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating capture session", e);
        }
    }
    
    private void startCapture() {
        try {
            CaptureRequest.Builder builder = 
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            
            captureSession.setRepeatingRequest(builder.build(), null, 
                new Handler(Looper.getMainLooper()));
                
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error starting capture", e);
        }
    }
    
    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            
            synchronized (frameLock) {
                if (frameQueue.size() > 5) {
                    frameQueue.poll();
                }
                frameQueue.offer(bytes);
                frameLock.notifyAll();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }
    
    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }
    
    private void updateStatus(String status) {
        runOnUiThread(() -> statusText.setText(status));
        Log.d(TAG, status);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
}
