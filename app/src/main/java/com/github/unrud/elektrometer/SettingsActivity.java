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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            super.onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            androidx.preference.ListPreference cameraRotationPreference = getPreferenceManager()
                    .findPreference("cameraRotation");
            if (cameraRotationPreference == null) {
                throw new AssertionError("unreachable"); // fixes compiler warning
            }
            cameraRotationPreference.setSummaryProvider(
                    ListPreference.SimpleSummaryProvider.getInstance());

            for (String preferenceKey : new String[]{
                    "cameraExposureCompensation", "windowHeight", "windowOffset",
                    "colorBlueProjection", "colorRedProjection", "colorDistanceThreshold", "colorLumaThreshold",
                    "triggerFillThreshold", "triggerFillResetThreshold", "triggerResetTime"
            }) {
                androidx.preference.EditTextPreference preference = getPreferenceManager().findPreference(preferenceKey);
                if (preference == null) {
                    throw new AssertionError("unreachable"); // fixes compiler warning
                }
                final int inputType;
                if (preferenceKey.equals("cameraExposureCompensation")) {
                    inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
                } else {
                    inputType = InputType.TYPE_CLASS_NUMBER;
                }
                preference.setOnBindEditTextListener(editText -> editText.setInputType(inputType));
                preference.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
            }

        }
    }

    public static class CameraSettings {
        private final SharedPreferences sharedPreferences;
        public boolean cameraFlash;
        public int cameraRotation, cameraExposureCompensation, windowHeight, windowOffset,
                colorBlueProjection, colorRedProjection, colorDistanceThreshold, colorLumaThreshold,
                triggerFillThreshold, triggerFillResetThreshold, triggerResetTime;

        public CameraSettings(SharedPreferences sharedPreferences) {
            this.sharedPreferences = sharedPreferences;
        }

        public boolean load() {
            try {
                cameraRotation = Integer.parseInt(sharedPreferences.getString("cameraRotation", ""));
                cameraFlash = sharedPreferences.getBoolean("cameraFlash", false);
                cameraExposureCompensation = Integer.parseInt(sharedPreferences.getString("cameraExposureCompensation", ""));
                windowHeight = Integer.parseInt(sharedPreferences.getString("windowHeight", ""));
                windowOffset = Integer.parseInt(sharedPreferences.getString("windowOffset", ""));
                colorBlueProjection = Integer.parseInt(sharedPreferences.getString("colorBlueProjection", ""));
                colorRedProjection = Integer.parseInt(sharedPreferences.getString("colorRedProjection", ""));
                colorDistanceThreshold = Integer.parseInt(sharedPreferences.getString("colorDistanceThreshold", ""));
                colorLumaThreshold = Integer.parseInt(sharedPreferences.getString("colorLumaThreshold", ""));
                triggerFillThreshold = Integer.parseInt(sharedPreferences.getString("triggerFillThreshold", ""));
                triggerFillResetThreshold = Integer.parseInt(sharedPreferences.getString("triggerFillResetThreshold", ""));
                triggerResetTime = Integer.parseInt(sharedPreferences.getString("triggerResetTime", ""));
            } catch (NumberFormatException e) {
                return false;
            }
            return (cameraRotation == 0 || cameraRotation == 90 || cameraRotation == 180 || cameraRotation == 270) &&
                    windowHeight >= 0 && windowHeight <= 100 && windowOffset >= 0 && windowOffset <= 100 && windowHeight + windowOffset <= 100 &&
                    colorBlueProjection >= 0 && colorBlueProjection <= 100 &&
                    colorRedProjection >= 0 && colorRedProjection <= 100 &&
                    colorDistanceThreshold >= 0 && colorDistanceThreshold <= 100 &&
                    colorLumaThreshold >= 0 && colorLumaThreshold <= 100 &&
                    triggerFillThreshold >= 0 && triggerFillThreshold <= 100 &&
                    triggerFillResetThreshold >= 0 && triggerFillResetThreshold <= 100 &&
                    triggerResetTime >= 0;
        }
    }
}
