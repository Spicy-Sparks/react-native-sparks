package com.marf.sparks.react;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.OptIn;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactHost;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactRootView;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseJavaModule;
import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.annotations.UnstableReactNativeAPI;
import com.facebook.react.devsupport.interfaces.DevSupportManager;
import com.facebook.react.modules.core.ChoreographerCompat;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.ReactChoreographer;
import com.facebook.react.modules.debug.interfaces.DeveloperSettings;
import com.facebook.react.runtime.ReactHostDelegate;
import com.facebook.react.runtime.ReactHostImpl;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@OptIn(markerClass = UnstableReactNativeAPI.class)
public class SparksNativeModule extends BaseJavaModule {
    private String mBinaryContentsHash = null;
    private String mClientUniqueId = null;
    private LifecycleEventListener mLifecycleEventListener = null;
    private int mMinimumBackgroundDuration = 0;

    private Sparks mSparks;
    private SettingsManager mSettingsManager;
    private SparksTelemetryManager mTelemetryManager;
    private SparksUpdateManager mUpdateManager;

    private boolean _allowed = true;
    private boolean _restartInProgress = false;
    private ArrayList<Boolean> _restartQueue = new ArrayList<>();

    public SparksNativeModule(ReactApplicationContext reactContext, Sparks Sparks, SparksUpdateManager SparksUpdateManager, SparksTelemetryManager SparksTelemetryManager, SettingsManager settingsManager) {
        super(reactContext);

        mSparks = Sparks;
        mSettingsManager = settingsManager;
        mTelemetryManager = SparksTelemetryManager;
        mUpdateManager = SparksUpdateManager;

        // Initialize module state while we have a reference to the current context.
        mBinaryContentsHash = SparksUpdateUtils.getHashForBinaryContents(reactContext, mSparks.isDebugMode());

        SharedPreferences preferences = mSparks.getContext().getSharedPreferences(SparksConstants.CODE_PUSH_PREFERENCES, 0);
        mClientUniqueId = preferences.getString(SparksConstants.CLIENT_UNIQUE_ID_KEY, null);
        if (mClientUniqueId == null) {
            mClientUniqueId = UUID.randomUUID().toString();
            preferences.edit().putString(SparksConstants.CLIENT_UNIQUE_ID_KEY, mClientUniqueId).apply();
        }
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();

        constants.put("SparksInstallModeImmediate", SparksInstallMode.IMMEDIATE.getValue());
        constants.put("SparksInstallModeOnNextRestart", SparksInstallMode.ON_NEXT_RESTART.getValue());
        constants.put("SparksInstallModeOnNextResume", SparksInstallMode.ON_NEXT_RESUME.getValue());
        constants.put("SparksInstallModeOnNextSuspend", SparksInstallMode.ON_NEXT_SUSPEND.getValue());

        constants.put("SparksUpdateStateRunning", SparksUpdateState.RUNNING.getValue());
        constants.put("SparksUpdateStatePending", SparksUpdateState.PENDING.getValue());
        constants.put("SparksUpdateStateLatest", SparksUpdateState.LATEST.getValue());

        return constants;
    }

    @Override
    public String getName() {
        return "Sparks";
    }

