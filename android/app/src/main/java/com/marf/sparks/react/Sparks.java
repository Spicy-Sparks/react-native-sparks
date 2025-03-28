package com.marf.sparks.react;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import com.facebook.react.ReactHost;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.JavaScriptModule;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.debug.interfaces.DeveloperSettings;
import com.facebook.react.devsupport.interfaces.DevSupportManager;
import com.facebook.react.uimanager.ViewManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class Sparks implements ReactPackage {

    private static boolean sIsRunningBinaryVersion = false;
    private static boolean sNeedToReportRollback = false;
    private static boolean sTestConfigurationFlag = false;
    private static String sAppVersion = null;

    private boolean mDidUpdate = false;

    private String mAssetsBundleFileName;

    // Helper classes.
    private SparksUpdateManager mUpdateManager;
    private SparksTelemetryManager mTelemetryManager;
    private SettingsManager mSettingsManager;

    // Config properties.
    private String mDeploymentKey;
    private static String mServerUrl = "https://sparks.spicysparks.com/";

    private Context mContext;
    private final boolean mIsDebugMode;

    private static String mPublicKey;

    private static ReactInstanceHolder mReactInstanceHolder;

    private static ReactHostHolder mReactHostHolder;

    private static Sparks mCurrentInstance;

    public Sparks(String deploymentKey, Context context) {
        this(deploymentKey, context, false);
    }

    public static String getServiceUrl() {
        return mServerUrl;
    }

    public Sparks(String deploymentKey, Context context, boolean isDebugMode) {
        mContext = context.getApplicationContext();

        mUpdateManager = new SparksUpdateManager(context.getFilesDir().getAbsolutePath());
        mTelemetryManager = new SparksTelemetryManager(mContext);
        mDeploymentKey = deploymentKey;
        mIsDebugMode = isDebugMode;
        mSettingsManager = new SettingsManager(mContext);

        if (sAppVersion == null) {
            try {
                PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
                sAppVersion = pInfo.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                throw new SparksUnknownException("Unable to get package info for " + mContext.getPackageName(), e);
            }
        }

        mCurrentInstance = this;

        String publicKeyFromStrings = getCustomPropertyFromStringsIfExist("PublicKey");
        if (publicKeyFromStrings != null) mPublicKey = publicKeyFromStrings;

        String serverUrlFromStrings = getCustomPropertyFromStringsIfExist("ServerUrl");
        if (serverUrlFromStrings != null) mServerUrl = serverUrlFromStrings;

        clearDebugCacheIfNeeded(false);
        initializeUpdateAfterRestart();
    }

    public Sparks(String deploymentKey, Context context, boolean isDebugMode, String serverUrl) {
        this(deploymentKey, context, isDebugMode);
        mServerUrl = serverUrl;
    }

    public Sparks(String deploymentKey, Context context, boolean isDebugMode, int publicKeyResourceDescriptor) {
        this(deploymentKey, context, isDebugMode);

        mPublicKey = getPublicKeyByResourceDescriptor(publicKeyResourceDescriptor);
    }

    public Sparks(String deploymentKey, Context context, boolean isDebugMode, String serverUrl, Integer publicKeyResourceDescriptor) {
        this(deploymentKey, context, isDebugMode);

        if (publicKeyResourceDescriptor != null) {
            mPublicKey = getPublicKeyByResourceDescriptor(publicKeyResourceDescriptor);
        }

        mServerUrl = serverUrl;
    }

    private String getPublicKeyByResourceDescriptor(int publicKeyResourceDescriptor){
        String publicKey;
        try {
            publicKey = mContext.getString(publicKeyResourceDescriptor);
        } catch (Resources.NotFoundException e) {
            throw new SparksInvalidPublicKeyException(
                    "Unable to get public key, related resource descriptor " +
                            publicKeyResourceDescriptor +
                            " can not be found", e
            );
        }

        if (publicKey.isEmpty()) {
            throw new SparksInvalidPublicKeyException("Specified public key is empty");
        }
        return publicKey;
    }

    private String getCustomPropertyFromStringsIfExist(String propertyName) {
        String property;
      
        String packageName = mContext.getPackageName();
        int resId = mContext.getResources().getIdentifier("Sparks" + propertyName, "string", packageName);
        
        if (resId != 0) {
            property = mContext.getString(resId);

            if (!property.isEmpty()) {
                return property;
            } else {
                SparksUtils.log("Specified " + propertyName + " is empty");
            } 
        }

        return null;
    }
    
    public void clearDebugCacheIfNeeded(boolean isLiveReloadEnabled) {
        // Issue: https://github.com/microsoft/react-native-code-push/issues/1272
        if (mIsDebugMode && mSettingsManager.isPendingUpdate(null) && !isLiveReloadEnabled) {
            // This needs to be kept in sync with https://github.com/facebook/react-native/blob/master/ReactAndroid/src/main/java/com/facebook/react/devsupport/DevSupportManager.java#L78
            File cachedDevBundle = new File(mContext.getFilesDir(), "ReactNativeDevBundle.js");
            if (cachedDevBundle.exists()) {
                cachedDevBundle.delete();
            }
        }
    }

    public boolean didUpdate() {
        return mDidUpdate;
    }

    public String getAppVersion() {
        return sAppVersion;
    }

    public String getAssetsBundleFileName() {
        return mAssetsBundleFileName;
    }

    public String getPublicKey() {
        return mPublicKey;
    }

    long getBinaryResourcesModifiedTime() {
        try {
            String packageName = this.mContext.getPackageName();
            int SparksApkBuildTimeId = this.mContext.getResources().getIdentifier(SparksConstants.CODE_PUSH_APK_BUILD_TIME_KEY, "string", packageName);
            // replace double quotes needed for correct restoration of long value from strings.xml
            // https://github.com/Microsoft/cordova-plugin-code-push/issues/264
            String SparksApkBuildTime = this.mContext.getResources().getString(SparksApkBuildTimeId).replaceAll("\"","");
            return Long.parseLong(SparksApkBuildTime);
        } catch (Exception e) {
            return 0;
            //throw new SparksUnknownException("Error in getting binary resources modified time", e);
        }
    }

    public String getPackageFolder() {
        JSONObject SparksLocalPackage = mUpdateManager.getCurrentPackage();
        if (SparksLocalPackage == null) {
            return null;
        }
        return mUpdateManager.getPackageFolderPath(SparksLocalPackage.optString("packageHash"));
    }

    @Deprecated
    public static String getBundleUrl() {
        return getJSBundleFile();
    }

    @Deprecated
    public static String getBundleUrl(String assetsBundleFileName) {
        return getJSBundleFile(assetsBundleFileName);
    }

    public Context getContext() {
        return mContext;
    }

    public String getDeploymentKey() {
        return mDeploymentKey;
    }

    public static String getJSBundleFile() {
        return Sparks.getJSBundleFile(SparksConstants.DEFAULT_JS_BUNDLE_NAME);
    }

    public static String getJSBundleFile(String assetsBundleFileName) {
        if (mCurrentInstance == null) {
            throw new SparksNotInitializedException("A Sparks instance has not been created yet. Have you added it to your app's list of ReactPackages?");
        }

        return mCurrentInstance.getJSBundleFileInternal(assetsBundleFileName);
    }

    public String getJSBundleFileInternal(String assetsBundleFileName) {
        this.mAssetsBundleFileName = assetsBundleFileName;
        String binaryJsBundleUrl = SparksConstants.ASSETS_BUNDLE_PREFIX + assetsBundleFileName;

        String packageFilePath = null;
        try {
            packageFilePath = mUpdateManager.getCurrentPackageBundlePath(this.mAssetsBundleFileName);
        } catch (SparksMalformedDataException e) {
            // We need to recover the app in case 'Sparks.json' is corrupted
            SparksUtils.log(e.getMessage());
            clearUpdates();
        }

        if (packageFilePath == null) {
            // There has not been any downloaded updates.
            SparksUtils.logBundleUrl(binaryJsBundleUrl);
            sIsRunningBinaryVersion = true;
            return binaryJsBundleUrl;
        }

        JSONObject packageMetadata = this.mUpdateManager.getCurrentPackage();
        if (isPackageBundleLatest(packageMetadata)) {
            SparksUtils.logBundleUrl(packageFilePath);
            sIsRunningBinaryVersion = false;
            return packageFilePath;
        } else {
            // The binary version is newer.
            this.mDidUpdate = false;
            if (!this.mIsDebugMode || hasBinaryVersionChanged(packageMetadata)) {
                this.clearUpdates();
            }

            SparksUtils.logBundleUrl(binaryJsBundleUrl);
            sIsRunningBinaryVersion = true;
            return binaryJsBundleUrl;
        }
    }

    public String getServerUrl() {
        return mServerUrl;
    }

    void initializeUpdateAfterRestart() {
        // Reset the state which indicates that
        // the app was just freshly updated.
        mDidUpdate = false;

        JSONObject pendingUpdate = mSettingsManager.getPendingUpdate();
        if (pendingUpdate != null) {
            JSONObject packageMetadata = this.mUpdateManager.getCurrentPackage();
            if (packageMetadata == null || !isPackageBundleLatest(packageMetadata) && hasBinaryVersionChanged(packageMetadata)) {
                SparksUtils.log("Skipping initializeUpdateAfterRestart(), binary version is newer");
                return;
            }

            try {
                boolean updateIsLoading = pendingUpdate.getBoolean(SparksConstants.PENDING_UPDATE_IS_LOADING_KEY);
                if (updateIsLoading) {
                    // Pending update was initialized, but notifyApplicationReady was not called.
                    // Therefore, deduce that it is a broken update and rollback.
                    SparksUtils.log("Update did not finish loading the last time, rolling back to a previous version.");
                    sNeedToReportRollback = true;
                    rollbackPackage();
                } else {
                    // There is in fact a new update running for the first
                    // time, so update the local state to ensure the client knows.
                    mDidUpdate = true;

                    // Mark that we tried to initialize the new update, so that if it crashes,
                    // we will know that we need to rollback when the app next starts.
                    mSettingsManager.savePendingUpdate(pendingUpdate.getString(SparksConstants.PENDING_UPDATE_HASH_KEY),
                            /* isLoading */true);
                }
            } catch (JSONException e) {
                // Should not happen.
                throw new SparksUnknownException("Unable to read pending update metadata stored in SharedPreferences", e);
            }
        }
    }

    void invalidateCurrentInstance() {
        mCurrentInstance = null;
    }

    boolean isDebugMode() {
        return mIsDebugMode;
    }

    boolean isRunningBinaryVersion() {
        return sIsRunningBinaryVersion;
    }

    private boolean isPackageBundleLatest(JSONObject packageMetadata) {
        try {
            Long binaryModifiedDateDuringPackageInstall = null;
            String binaryModifiedDateDuringPackageInstallString = packageMetadata.optString(SparksConstants.BINARY_MODIFIED_TIME_KEY, null);
            if (binaryModifiedDateDuringPackageInstallString != null) {
                binaryModifiedDateDuringPackageInstall = Long.parseLong(binaryModifiedDateDuringPackageInstallString);
            }
            String packageAppVersion = packageMetadata.optString("appVersion", null);
            long binaryResourcesModifiedTime = this.getBinaryResourcesModifiedTime();
            return binaryModifiedDateDuringPackageInstall != null &&
                    binaryModifiedDateDuringPackageInstall == binaryResourcesModifiedTime &&
                    (isUsingTestConfiguration() || sAppVersion.equals(packageAppVersion));
        } catch (NumberFormatException e) {
            throw new SparksUnknownException("Error in reading binary modified date from package metadata", e);
        }
    }

    private boolean hasBinaryVersionChanged(JSONObject packageMetadata) {
        String packageAppVersion = packageMetadata.optString("appVersion", null);
        return !sAppVersion.equals(packageAppVersion);
    }

    boolean needToReportRollback() {
        return sNeedToReportRollback;
    }

    public static void overrideAppVersion(String appVersionOverride) {
        sAppVersion = appVersionOverride;
    }

    private void rollbackPackage() {
        JSONObject failedPackage = mUpdateManager.getCurrentPackage();
        mSettingsManager.saveFailedUpdate(failedPackage);
        mUpdateManager.rollbackPackage();
        mSettingsManager.removePendingUpdate();
    }

    public void setNeedToReportRollback(boolean needToReportRollback) {
        Sparks.sNeedToReportRollback = needToReportRollback;
    }

    /* The below 3 methods are used for running tests.*/
    public static boolean isUsingTestConfiguration() {
        return sTestConfigurationFlag;
    }

    public void setDeploymentKey(String deploymentKey) {
        mDeploymentKey = deploymentKey;
    }

    public static void setUsingTestConfiguration(boolean shouldUseTestConfiguration) {
        sTestConfigurationFlag = shouldUseTestConfiguration;
    }

    public void clearUpdates() {
        mUpdateManager.clearUpdates();
        mSettingsManager.removePendingUpdate();
        mSettingsManager.removeFailedUpdates();
    }

    public static void setReactInstanceHolder(ReactInstanceHolder reactInstanceHolder) {
        mReactInstanceHolder = reactInstanceHolder;
    }

    static ReactInstanceManager getReactInstanceManager() {
        if (mReactInstanceHolder == null) {
            return null;
        }
        return mReactInstanceHolder.getReactInstanceManager();
    }

    public static void setReactHost(ReactHostHolder reactHostHolder) {
        mReactHostHolder = reactHostHolder;
    }
 
    static ReactHost getReactHost() {
        if (mReactHostHolder == null) {
            return null;
        }

        return mReactHostHolder.getReactHost();
    }

    @Override
    public List<NativeModule> createNativeModules(ReactApplicationContext reactApplicationContext) {
        SparksNativeModule SparksModule = new SparksNativeModule(reactApplicationContext, this, mUpdateManager, mTelemetryManager, mSettingsManager);
        SparksDialog dialogModule = new SparksDialog(reactApplicationContext);

        List<NativeModule> nativeModules = new ArrayList<>();
        nativeModules.add(SparksModule);
        nativeModules.add(dialogModule);
        return nativeModules;
    }

    // Deprecated in RN v0.47.
    public List<Class<? extends JavaScriptModule>> createJSModules() {
        return new ArrayList<>();
    }

    @Override
    public List<ViewManager> createViewManagers(ReactApplicationContext reactApplicationContext) {
        return new ArrayList<>();
    }
}
