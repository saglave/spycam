package spy.cam;
import spy.cam.*;


import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.lang.System;
import java.lang.Thread;
import java.util.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import  org.apache.http.conn.util.InetAddressUtils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.ProgressDialog;
import android.content.res.Resources;
import android.content.res.AssetManager;
import android.content.res.AssetFileDescriptor;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.PictureCallback;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.YuvImage;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.media.AudioRecord;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.SurfaceView;
import android.util.Log;
import android.widget.LinearLayout; 
import android.widget.ImageButton;
import android.widget.Button;
import android.widget.TextView;


public class MainActivity extends Activity 
    implements View.OnTouchListener, CameraView.CameraReadyCallback, OverlayView.UpdateDoneCallback{
    private static final String TAG = "SPY";


    boolean inProcessing = false;
    final int maxVideoNumber = 3;
    VideoFrame[] videoFrames = new VideoFrame[maxVideoNumber];
    byte[] preFrame = new byte[1024*1024*8];
    
    Server webServer = null;
    private CameraView cameraView_;
    private OverlayView overlayView_;
    private Button btnExit;
    private TextView tvMessage1;
    private TextView tvMessage2;

    private AudioRecord audioCapture = null;
    private StreamingLoop audioLoop = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = getWindow();
        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);    
        

        setContentView(R.layout.main);

        btnExit = (Button)findViewById(R.id.btn_exit);
        btnExit.setOnClickListener(exitAction);
        tvMessage1 = (TextView)findViewById(R.id.tv_message1);
        tvMessage2 = (TextView)findViewById(R.id.tv_message2);
        
        for(int i = 0; i < maxVideoNumber; i++) {
            videoFrames[i] = new VideoFrame(1024*1024*2);        
        }    

        System.loadLibrary("mp3encoder");
        System.loadLibrary("natpmp");

        initAudio();
        initCamera();
    }
    
    @Override
    public void onCameraReady() {
        if ( initWebServer() ) {
            int wid = cameraView_.Width();
            int hei = cameraView_.Height();
            cameraView_.StopPreview();
            cameraView_.setupCamera(wid, hei, previewCb_);
            cameraView_.StartPreview();
        }
    }
 
    @Override
    public void onUpdateDone() {
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
    }   

    @Override
    public void onStart(){
        super.onStart();
    }   

    @Override
    public void onResume(){
        super.onResume();
    }   
    
    @Override
    public void onPause(){  
        super.onPause();
        inProcessing = true;
        if ( webServer != null)
            webServer.stop();
        cameraView_.StopPreview(); 
        //cameraView_.Release();
        audioLoop.ReleaseLoop();
        audioCapture.release();
    
        //System.exit(0);
        finish();
    }  
    
    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override 
    public boolean onTouch(View v, MotionEvent evt) { 
        

        return false;
    }
  
    private void initAudio() {
        int minBufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int minTargetSize = 4410 * 2;      // 0.1 seconds buffer size
        if (minTargetSize < minBufferSize) {
            minTargetSize = minBufferSize;
        }
        if (audioCapture == null) {
            audioCapture = new AudioRecord(MediaRecorder.AudioSource.MIC,
                                        44100,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        minTargetSize);
        }

        if ( audioLoop == null) {  
            Random rnd = new Random();
            String etag = Integer.toHexString( rnd.nextInt() );
            audioLoop = new StreamingLoop("spy.cam" + etag );
        }

    }

    private void initCamera() {
        SurfaceView cameraSurface = (SurfaceView)findViewById(R.id.surface_camera);
        cameraView_ = new CameraView(cameraSurface);        
        cameraView_.setCameraReadyCallback(this);

        overlayView_ = (OverlayView)findViewById(R.id.surface_overlay);
        overlayView_.setOnTouchListener(this);
        overlayView_.setUpdateDoneCallback(this);
    }
    
    public String getLocalIpAddress() {

        	WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        	WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        	int ipAddress = wifiInfo.getIpAddress();
        	String ip = intToIp(ipAddress);
        	Log.v(TAG, ip);
        	return ip;

    }   
    public String intToIp(int i) {

    	   return ((i & 0xFF) & 0xFF ) + "." +
    	               ((i >> 8 ) & 0xFF) + "." +
    	               ((i >> 16 ) & 0xFF) + "." +
    	               ( i >> 24 ) ;
    }

    private boolean initWebServer() {
        String ipAddr = getLocalIpAddress();
        if ( ipAddr != null ) {
            try{
                webServer = new Server(8080, this); 
                webServer.registerCGI("/cgi/query", doQuery);
                webServer.registerCGI("/cgi/setup", doSetup);
                webServer.registerCGI("/stream/live.jpg", doCapture);
                webServer.registerCGI("/stream/live.mp3", doBroadcast);
            }catch (IOException e){
                webServer = null;
            }
        }
        if ( webServer != null) {
            tvMessage1.setText( getString(R.string.msg_access_local)+ " " + ipAddr  + ":8080" );
            return true;
        } else {
            tvMessage1.setText( getString(R.string.msg_error) );
            tvMessage2.setVisibility(View.GONE);
            return false;
        }
          
    }
   
    private OnClickListener exitAction = new OnClickListener() {
        @Override
        public void onClick(View v) {
            onPause();
        }   
    };
   
    private PreviewCallback previewCb_ = new PreviewCallback() {
        public void onPreviewFrame(byte[] frame, Camera c) {
            if ( !inProcessing ) {
                inProcessing = true;
           
                int picWidth = cameraView_.Width();
                int picHeight = cameraView_.Height(); 
                ByteBuffer bbuffer = ByteBuffer.wrap(frame); 
                bbuffer.get(preFrame, 0, picWidth*picHeight + picWidth*picHeight/2);

                inProcessing = false;
            }
        }
    };
    
    private Server.CommonGatewayInterface doQuery = new Server.CommonGatewayInterface () {
        @Override
        public String run(Properties parms) {
            String ret = "";
            List<Camera.Size> supportSize =  cameraView_.getSupportedPreviewSize();                             
            ret = ret + "" + cameraView_.Width() + "x" + cameraView_.Height() + "|";
            for(int i = 0; i < supportSize.size() - 1; i++) {
                ret = ret + "" + supportSize.get(i).width + "x" + supportSize.get(i).height + "|";
            }
            int i = supportSize.size() - 1;
            ret = ret + "" + supportSize.get(i).width + "x" + supportSize.get(i).height ;
            return ret;
        }
        
        @Override 
        public InputStream streaming(Properties parms) {
            return null;
        }    
    }; 

    private Server.CommonGatewayInterface doSetup = new Server.CommonGatewayInterface () {
        @Override
        public String run(Properties parms) {
            int wid = Integer.parseInt(parms.getProperty("wid")); 
            int hei = Integer.parseInt(parms.getProperty("hei"));
            Log.d("SPY", ">>>>>>>run in doSetup wid = " + wid + " hei=" + hei);
            cameraView_.StopPreview();
            cameraView_.setupCamera(wid, hei, previewCb_);
            cameraView_.StartPreview();
            return "OK";
        }   
 
        @Override 
        public InputStream streaming(Properties parms) {
            return null;
        }    
    }; 

    private Server.CommonGatewayInterface doBroadcast = new Server.CommonGatewayInterface() {
        @Override
        public String run(Properties parms) {
            return null;
        }   
        
        
        @Override 
        public InputStream streaming(Properties parms) {
            if ( audioLoop.isConnected() ) {     
                return null;                    // tell client is is busy by 503
            }    
 
            audioLoop.InitLoop(128, 8192);
            InputStream is = null;
            try{
                is = audioLoop.getInputStream();
            } catch(IOException e) {
                audioLoop.ReleaseLoop();
                return null;
            }
            
            audioCapture.startRecording();
            AudioEncoder audioEncoder = new AudioEncoder();
            audioEncoder.start();  
            
            return is;
        }

    };

    private Server.CommonGatewayInterface doCapture = new Server.CommonGatewayInterface () {
        @Override
        public String run(Properties parms) {
           return null;
        }   
        
        @Override 
        public InputStream streaming(Properties parms) {
            VideoFrame targetFrame = null;
            for(int i = 0; i < maxVideoNumber; i++) {
                if ( videoFrames[i].acquire() ) {
                    targetFrame = videoFrames[i];
                    break;
                }
            }
            // return 503 internal error
            if ( targetFrame == null) {
                Log.d("SPY", "No free videoFrame found!");
                return null;
            }

            int picWidth = cameraView_.Width();
            int picHeight = cameraView_.Height(); 
            YuvImage newImage = new YuvImage(preFrame, ImageFormat.NV21, picWidth, picHeight, null);
            targetFrame.reset();
            boolean ret;
            inProcessing = true;
            try{
                ret = newImage.compressToJpeg( new Rect(0,0,picWidth,picHeight), 30, targetFrame);
            } catch (Exception ex) {
                ret = false;    
            } 
            inProcessing = false;


            if ( ret == true)  {
                parms.setProperty("mime", "image/jpeg");
                InputStream ins = targetFrame.getInputStream();
                return ins;
            }
            targetFrame.release();

            return null;
        }
    }; 

    static private native int nativeOpenEncoder();
    static private native void nativeCloseEncoder();
    static private native int nativeEncodingPCM(byte[] pcmdata, int length, byte[] mp3Data);    
    private class AudioEncoder extends Thread {
        byte[] audioPackage = new byte[1024*16];
        byte[] mp3Data = new byte[1024*8];
        int packageSize = 4410 * 2;
        @Override
        public void run() {
            nativeOpenEncoder(); 
            
            OutputStream os = null;
            try {
                os = audioLoop.getOutputStream();
            } catch(IOException e) {
                os = null;
                audioLoop.ReleaseLoop();
                nativeCloseEncoder();
                return;
            }
            
            while(true) {

                int ret = audioCapture.read(audioPackage, 0, packageSize);
                if ( ret == AudioRecord.ERROR_INVALID_OPERATION ||
                        ret == AudioRecord.ERROR_BAD_VALUE) {
                    break; 
                }

                ret = nativeEncodingPCM(audioPackage, ret, mp3Data);          
                
                try {
                    os.write(mp3Data, 0, ret);
                } catch(IOException e) {
                    break;    
                }
            }
            
            audioLoop.ReleaseLoop();
            nativeCloseEncoder();
        }
    }
}    


