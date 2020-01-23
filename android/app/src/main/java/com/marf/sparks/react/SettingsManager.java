package com.marf.sparks.react;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SettingsManager {

    private SharedPreferences mSettings;

    public SettingsManager(Context applicationContext) {
        mSettings = applicationContext.getSharedPreferences(SparksConstants.CODE_PUSH_PREFERENCES, 0);
    }

    public JSONArray getFailedUpdates() {
        String failedUpdatesString = mSettings.getString(SparksConstants.FAILED_UPDATES_KEY, null);
        if (failedUpdatesString == null) {
            return new JSONArray();
        }

        try {
            return new JSONArray(failedUpdatesString);
        } catch (JSONException e) {
            // Unrecognized data format, clear and replace with expected format.
            JSONArray emptyArray = new JSONArray();
            mSettings.edit().putString(SparksConstants.FAILED_UPDATES_KEY, emptyArray.toString()).commit();
            return emptyArray;
        }
    }

    public JSONObject getPendingUpdate() {
        String pendingUpdateString = mSettings.getString(SparksConstants.PENDING_UPDATE_KEY, null);
        if (pendingUpdateString == null) {
            return null;
        }

        try {
            return new JSONObject(pendingUpdateString);
        } catch (JSONException e) {
            // Should not happen.
            SparksUtils.log("Unable to parse pending update metadata " + pendingUpdateString +
                    " stored in SharedPreferences");
            return null;
        }
    }


    public boolean isFailedHash(String packageHash) {
        JSONArray failedUpdates = getFailedUpdates();
        if (packageHash != null) {
            for (int i = 0; i < failedUpdates.length(); i++) {
                try {
                    JSONObject failedPackage = failedUpdates.getJSONObject(i);
                    String failedPackageHash = failedPackage.getString(SparksConstants.PACKAGE_HASH_KEY);
                    if (packageHash.equals(failedPackageHash)) {
                        return true;
                    }
                } catch (JSONException e) {
                    throw new SparksUnknownException("Unable to read failedUpdates data stored in SharedPreferences.", e);
                }
            }
        }

        return false;
    }

    public boolean isPendingUpdate(String packageHash) {
        JSONObject pendingUpdate = getPendingUpdate();

        try {
            return pendingUpdate != null &&
                    !pendingUpdate.getBoolean(SparksConstants.PENDING_UPDATE_IS_LOADING_KEY) &&
                    (packageHash == null || pendingUpdate.getString(SparksConstants.PENDING_UPDATE_HASH_KEY).equals(packageHash));
        } catch (JSONException e) {
            throw new SparksUnknownException("Unable to read pending update metadata in isPendingUpdate.", e);
        }
    }

    public void removeFailedUpdates() {
        mSettings.edit().remove(SparksConstants.FAILED_UPDATES_KEY).commit();
    }

    public void removePendingUpdate() {
        mSettings.edit().remove(SparksConstants.PENDING_UPDATE_KEY).commit();
    }

    public void saveFailedUpdate(JSONObject failedPackage) {
        try {
            if (isFailedHash(failedPackage.getString(SparksConstants.PACKAGE_HASH_KEY))) {
                // Do not need to add the package if it is already in the failedUpdates.
                return;
            }
        } catch (JSONException e) {
            throw new SparksUnknownException("Unable to read package hash from package.", e);
        }

        String failedUpdatesString = mSettings.getString(SparksConstants.FAILED_UPDATES_KEY, null);
        JSONArray failedUpdates;
        if (failedUpdatesString == null) {
            failedUpdates = new JSONArray();
        } else {
            try {
                failedUpdates = new JSONArray(failedUpdatesString);
            } catch (JSONException e) {
                // Should not happen.
                throw new SparksMalformedDataException("Unable to parse failed updates information " +
                        failedUpdatesString + " stored in SharedPreferences", e);
            }
        }

        failedUpdates.put(failedPackage);
        mSettings.edit().putString(SparksConstants.FAILED_UPDATES_KEY, failedUpdates.toString()).commit();
    }

    public JSONObject getLatestRollbackInfo() {
        String latestRollbackInfoString = mSettings.getString(SparksConstants.LATEST_ROLLBACK_INFO_KEY, null);
        if (latestRollbackInfoString == null) {
            return null;
        }

        try {
            return new JSONObject(latestRollbackInfoString);
        } catch (JSONException e) {
            // Should not happen.
            SparksUtils.log("Unable to parse latest rollback metadata " + latestRollbackInfoString +
                    " stored in SharedPreferences");
            return null;
        }
    }

    public void setLatestRollbackInfo(String packageHash) {
        JSONObject latestRollbackInfo = getLatestRollbackInfo();
        int count = 0;

        if (latestRollbackInfo != null) {
            try {
                String latestRollbackPackageHash = latestRollbackInfo.getString(SparksConstants.LATEST_ROLLBACK_PACKAGE_HASH_KEY);
                if (latestRollbackPackageHash.equals(packageHash)) {
                    count = latestRollbackInfo.getInt(SparksConstants.LATEST_ROLLBACK_COUNT_KEY);
                }
            } catch (JSONException e) {
                SparksUtils.log("Unable to parse latest rollback info.");
            }
        } else {
            latestRollbackInfo = new JSONObject();
        }

        try {
            latestRollbackInfo.put(SparksConstants.LATEST_ROLLBACK_PACKAGE_HASH_KEY, packageHash);
            latestRollbackInfo.put(SparksConstants.LATEST_ROLLBACK_TIME_KEY, System.currentTimeMillis());
            latestRollbackInfo.put(SparksConstants.LATEST_ROLLBACK_COUNT_KEY, count + 1);
            mSettings.edit().putString(SparksConstants.LATEST_ROLLBACK_INFO_KEY, latestRollbackInfo.toString()).commit();
        } catch (JSONException e) {
            throw new SparksUnknownException("Unable to save latest rollback info.", e);
        }
    }

    public void savePendingUpdate(String packageHash, boolean isLoading) {
        JSONObject pendingUpdate = new JSONObject();
        try {
            pendingUpdate.put(SparksConstants.PENDING_UPDATE_HASH_KEY, packageHash);
            pendingUpdate.put(SparksConstants.PENDING_UPDATE_IS_LOADING_KEY, isLoading);
            mSettings.edit().putString(SparksConstants.PENDING_UPDATE_KEY, pendingUpdate.toString()).commit();
        } catch (JSONException e) {
            // Should not happen.
            throw new SparksUnknownException("Unable to save pending update.", e);
        }
    }

}
