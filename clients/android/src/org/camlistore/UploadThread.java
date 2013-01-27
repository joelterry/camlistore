/*
Copyright 2011 Google Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.camlistore;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

public class UploadThread extends Thread {
    private static final String TAG = "UploadThread";
    
    private final UploadService mService;
    private final HostPort mHostPort;
    private final String mUsername;
    private final String mPassword;
    private LinkedList<QueuedFile> mQueue;
    
    AtomicReference<Process> goProcess = new AtomicReference<Process>();
    AtomicReference<OutputStream> toChildRef = new AtomicReference<OutputStream>();

    private final AtomicBoolean mStopRequested = new AtomicBoolean(false);

    public UploadThread(UploadService uploadService, HostPort hp, String username, String password) {
        mService = uploadService;
        mHostPort = hp;
        mUsername = username;
        mPassword = password;
    }
    
    public void stopPlease() {
        mStopRequested.set(true);
    }

    private String binaryPath(String suffix) {
        return mService.getBaseContext().getFilesDir().getAbsolutePath() + "/" + suffix;
    }
    
    @Override
    public void run() {
    	Log.d(TAG, "Running");
        if (!mHostPort.isValid()) {
        	Log.d(TAG, "host/port is invalid");
            return;
        }
        status("Running UploadThread for " + mHostPort);

        mService.setInFlightBytes(0);
        mService.setInFlightBlobs(0);
        
        while (!(mQueue = mService.uploadQueue()).isEmpty()) {
            if (mStopRequested.get()) {
                status("Upload pause requested; ending upload.");
                return;
            }

            status("Uploading...");
            ListIterator<QueuedFile> iter = mQueue.listIterator();
            while (iter.hasNext()) {
            	QueuedFile qf = iter.next();
            	String diskPath = qf.getDiskPath();
            	if (diskPath == null) {
            		Log.d(TAG, "URI " + qf.getUri() + " had no disk path; skipping");
            		iter.remove();
            		continue;
            	}
            	Log.d(TAG, "need to upload: " + qf);
            
                Process process = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder()
                    	.command(binaryPath("camput.bin"), "--server=" + mHostPort.urlPrefix(), "file", "-vivify", diskPath)
                        .redirectErrorStream(false);
                    pb.environment().put("CAMLI_AUTH", "userpass:" + mUsername + ":" + mPassword);
                    process = pb.start();
                    goProcess.set(process);
                    new CopyToAndroidLogThread(process.getErrorStream()).start(); // stderr
                    new CopyToAndroidLogThread(process.getInputStream()).start(); // stdout
                    //BufferedReader br = new BufferedReader(new InputStreamReader(in));
                    Log.d(TAG, "Waiting for camput process.");
                    process.waitFor();
                    Log.d(TAG, "Exit status of camput = " + process.exitValue());
                    if (process.exitValue() == 0) {
                        status("Uploaded " + diskPath);
                    	iter.remove();
                    } else {
                    	Log.d(TAG, "Problem uploading.");
                    	return;
                    }
                } catch (IOException e) {
                	throw new RuntimeException(e);
                } catch (InterruptedException e) {
                	throw new RuntimeException(e);
                }
                
            }
            
            mService.setInFlightBytes(0);
            mService.setInFlightBlobs(0);
        }

        status("Queue empty; done.");
    }



    private void status(String st) {
        Log.d(TAG, st);
        mService.setUploadStatusText(st);
    }
    
    private class CopyToAndroidLogThread extends Thread {
        private final BufferedReader mBufIn;
		
        public CopyToAndroidLogThread(InputStream in) {
            mBufIn = new BufferedReader(new InputStreamReader(in));
        }
		
        @Override 
            public void run() {
            String tag = TAG + "/child-stderr";
            while (true) {
                String line = null;
                try {
                    line = mBufIn.readLine();
                } catch (IOException e) {
                    Log.d(tag, "Exception: " + e.toString());
                    return;
                }
                if (line == null) {
                    Log.d(tag, "null line from child stderr.");
                    return;
                }
                Log.d(tag, line);
            }
        }
    }

}
