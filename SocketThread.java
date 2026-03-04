package com.carriertech.healson;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;

/**
 * WebSocket client for sending scan data to PACS server
 */
public class SocketThread {
    private static final String TAG = "SocketThread";
    private static final String WEBSOCKET_URL = "ws://carriertech.uk:7556";
    
    private WebSocketClient webSocketClient;
    private ArrayList<File> imageFiles;
    private JSONObject metadata;
    private int sentImages = 0;
    
    public interface ProgressCallback { 
        void onProgressUpdate(int current, int total); 
    }
    
    public interface CompletionCallback {
        void onComplete();
    }
    
    public interface ErrorCallback {
        void onError(String error);
    }
    
    private ProgressCallback progressCallback;
    private CompletionCallback completionCallback;
    private ErrorCallback errorCallback;
    
    /**
     * Send scan data to PACS server via WebSocket
     */
    public void sendScanData(JSONObject metadata, ArrayList<File> imageFiles, CompletionCallback onComplete, ErrorCallback onError) {
        sendScanData(metadata, imageFiles, null, onComplete, onError);
    }
    
    /**
     * Send scan data to PACS server via WebSocket with progress tracking
     */
    public void sendScanData(JSONObject metadata, ArrayList<File> imageFiles, ProgressCallback onProgress, CompletionCallback onComplete, ErrorCallback onError) {
        this.progressCallback = onProgress;
        this.completionCallback = onComplete;
        this.errorCallback = onError;
        this.metadata = metadata;
        this.imageFiles = imageFiles;
        this.sentImages = 0;
        
        try {
            URI serverUri = new URI(WEBSOCKET_URL);
            
            webSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "WebSocket connected to PACS server");
                    
                    try {
                        // Send metadata first
                        send(metadata.toString());
                        Log.d(TAG, "Sent metadata");
                        
                        // Send images one by one
                        sendNextImage();
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending data", e);
                        if (errorCallback != null) {
                            errorCallback.onError("Failed to send metadata: " + e.getMessage());
                        }
                    }
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "Received: " + message);
                    try {
                        JSONObject response = new JSONObject(message);
                        if ("image_received".equals(response.optString("status"))) {
                            // Send next image
                            sendNextImage();
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing response", e);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "WebSocket closed - code: " + code + ", reason: " + reason + ", remote: " + remote);
                    if (sentImages < imageFiles.size() && errorCallback != null) {
                        errorCallback.onError("Connection closed unexpectedly after " + sentImages + " images");
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket error", ex);
                    if (errorCallback != null) {
                        errorCallback.onError("Connection error: " + ex.getMessage());
                    }
                }
            };
            
            webSocketClient.connect();
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating WebSocket connection", e);
            if (errorCallback != null) {
                errorCallback.onError("Failed to connect: " + e.getMessage());
            }
        }
    }
    
    private void sendNextImage() {
        if (sentImages >= imageFiles.size()) {
            // All images sent, send completion signal
            try {
                JSONObject complete = new JSONObject();
                complete.put("type", "complete");
                webSocketClient.send(complete.toString());
                Log.d(TAG, "All images sent. Closing connection.");
                
                if (progressCallback != null) {
                    progressCallback.onProgressUpdate(sentImages, imageFiles.size());
                }
                
                if (completionCallback != null) {
                    completionCallback.onComplete();
                }
                
                webSocketClient.close();
            } catch (JSONException e) {
                Log.e(TAG, "Error sending completion signal", e);
                if (errorCallback != null) {
                    errorCallback.onError("Failed to send completion signal: " + e.getMessage());
                }
            }
            return;
        }
        
        File imageFile = imageFiles.get(sentImages);
        
        try {
            // Verify file exists and is readable
            if (!imageFile.exists()) {
                Log.e(TAG, "File does not exist: " + imageFile.getAbsolutePath());
                throw new Exception("File does not exist");
            }
            if (!imageFile.canRead()) {
                Log.e(TAG, "File cannot be read: " + imageFile.getAbsolutePath());
                throw new Exception("File cannot be read");
            }
            
            // Server routes to processed/ or raw/ subdir based on filename prefix:
            //   starts with "processed_" → processed/
            //   anything else            → raw/
            // Detect type from parent directory and prefix filename accordingly
            String filenameToSend = imageFile.getName();
            String parentName = imageFile.getParentFile() != null
                    ? imageFile.getParentFile().getName().toLowerCase() : "";
            if (parentName.contains("processed") && !filenameToSend.startsWith("processed_")) {
                filenameToSend = "processed_" + filenameToSend;
            }
            
            Log.d(TAG, "Sending image " + (sentImages + 1) + "/" + imageFiles.size() + ": " + filenameToSend + " (" + imageFile.length() + " bytes)");
            
            // Send filename - server constructs the directory path from metadata
            webSocketClient.send(filenameToSend);
            
            // Read and send image data
            java.io.FileInputStream fis = new java.io.FileInputStream(imageFile);
            byte[] imageData = new byte[(int) imageFile.length()];
            int bytesRead = fis.read(imageData);
            fis.close();
            
            if (bytesRead != imageFile.length()) {
                Log.e(TAG, "Failed to read entire file. Expected " + imageFile.length() + " bytes, read " + bytesRead);
                throw new Exception("Failed to read entire file");
            }
            
            webSocketClient.send(imageData);
            sentImages++;
            
            Log.d(TAG, "Successfully sent image " + sentImages + "/" + imageFiles.size());
            
            if (progressCallback != null) {
                progressCallback.onProgressUpdate(sentImages, imageFiles.size());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending image: " + imageFile.getAbsolutePath(), e);
            // Report error but try to continue with next image
            if (errorCallback != null) {
                errorCallback.onError("Failed to send image " + imageFile.getName() + ": " + e.getMessage());
            }
            sentImages++;
            sendNextImage();
        }
    }
    
    // Legacy methods kept for compatibility
    public void sendScanData(JSONObject metadata, ArrayList<File> imageFiles) {
        sendScanData(metadata, imageFiles, null, null);
    }
}
