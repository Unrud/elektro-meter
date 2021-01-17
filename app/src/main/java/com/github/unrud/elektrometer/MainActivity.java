/*
 * This file is part of Elektro Meter.
 *
 * Elektro Meter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Elektro Meter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Elektro Meter.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.unrud.elektrometer;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;


public class MainActivity extends AppCompatActivity implements CameraService.CameraImageListener {
    private static final String TAG = "elektrometer::MainActivity";
    private static final int REQUEST_PERMISSIONS = 100;
    private CameraView cameraView;
    private CameraService.CameraBinder cameraBinder;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            cameraBinder = (CameraService.CameraBinder) service;
            if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
                cameraBinder.registerCameraImageListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            cameraBinder.removeCameraImageListener(MainActivity.this);
            cameraBinder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Populate preferences
        PreferenceManager.setDefaultValues(this, R.xml.root_preferences, true);
        setContentView(R.layout.activity_main);
        cameraView = findViewById(R.id.cameraView);

        // request permissions
        ArrayList<String> missingPermissions = new ArrayList<>();
        for (String permission : CameraService.REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if (missingPermissions.size() != 0) {
            String[] missingPermissionsArray = new String[missingPermissions.size()];
            missingPermissionsArray = missingPermissions.toArray(missingPermissionsArray);
            requestPermissions(missingPermissionsArray, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    // handle button activities
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.settingsButton) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        } else if (id == R.id.dumpButton) {
            if (cameraBinder != null)
                cameraBinder.requestDump();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != REQUEST_PERMISSIONS) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        for (int i : grantResults) {
            if (i != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "permissions denied by user");
                Toast.makeText(this, "permissions denied", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }
        bindCameraService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindCameraService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraBinder != null)
            cameraBinder.registerCameraImageListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraBinder != null)
            cameraBinder.removeCameraImageListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (cameraBinder != null)
            unbindService(connection);
    }

    private void bindCameraService() {
        for (String permission : CameraService.REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        Intent intent = new Intent(this, CameraService.class);
        startService(intent);
        bindService(intent, connection, 0);
    }

    @Override
    public void onCameraImage(@Nullable Bitmap image, int windowFill, boolean triggered, float fps) {
        cameraView.updateCameraImage(image, windowFill, triggered, fps);
    }
}