    private void loadBundleLegacy() {
        final Activity currentActivity = getReactApplicationContext().getCurrentActivity();
        if (currentActivity == null) {
            // The currentActivity can be null if it is backgrounded / destroyed, so we simply
            // no-op to prevent any null pointer exceptions.
            return;
        }
        mSparks.invalidateCurrentInstance();

        currentActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                currentActivity.recreate();
            }
        });
    }

    // Use reflection to find and set the appropriate fields on ReactInstanceManager. See #556 for a proposal for a less brittle way
    // to approach this.
    private void setJSBundle(ReactInstanceManager instanceManager, String latestJSBundleFile) throws IllegalAccessException {
        try {
            JSBundleLoader latestJSBundleLoader;
            if (latestJSBundleFile.toLowerCase().startsWith("assets://")) {
                latestJSBundleLoader = JSBundleLoader.createAssetLoader(getReactApplicationContext(), latestJSBundleFile, false);
            } else {
                latestJSBundleLoader = JSBundleLoader.createFileLoader(latestJSBundleFile);
            }

            Field bundleLoaderField = instanceManager.getClass().getDeclaredField("mBundleLoader");
            bundleLoaderField.setAccessible(true);
            bundleLoaderField.set(instanceManager, latestJSBundleLoader);
        } catch (Exception e) {
            SparksUtils.log("Unable to set JSBundle of ReactInstanceManager - Sparks may not support this version of React Native");
            throw new IllegalAccessException("Could not setJSBundle");
        }
    }

    // Use reflection to find and set the appropriate fields on ReactHostDelegate. See #556 for a proposal for a less brittle way
    // to approach this.
    private void setJSBundle(ReactHostDelegate reactHostDelegate, String latestJSBundleFile) throws IllegalAccessException {
        try {
            JSBundleLoader latestJSBundleLoader;
            if (latestJSBundleFile.toLowerCase().startsWith("assets://")) {
                latestJSBundleLoader = JSBundleLoader.createAssetLoader(getReactApplicationContext(), latestJSBundleFile, false);
            } else {
                latestJSBundleLoader = JSBundleLoader.createFileLoader(latestJSBundleFile);
            }

            Field bundleLoaderField = reactHostDelegate.getClass().getDeclaredField("jsBundleLoader");
            bundleLoaderField.setAccessible(true);
            bundleLoaderField.set(reactHostDelegate, latestJSBundleLoader);
        } catch (Exception e) {
            SparksUtils.log("Unable to set JSBundle of ReactHostDelegate - Sparks may not support this version of React Native");
            throw new IllegalAccessException("Could not setJSBundle");
        }
    }

    private void loadBundle() {
        clearLifecycleEventListener();

        if (BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
            try {
                DevSupportManager devSupportManager = null;
                ReactHost reactHost = resolveReactHost();
                if (reactHost != null) {
                    devSupportManager = reactHost.getDevSupportManager();
                }
                boolean isLiveReloadEnabled = isLiveReloadEnabled(devSupportManager);

                mSparks.clearDebugCacheIfNeeded(isLiveReloadEnabled);
            } catch (Exception e) {
                // If we got error in out reflection we should clear debug cache anyway.
                mSparks.clearDebugCacheIfNeeded(false);
            }

            try {
                // #1) Get the ReactHost instance, which is what includes the
                //     logic to reload the current React context.
                final ReactHost reactHost = resolveReactHost();
                if (reactHost == null) {
                    return;
                }

                String latestJSBundleFile = mSparks.getJSBundleFileInternal(mSparks.getAssetsBundleFileName());

                // #2) Update the locally stored JS bundle file path
                setJSBundle(getReactHostDelegate((ReactHostImpl) reactHost), latestJSBundleFile);

                // #3) Get the context creation method
                try {
                    reactHost.reload("Sparks triggers reload");
                    mSparks.initializeUpdateAfterRestart();
                } catch (Exception e) {
                    // The recreation method threw an unknown exception
                    // so just simply fallback to restarting the Activity (if it exists)
                    loadBundleLegacy();
                }

            } catch (Exception e) {
                // Our reflection logic failed somewhere
                // so fall back to restarting the Activity (if it exists)
                SparksUtils.log("Failed to load the bundle, falling back to restarting the Activity (if it exists). " + e.getMessage());
                loadBundleLegacy();
            }
        } else {
            try {
                DevSupportManager devSupportManager = null;
                ReactInstanceManager reactInstanceManager = resolveInstanceManager();
                if (reactInstanceManager != null) {
                    devSupportManager = reactInstanceManager.getDevSupportManager();
                }
                boolean isLiveReloadEnabled = isLiveReloadEnabled(devSupportManager);

                mSparks.clearDebugCacheIfNeeded(isLiveReloadEnabled);
            } catch (Exception e) {
                // If we got error in out reflection we should clear debug cache anyway.
                mSparks.clearDebugCacheIfNeeded(false);
            }

            try {
                // #1) Get the ReactInstanceManager instance, which is what includes the
                //     logic to reload the current React context.
                final ReactInstanceManager instanceManager = resolveInstanceManager();
                if (instanceManager == null) {
                    return;
                }

                String latestJSBundleFile = mSparks.getJSBundleFileInternal(mSparks.getAssetsBundleFileName());

                // #2) Update the locally stored JS bundle file path
                setJSBundle(instanceManager, latestJSBundleFile);

                // #3) Get the context creation method and fire it on the UI thread (which RN enforces)
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // We don't need to resetReactRootViews anymore
                            // due the issue https://github.com/facebook/react-native/issues/14533
                            // has been fixed in RN 0.46.0
                            //resetReactRootViews(instanceManager);

                            instanceManager.recreateReactContextInBackground();
                            mSparks.initializeUpdateAfterRestart();
                        } catch (Exception e) {
                            // The recreation method threw an unknown exception
                            // so just simply fallback to restarting the Activity (if it exists)
                            loadBundleLegacy();
                        }
                    }
                });

            } catch (Exception e) {
                // Our reflection logic failed somewhere
                // so fall back to restarting the Activity (if it exists)
                SparksUtils.log("Failed to load the bundle, falling back to restarting the Activity (if it exists). " + e.getMessage());
                loadBundleLegacy();
            }
        }
    }

    private boolean isLiveReloadEnabled(DevSupportManager devSupportManager) {
        if (devSupportManager == null) {
            return false;
        }

        DeveloperSettings devSettings = devSupportManager.getDevSettings();
        Method[] methods = devSettings.getClass().getMethods();
        for (Method m : methods) {
            if (m.getName().equals("isReloadOnJSChangeEnabled")) {
                try {
                    return (boolean) m.invoke(devSettings);
                } catch (Exception x) {
                    return false;
                }
            }
        }

        return false;
    }

    private void resetReactRootViews(ReactInstanceManager instanceManager) throws NoSuchFieldException, IllegalAccessException {
        Field mAttachedRootViewsField = instanceManager.getClass().getDeclaredField("mAttachedRootViews");
        mAttachedRootViewsField.setAccessible(true);
        List<ReactRootView> mAttachedRootViews = (List<ReactRootView>) mAttachedRootViewsField.get(instanceManager);
        for (ReactRootView reactRootView : mAttachedRootViews) {
            reactRootView.removeAllViews();
            reactRootView.setId(View.NO_ID);
        }
        mAttachedRootViewsField.set(instanceManager, mAttachedRootViews);
    }

    private void clearLifecycleEventListener() {
        // Remove LifecycleEventListener to prevent infinite restart loop
        if (mLifecycleEventListener != null) {
            getReactApplicationContext().removeLifecycleEventListener(mLifecycleEventListener);
            mLifecycleEventListener = null;
        }
    }

    // Use reflection to find the ReactInstanceManager. See #556 for a proposal for a less brittle way to approach this.
    private ReactInstanceManager resolveInstanceManager() throws NoSuchFieldException, IllegalAccessException {
        ReactInstanceManager instanceManager = Sparks.getReactInstanceManager();
        if (instanceManager != null) {
            return instanceManager;
        }

        final Activity currentActivity = getReactApplicationContext().getCurrentActivity();
        if (currentActivity == null) {
            return null;
        }

        ReactApplication reactApplication = (ReactApplication) currentActivity.getApplication();
        instanceManager = reactApplication.getReactNativeHost().getReactInstanceManager();

        return instanceManager;
    }

    private ReactHost resolveReactHost() throws NoSuchFieldException, IllegalAccessException {
        ReactHost reactHost = Sparks.getReactHost();
        if (reactHost != null) {
            return reactHost;
        }

        final Activity currentActivity = getReactApplicationContext().getCurrentActivity();
        if (currentActivity == null) {
            return null;
        }

        ReactApplication reactApplication = (ReactApplication) currentActivity.getApplication();
        return reactApplication.getReactHost();
    }

    private void restartAppInternal(boolean onlyIfUpdateIsPending) {
        if (this._restartInProgress) {
            SparksUtils.log("Restart request queued until the current restart is completed");
            this._restartQueue.add(onlyIfUpdateIsPending);
            return;
        } else if (!this._allowed) {
            SparksUtils.log("Restart request queued until restarts are re-allowed");
            this._restartQueue.add(onlyIfUpdateIsPending);
            return;
        }

        this._restartInProgress = true;
        if (!onlyIfUpdateIsPending || mSettingsManager.isPendingUpdate(null)) {
            loadBundle();
            SparksUtils.log("Restarting app");
            return;
        }

        this._restartInProgress = false;
        if (this._restartQueue.size() > 0) {
            boolean buf = this._restartQueue.get(0);
            this._restartQueue.remove(0);
            this.restartAppInternal(buf);
        }
    }

    @ReactMethod
    public void allow(Promise promise) {
        SparksUtils.log("Re-allowing restarts");
        this._allowed = true;

        if (_restartQueue.size() > 0) {
            SparksUtils.log("Executing pending restart");
            boolean buf = this._restartQueue.get(0);
            this._restartQueue.remove(0);
            this.restartAppInternal(buf);
        }

        promise.resolve(null);
        return;
    }

    @ReactMethod
    public void clearPendingRestart(Promise promise) {
        this._restartQueue.clear();
        promise.resolve(null);
        return;
    }

    @ReactMethod
    public void disallow(Promise promise) {
        SparksUtils.log("Disallowing restarts");
        this._allowed = false;
        promise.resolve(null);
        return;
    }

    @ReactMethod
    public void restartApp(boolean onlyIfUpdateIsPending, Promise promise) {
        try {
            restartAppInternal(onlyIfUpdateIsPending);
            promise.resolve(null);
        } catch (SparksUnknownException e) {
            SparksUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void downloadUpdate(final ReadableMap updatePackage, final boolean notifyProgress, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    JSONObject mutableUpdatePackage = SparksUtils.convertReadableToJsonObject(updatePackage);
                    SparksUtils.setJSONValueForKey(mutableUpdatePackage, SparksConstants.BINARY_MODIFIED_TIME_KEY, "" + mSparks.getBinaryResourcesModifiedTime());
                    mUpdateManager.downloadPackage(mutableUpdatePackage, mSparks.getAssetsBundleFileName(), new DownloadProgressCallback() {
                        private boolean hasScheduledNextFrame = false;
                        private DownloadProgress latestDownloadProgress = null;

                        @Override
                        public void call(DownloadProgress downloadProgress) {
                            if (!notifyProgress) {
                                return;
                            }

                            latestDownloadProgress = downloadProgress;
                            // If the download is completed, synchronously send the last event.
                            if (latestDownloadProgress.isCompleted()) {
                                dispatchDownloadProgressEvent();
                                return;
                            }

                            if (hasScheduledNextFrame) {
                                return;
                            }

                            hasScheduledNextFrame = true;
                            getReactApplicationContext().runOnUiQueueThread(new Runnable() {
                                @Override
                                public void run() {
                                    ReactChoreographer.getInstance().postFrameCallback(ReactChoreographer.CallbackType.TIMERS_EVENTS, new ChoreographerCompat.FrameCallback() {
                                        @Override
                                        public void doFrame(long frameTimeNanos) {
                                            if (!latestDownloadProgress.isCompleted()) {
                                                dispatchDownloadProgressEvent();
                                            }

                                            hasScheduledNextFrame = false;
                                        }
                                    });
                                }
                            });
                        }

                        public void dispatchDownloadProgressEvent() {
                            getReactApplicationContext()
                                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                    .emit(SparksConstants.DOWNLOAD_PROGRESS_EVENT_NAME, latestDownloadProgress.createWritableMap());
                        }
                    }, mSparks.getPublicKey());

                    JSONObject newPackage = mUpdateManager.getPackage(SparksUtils.tryGetString(updatePackage, SparksConstants.PACKAGE_HASH_KEY));
                    promise.resolve(SparksUtils.convertJsonObjectToWritable(newPackage));
                } catch (SparksInvalidUpdateException e) {
                    SparksUtils.log(e);
                    mSettingsManager.saveFailedUpdate(SparksUtils.convertReadableToJsonObject(updatePackage));
                    promise.reject(e);
                } catch (IOException | SparksUnknownException e) {
                    SparksUtils.log(e);
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void getConfiguration(Promise promise) {
        try {
            WritableMap configMap = Arguments.createMap();
            configMap.putString("appVersion", mSparks.getAppVersion());
            configMap.putString("clientUniqueId", mClientUniqueId);
            configMap.putString("deploymentKey", mSparks.getDeploymentKey());
            configMap.putString("serverUrl", mSparks.getServerUrl());

            // The binary hash may be null in debug builds
            if (mBinaryContentsHash != null) {
                configMap.putString(SparksConstants.PACKAGE_HASH_KEY, mBinaryContentsHash);
            }

            promise.resolve(configMap);
        } catch (SparksUnknownException e) {
            SparksUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void getUpdateMetadata(final int updateState, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    JSONObject currentPackage = mUpdateManager.getCurrentPackage();

                    if (currentPackage == null) {
                        promise.resolve(null);
                        return null;
                    }

                    Boolean currentUpdateIsPending = false;

                    if (currentPackage.has(SparksConstants.PACKAGE_HASH_KEY)) {
                        String currentHash = currentPackage.optString(SparksConstants.PACKAGE_HASH_KEY, null);
                        currentUpdateIsPending = mSettingsManager.isPendingUpdate(currentHash);
                    }

                    if (updateState == SparksUpdateState.PENDING.getValue() && !currentUpdateIsPending) {
                        // The caller wanted a pending update
                        // but there isn't currently one.
                        promise.resolve(null);
                    } else if (updateState == SparksUpdateState.RUNNING.getValue() && currentUpdateIsPending) {
                        // The caller wants the running update, but the current
                        // one is pending, so we need to grab the previous.
                        JSONObject previousPackage = mUpdateManager.getPreviousPackage();

                        if (previousPackage == null) {
                            promise.resolve(null);
                            return null;
                        }

                        promise.resolve(SparksUtils.convertJsonObjectToWritable(previousPackage));
                    } else {
                        // The current package satisfies the request:
                        // 1) Caller wanted a pending, and there is a pending update
                        // 2) Caller wanted the running update, and there isn't a pending
                        // 3) Caller wants the latest update, regardless if it's pending or not
                        if (mSparks.isRunningBinaryVersion()) {
                            // This only matters in Debug builds. Since we do not clear "outdated" updates,
                            // we need to indicate to the JS side that somehow we have a current update on
                            // disk that is not actually running.
                            SparksUtils.setJSONValueForKey(currentPackage, "_isDebugOnly", true);
                        }

                        // Enable differentiating pending vs. non-pending updates
                        SparksUtils.setJSONValueForKey(currentPackage, "isPending", currentUpdateIsPending);
                        promise.resolve(SparksUtils.convertJsonObjectToWritable(currentPackage));
                    }
                } catch (SparksMalformedDataException e) {
                    // We need to recover the app in case 'Sparks.json' is corrupted
                    SparksUtils.log(e.getMessage());
                    clearUpdates();
                    promise.resolve(null);
                } catch (SparksUnknownException e) {
                    SparksUtils.log(e);
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void getNewStatusReport(final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    if (mSparks.needToReportRollback()) {
                        mSparks.setNeedToReportRollback(false);
                        JSONArray failedUpdates = mSettingsManager.getFailedUpdates();
                        if (failedUpdates != null && failedUpdates.length() > 0) {
                            try {
                                JSONObject lastFailedPackageJSON = failedUpdates.getJSONObject(failedUpdates.length() - 1);
                                WritableMap lastFailedPackage = SparksUtils.convertJsonObjectToWritable(lastFailedPackageJSON);
                                WritableMap failedStatusReport = mTelemetryManager.getRollbackReport(lastFailedPackage);
                                if (failedStatusReport != null) {
                                    promise.resolve(failedStatusReport);
                                    return null;
                                }
                            } catch (JSONException e) {
                                throw new SparksUnknownException("Unable to read failed updates information stored in SharedPreferences.", e);
                            }
                        }
                    } else if (mSparks.didUpdate()) {
                        JSONObject currentPackage = mUpdateManager.getCurrentPackage();
                        if (currentPackage != null) {
                            WritableMap newPackageStatusReport = mTelemetryManager.getUpdateReport(SparksUtils.convertJsonObjectToWritable(currentPackage));
                            if (newPackageStatusReport != null) {
                                promise.resolve(newPackageStatusReport);
                                return null;
                            }
                        }
                    } else if (mSparks.isRunningBinaryVersion()) {
                        WritableMap newAppVersionStatusReport = mTelemetryManager.getBinaryUpdateReport(mSparks.getAppVersion());
                        if (newAppVersionStatusReport != null) {
                            promise.resolve(newAppVersionStatusReport);
                            return null;
                        }
                    } else {
                        WritableMap retryStatusReport = mTelemetryManager.getRetryStatusReport();
                        if (retryStatusReport != null) {
                            promise.resolve(retryStatusReport);
                            return null;
                        }
                    }

                    promise.resolve("");
                } catch (SparksUnknownException e) {
                    SparksUtils.log(e);
                    promise.reject(e);
                }
                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void installUpdate(final ReadableMap updatePackage, final int installMode, final int minimumBackgroundDuration, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    mUpdateManager.installPackage(SparksUtils.convertReadableToJsonObject(updatePackage), mSettingsManager.isPendingUpdate(null));

                    String pendingHash = SparksUtils.tryGetString(updatePackage, SparksConstants.PACKAGE_HASH_KEY);
                    if (pendingHash == null) {
                        throw new SparksUnknownException("Update package to be installed has no hash.");
                    } else {
                        mSettingsManager.savePendingUpdate(pendingHash, /* isLoading */false);
                    }

                    if (installMode == SparksInstallMode.ON_NEXT_RESUME.getValue() ||
                            // We also add the resume listener if the installMode is IMMEDIATE, because
                            // if the current activity is backgrounded, we want to reload the bundle when
                            // it comes back into the foreground.
                            installMode == SparksInstallMode.IMMEDIATE.getValue() ||
                            installMode == SparksInstallMode.ON_NEXT_SUSPEND.getValue()) {

                        // Store the minimum duration on the native module as an instance
                        // variable instead of relying on a closure below, so that any
                        // subsequent resume-based installs could override it.
                        SparksNativeModule.this.mMinimumBackgroundDuration = minimumBackgroundDuration;

                        if (mLifecycleEventListener == null) {
                            // Ensure we do not add the listener twice.
                            mLifecycleEventListener = new LifecycleEventListener() {
                                private Date lastPausedDate = null;
                                private Handler appSuspendHandler = new Handler(Looper.getMainLooper());
                                private Runnable loadBundleRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        SparksUtils.log("Loading bundle on suspend");
                                        restartAppInternal(false);
                                    }
                                };

                                @Override
                                public void onHostResume() {
                                    appSuspendHandler.removeCallbacks(loadBundleRunnable);
                                    // As of RN 36, the resume handler fires immediately if the app is in
                                    // the foreground, so explicitly wait for it to be backgrounded first
                                    if (lastPausedDate != null) {
                                        long durationInBackground = (new Date().getTime() - lastPausedDate.getTime()) / 1000;
                                        if (installMode == SparksInstallMode.IMMEDIATE.getValue()
                                                || durationInBackground >= SparksNativeModule.this.mMinimumBackgroundDuration) {
                                            SparksUtils.log("Loading bundle on resume");
                                            restartAppInternal(false);
                                        }
                                    }
                                }

                                @Override
                                public void onHostPause() {
                                    // Save the current time so that when the app is later
                                    // resumed, we can detect how long it was in the background.
                                    lastPausedDate = new Date();

                                    if (installMode == SparksInstallMode.ON_NEXT_SUSPEND.getValue() && mSettingsManager.isPendingUpdate(null)) {
                                        appSuspendHandler.postDelayed(loadBundleRunnable, minimumBackgroundDuration * 1000);
                                    }
                                }

                                @Override
                                public void onHostDestroy() {
                                }
                            };

                            getReactApplicationContext().addLifecycleEventListener(mLifecycleEventListener);
                        }
                    }

                    promise.resolve("");
                } catch (SparksUnknownException e) {
                    SparksUtils.log(e);
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void isFailedUpdate(String packageHash, Promise promise) {
        try {
            promise.resolve(mSettingsManager.isFailedHash(packageHash));
        } catch (SparksUnknownException e) {
            SparksUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void getLatestRollbackInfo(Promise promise) {
        try {
            JSONObject latestRollbackInfo = mSettingsManager.getLatestRollbackInfo();
            if (latestRollbackInfo != null) {
                promise.resolve(SparksUtils.convertJsonObjectToWritable(latestRollbackInfo));
            } else {
                promise.resolve(null);
            }
        } catch (SparksUnknownException e) {
            SparksUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void setLatestRollbackInfo(String packageHash, Promise promise) {
        try {
            mSettingsManager.setLatestRollbackInfo(packageHash);
            promise.resolve(null);
        } catch (SparksUnknownException e) {
            SparksUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void isFirstRun(String packageHash, Promise promise) {
        try {
            boolean isFirstRun = mSparks.didUpdate()
                    && packageHash != null
                    && packageHash.length() > 0
                    && packageHash.equals(mUpdateManager.getCurrentPackageHash());
            promise.resolve(isFirstRun);
        } catch (SparksUnknownException e) {
            SparksUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void notifyApplicationReady(Promise promise) {
        try {
            mSettingsManager.removePendingUpdate();
            promise.resolve("");
        } catch (SparksUnknownException e) {
            SparksUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void recordStatusReported(ReadableMap statusReport) {
        try {
            mTelemetryManager.recordStatusReported(statusReport);
        } catch (SparksUnknownException e) {
            SparksUtils.log(e);
        }
    }

    @ReactMethod
    public void saveStatusReportForRetry(ReadableMap statusReport) {
        try {
            mTelemetryManager.saveStatusReportForRetry(statusReport);
        } catch (SparksUnknownException e) {
            SparksUtils.log(e);
        }
    }

    @ReactMethod
    // Replaces the current bundle with the one downloaded from removeBundleUrl.
    // It is only to be used during tests. No-ops if the test configuration flag is not set.
    public void downloadAndReplaceCurrentBundle(String remoteBundleUrl) {
        try {
            if (mSparks.isUsingTestConfiguration()) {
                try {
                    mUpdateManager.downloadAndReplaceCurrentBundle(remoteBundleUrl, mSparks.getAssetsBundleFileName());
                } catch (IOException e) {
                    throw new SparksUnknownException("Unable to replace current bundle", e);
                }
            }
        } catch (SparksUnknownException | SparksMalformedDataException e) {
            SparksUtils.log(e);
        }
    }

    /**
     * This method clears Sparks's downloaded updates.
     * It is needed to switch to a different deployment if the current deployment is more recent.
     * Note: we don’t recommend to use this method in scenarios other than that (Sparks will call
     * this method automatically when needed in other cases) as it could lead to unpredictable
     * behavior.
     */
    @ReactMethod
    public void clearUpdates() {
        SparksUtils.log("Clearing updates.");
        mSparks.clearUpdates();
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Set up any upstream listeners or background tasks as necessary
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Remove upstream listeners, stop unnecessary background tasks
    }

    public ReactHostDelegate getReactHostDelegate(ReactHostImpl reactHostImpl) {
        try {
            Class<?> clazz = reactHostImpl.getClass();
            Field field = clazz.getDeclaredField("mReactHostDelegate");
            field.setAccessible(true);

            // Get the value of the field for the provided instance
            return (ReactHostDelegate) field.get(reactHostImpl);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            return null;
        }
    }
}
