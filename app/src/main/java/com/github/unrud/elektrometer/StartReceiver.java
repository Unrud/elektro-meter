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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class StartReceiver extends BroadcastReceiver {
    private static final String TAG = "elektrometer::StartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            Log.d(TAG, "received ACTION_BOOT_COMPLETED");
            for (String permission : CameraService.REQUIRED_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(context, permission) !=
                        PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }
            Intent serviceIntent = new Intent(context, CameraService.class);
            context.startService(serviceIntent);
        }
    }
}
