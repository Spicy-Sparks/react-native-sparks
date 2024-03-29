import { AcquisitionManager as Sdk } from "code-push/script/acquisition-sdk";
import { Alert } from "./AlertAdapter";
import requestFetchAdapter from "./request-fetch-adapter";
import { AppState, Platform } from "react-native";
import log from "./logging";
import hoistStatics from 'hoist-non-react-statics';

let NativeSparks = require("react-native").NativeModules.Sparks;

const PackageMixins = require("./package-mixins")(NativeSparks);

async function checkForUpdate(deploymentKey = null, handleBinaryVersionMismatchCallback = null) {

  const nativeConfig = await getConfiguration();

  const config = deploymentKey ? { ...nativeConfig, ...{ deploymentKey } } : nativeConfig;
  const sdk = getPromisifiedSdk(requestFetchAdapter, config);

  // Use dynamically overridden getCurrentPackage() during tests.
  const localPackage = await module.exports.getCurrentPackage();

  /*
   * If the app has a previously installed update, and that update
   * was targetted at the same app version that is currently running,
   * then we want to use its package hash to determine whether a new
   * release has been made on the server. Otherwise, we only need
   * to send the app version to the server, since we are interested
   * in any updates for current binary version, regardless of hash.
   */
  let queryPackage;
  if (localPackage) {
    queryPackage = localPackage;
  } else {
    queryPackage = { appVersion: config.appVersion };
    if (Platform.OS === "ios" && config.packageHash) {
      queryPackage.packageHash = config.packageHash;
    }
  }

  const update = await sdk.queryUpdateWithCurrentPackage(queryPackage);

  if (!update || update.updateAppVersion ||
      localPackage && (update.packageHash === localPackage.packageHash) ||
      (!localPackage || localPackage._isDebugOnly) && config.packageHash === update.packageHash) {
    if (update && update.updateAppVersion) {
      log("An update is available but it is not targeting the binary version of your app.");
      if (handleBinaryVersionMismatchCallback && typeof handleBinaryVersionMismatchCallback === "function") {
        handleBinaryVersionMismatchCallback(update)
      }
    }

    return null;
  } else {
    const remotePackage = { ...update, ...PackageMixins.remote(sdk.reportStatusDownload) };
    remotePackage.failedInstall = await NativeSparks.isFailedUpdate(remotePackage.packageHash);
    remotePackage.deploymentKey = deploymentKey || nativeConfig.deploymentKey;
    return remotePackage;
  }
}

const getConfiguration = (() => {
  let config;
  return async function getConfiguration() {
    if (config) {
      return config;
    } else if (testConfig) {
      return testConfig;
    } else {
      config = await NativeSparks.getConfiguration();
      return config;
    }
  }
})();

async function getCurrentPackage() {
  return await getUpdateMetadata(Sparks.UpdateState.LATEST);
}

async function getUpdateMetadata(updateState) {
  let updateMetadata = await NativeSparks.getUpdateMetadata(updateState || Sparks.UpdateState.RUNNING);
  if (updateMetadata) {
    updateMetadata = {...PackageMixins.local, ...updateMetadata};
    updateMetadata.failedInstall = await NativeSparks.isFailedUpdate(updateMetadata.packageHash);
    updateMetadata.isFirstRun = await NativeSparks.isFirstRun(updateMetadata.packageHash);
  }
  return updateMetadata;
}

function getPromisifiedSdk(requestFetchAdapter, config) {
  // Use dynamically overridden AcquisitionSdk during tests.
  const sdk = new module.exports.AcquisitionSdk(requestFetchAdapter, config);
  sdk.queryUpdateWithCurrentPackage = (queryPackage) => {
    return new Promise((resolve, reject) => {
      module.exports.AcquisitionSdk.prototype.queryUpdateWithCurrentPackage.call(sdk, queryPackage, (err, update) => {
        if (err) {
          reject(err);
        } else {
          resolve(update);
        }
      });
    });
  };

  sdk.reportStatusDeploy = (deployedPackage, status, previousLabelOrAppVersion, previousApiKey) => {
    return new Promise((resolve, reject) => {
      module.exports.AcquisitionSdk.prototype.reportStatusDeploy.call(sdk, deployedPackage, status, previousLabelOrAppVersion, previousApiKey, (err) => {
        if (err) {
          reject(err);
        } else {
          resolve();
        }
      });
    });
  };

  sdk.reportStatusDownload = (downloadedPackage) => {
    return new Promise((resolve, reject) => {
      module.exports.AcquisitionSdk.prototype.reportStatusDownload.call(sdk, downloadedPackage, (err) => {
        if (err) {
          reject(err);
        } else {
          resolve();
        }
      });
    });
  };

  return sdk;
}

// This ensures that notifyApplicationReadyInternal is only called once
// in the lifetime of this module instance.
const notifyApplicationReady = (() => {
  let notifyApplicationReadyPromise;
  return () => {
    if (!notifyApplicationReadyPromise) {
      notifyApplicationReadyPromise = notifyApplicationReadyInternal();
    }

    return notifyApplicationReadyPromise;
  };
})();

async function notifyApplicationReadyInternal() {
  await NativeSparks.notifyApplicationReady();
  const statusReport = await NativeSparks.getNewStatusReport();
  statusReport && tryReportStatus(statusReport); // Don't wait for this to complete.

  return statusReport;
}

async function tryReportStatus(statusReport, retryOnAppResume) {
  const config = await getConfiguration();
  const previousLabelOrAppVersion = statusReport.previousLabelOrAppVersion;
  const previousDeploymentKey = statusReport.previousDeploymentKey || config.deploymentKey;
  try {
    if (statusReport.appVersion) {
      log(`Reporting binary update (${statusReport.appVersion})`);

      const sdk = getPromisifiedSdk(requestFetchAdapter, config);
      await sdk.reportStatusDeploy(/* deployedPackage */ null, /* status */ null, previousLabelOrAppVersion, previousDeploymentKey);
    } else {
      const label = statusReport.package.label;
      if (statusReport.status === "DeploymentSucceeded") {
        log(`Reporting Sparks update success (${label})`);
      } else {
        log(`Reporting Sparks update rollback (${label})`);
        await NativeSparks.setLatestRollbackInfo(statusReport.package.packageHash);
      }

      config.deploymentKey = statusReport.package.deploymentKey;
      const sdk = getPromisifiedSdk(requestFetchAdapter, config);
      await sdk.reportStatusDeploy(statusReport.package, statusReport.status, previousLabelOrAppVersion, previousDeploymentKey);
    }

    NativeSparks.recordStatusReported(statusReport);
    retryOnAppResume && retryOnAppResume.remove();
  } catch (e) {
    log(`Report status failed: ${JSON.stringify(statusReport)}`);
    NativeSparks.saveStatusReportForRetry(statusReport);
    // Try again when the app resumes
    if (!retryOnAppResume) {
        const resumeListener = AppState.addEventListener("change", async (newState) => {
        if (newState !== "active") return;
        const refreshedStatusReport = await NativeSparks.getNewStatusReport();
        if (refreshedStatusReport) {
          tryReportStatus(refreshedStatusReport, resumeListener);
        } else {
          resumeListener && resumeListener.remove();
        }
      });
    }
  }
}

async function shouldUpdateBeIgnored(remotePackage, syncOptions) {
  let { rollbackRetryOptions } = syncOptions;

  const isFailedPackage = remotePackage && remotePackage.failedInstall;
  if (!isFailedPackage || !syncOptions.ignoreFailedUpdates) {
    return false;
  }

  if (!rollbackRetryOptions) {
    return true;
  }

  if (typeof rollbackRetryOptions !== "object") {
    rollbackRetryOptions = Sparks.DEFAULT_ROLLBACK_RETRY_OPTIONS;
  } else {
    rollbackRetryOptions = { ...Sparks.DEFAULT_ROLLBACK_RETRY_OPTIONS, ...rollbackRetryOptions };
  }

  if (!validateRollbackRetryOptions(rollbackRetryOptions)) {
    return true;
  }

  const latestRollbackInfo = await NativeSparks.getLatestRollbackInfo();
  if (!validateLatestRollbackInfo(latestRollbackInfo, remotePackage.packageHash)) {
    log("The latest rollback info is not valid.");
    return true;
  }

  const { delayInHours, maxRetryAttempts } = rollbackRetryOptions;
  const hoursSinceLatestRollback = (Date.now() - latestRollbackInfo.time) / (1000 * 60 * 60);
  if (hoursSinceLatestRollback >= delayInHours && maxRetryAttempts >= latestRollbackInfo.count) {
    log("Previous rollback should be ignored due to rollback retry options.");
    return false;
  }

  return true;
}

function validateLatestRollbackInfo(latestRollbackInfo, packageHash) {
  return latestRollbackInfo &&
    latestRollbackInfo.time &&
    latestRollbackInfo.count &&
    latestRollbackInfo.packageHash &&
    latestRollbackInfo.packageHash === packageHash;
}

function validateRollbackRetryOptions(rollbackRetryOptions) {
  if (typeof rollbackRetryOptions.delayInHours !== "number") {
    log("The 'delayInHours' rollback retry parameter must be a number.");
    return false;
  }

  if (typeof rollbackRetryOptions.maxRetryAttempts !== "number") {
    log("The 'maxRetryAttempts' rollback retry parameter must be a number.");
    return false;
  }

  if (rollbackRetryOptions.maxRetryAttempts < 1) {
    log("The 'maxRetryAttempts' rollback retry parameter cannot be less then 1.");
    return false;
  }

  return true;
}

var testConfig;

// This function is only used for tests. Replaces the default SDK, configuration and native bridge
function setUpTestDependencies(testSdk, providedTestConfig, testNativeBridge) {
  if (testSdk) module.exports.AcquisitionSdk = testSdk;
  if (providedTestConfig) testConfig = providedTestConfig;
  if (testNativeBridge) NativeSparks = testNativeBridge;
}

async function restartApp(onlyIfUpdateIsPending = false) {
  NativeSparks.restartApp(onlyIfUpdateIsPending);
}

// This function allows only one syncInternal operation to proceed at any given time.
// Parallel calls to sync() while one is ongoing yields Sparks.SyncStatus.SYNC_IN_PROGRESS.
const sync = (() => {
  let syncInProgress = false;
  const setSyncCompleted = () => { syncInProgress = false; };

  return (options = {}, syncStatusChangeCallback, downloadProgressCallback, handleBinaryVersionMismatchCallback) => {
    let syncStatusCallbackWithTryCatch, downloadProgressCallbackWithTryCatch;
    if (typeof syncStatusChangeCallback === "function") {
      syncStatusCallbackWithTryCatch = (...args) => {
        try {
          syncStatusChangeCallback(...args);
        } catch (error) {
          log(`An error has occurred : ${error.stack}`);
        }
      }
    }

    if (typeof downloadProgressCallback === "function") {
      downloadProgressCallbackWithTryCatch = (...args) => {
        try {
          downloadProgressCallback(...args);
        } catch (error) {
          log(`An error has occurred: ${error.stack}`);
        }
      }
    }

    if (syncInProgress) {
      typeof syncStatusCallbackWithTryCatch === "function"
        ? syncStatusCallbackWithTryCatch(Sparks.SyncStatus.SYNC_IN_PROGRESS)
        : log("Sync already in progress.");
      return Promise.resolve(Sparks.SyncStatus.SYNC_IN_PROGRESS);
    }

    syncInProgress = true;
    const syncPromise = syncInternal(options, syncStatusCallbackWithTryCatch, downloadProgressCallbackWithTryCatch, handleBinaryVersionMismatchCallback);
    syncPromise
      .then(setSyncCompleted)
      .catch(setSyncCompleted);

    return syncPromise;
  };
})();

async function syncInternal(options = {}, syncStatusChangeCallback, downloadProgressCallback, handleBinaryVersionMismatchCallback) {
  let resolvedInstallMode;
  const syncOptions = {
    deploymentKey: null,
    ignoreFailedUpdates: true,
    rollbackRetryOptions: null,
    installMode: Sparks.InstallMode.ON_NEXT_RESTART,
    mandatoryInstallMode: Sparks.InstallMode.IMMEDIATE,
    minimumBackgroundDuration: 0,
    updateDialog: null,
    ...options
  };

  syncStatusChangeCallback = typeof syncStatusChangeCallback === "function"
    ? syncStatusChangeCallback
    : (syncStatus) => {

        switch(syncStatus) {
          case Sparks.SyncStatus.CHECKING_FOR_UPDATE:
            log("Checking for update.");
            break;
          case Sparks.SyncStatus.AWAITING_USER_ACTION:
            log("Awaiting user action.");
            break;
          case Sparks.SyncStatus.DOWNLOADING_PACKAGE:
            log("Downloading package.");
            break;
          case Sparks.SyncStatus.INSTALLING_UPDATE:
            log("Installing update.");
            break;
          case Sparks.SyncStatus.UP_TO_DATE:
            log("App is up to date.");
            break;
          case Sparks.SyncStatus.UPDATE_IGNORED:
            log("User cancelled the update.");
            break;
          case Sparks.SyncStatus.UPDATE_INSTALLED:
            if (resolvedInstallMode == Sparks.InstallMode.ON_NEXT_RESTART) {
              log("Update is installed and will be run on the next app restart.");
            } else if (resolvedInstallMode == Sparks.InstallMode.ON_NEXT_RESUME) {
              if (syncOptions.minimumBackgroundDuration > 0) {
                log(`Update is installed and will be run after the app has been in the background for at least ${syncOptions.minimumBackgroundDuration} seconds.`);
              } else {
                log("Update is installed and will be run when the app next resumes.");
              }
            }
            break;
          case Sparks.SyncStatus.UNKNOWN_ERROR:
            log("An unknown error occurred.");
            break;
        }
      };

  try {

    await Sparks.notifyApplicationReady();

    syncStatusChangeCallback(Sparks.SyncStatus.CHECKING_FOR_UPDATE);
    const remotePackage = await checkForUpdate(syncOptions.deploymentKey, handleBinaryVersionMismatchCallback);

    const doDownloadAndInstall = async () => {

      syncStatusChangeCallback(Sparks.SyncStatus.DOWNLOADING_PACKAGE);
      const localPackage = await remotePackage.download(downloadProgressCallback);

      // Determine the correct install mode based on whether the update is mandatory or not.
      resolvedInstallMode = localPackage.isMandatory ? syncOptions.mandatoryInstallMode : syncOptions.installMode;

      syncStatusChangeCallback(Sparks.SyncStatus.INSTALLING_UPDATE);
      await localPackage.install(resolvedInstallMode, syncOptions.minimumBackgroundDuration, () => {
        syncStatusChangeCallback(Sparks.SyncStatus.UPDATE_INSTALLED);
      });

      return Sparks.SyncStatus.UPDATE_INSTALLED;
    };

    const updateShouldBeIgnored = await shouldUpdateBeIgnored(remotePackage, syncOptions);

    if (!remotePackage || updateShouldBeIgnored) {
      if (updateShouldBeIgnored) {
          log("An update is available, but it is being ignored due to having been previously rolled back.");
      }

      const currentPackage = await Sparks.getCurrentPackage();
      if (currentPackage && currentPackage.isPending) {
        syncStatusChangeCallback(Sparks.SyncStatus.UPDATE_INSTALLED);
        return Sparks.SyncStatus.UPDATE_INSTALLED;
      } else {
        syncStatusChangeCallback(Sparks.SyncStatus.UP_TO_DATE);
        return Sparks.SyncStatus.UP_TO_DATE;
      }
    } else if (syncOptions.updateDialog) {
      // updateDialog supports any truthy value (e.g. true, "goo", 12),
      // but we should treat a non-object value as just the default dialog
      if (typeof syncOptions.updateDialog !== "object") {
        syncOptions.updateDialog = Sparks.DEFAULT_UPDATE_DIALOG;
      } else {
        syncOptions.updateDialog = { ...Sparks.DEFAULT_UPDATE_DIALOG, ...syncOptions.updateDialog };
      }

      return await new Promise((resolve, reject) => {

        let message = null;
        let installButtonText = null;

        const dialogButtons = [];

        if (remotePackage.isMandatory) {
          message = syncOptions.updateDialog.mandatoryUpdateMessage;
          installButtonText = syncOptions.updateDialog.mandatoryContinueButtonLabel;
        } else {
          message = syncOptions.updateDialog.optionalUpdateMessage;
          installButtonText = syncOptions.updateDialog.optionalInstallButtonLabel;

          dialogButtons.push({
            text: syncOptions.updateDialog.optionalIgnoreButtonLabel,
            onPress: () => {
              syncStatusChangeCallback(Sparks.SyncStatus.UPDATE_IGNORED);
              resolve(Sparks.SyncStatus.UPDATE_IGNORED);
            }
          });
        }

        dialogButtons.push({
          text: installButtonText,
          onPress:() => {
            doDownloadAndInstall()
              .then(resolve, reject);
          }
        })

        if (syncOptions.updateDialog.appendReleaseDescription && remotePackage.description) {
          message += `${syncOptions.updateDialog.descriptionPrefix} ${remotePackage.description}`;
        }

        syncStatusChangeCallback(Sparks.SyncStatus.AWAITING_USER_ACTION);
        Alert.alert(syncOptions.updateDialog.title, message, dialogButtons);
      });
    } else {
      return await doDownloadAndInstall();
    }
  } catch (error) {
    syncStatusChangeCallback(Sparks.SyncStatus.UNKNOWN_ERROR);
    log(error.message);
    throw error;
  }
};

let Sparks;

function sparksify(options = {}) {
  let React;
  let ReactNative = require("react-native");

  try { React = require("react"); } catch (e) { }
  if (!React) {
    try { React = ReactNative.React; } catch (e) { }
    if (!React) {
      throw new Error("Unable to find the 'React' module.");
    }
  }

  if (!React.Component) {
    throw new Error(
      `Unable to find the "Component" class, please upgrade to a newer version of React Native that supports it`
    );
  }

  var decorator = (RootComponent) => {
    const extended = class SparksComponent extends React.Component {
      componentDidMount() {
        if (options.checkFrequency === Sparks.CheckFrequency.MANUAL) {
          Sparks.notifyAppReady();
        } else {
          let rootComponentInstance = this.refs.rootComponent;

          let syncStatusCallback;
          if (rootComponentInstance && rootComponentInstance.sparksStatusDidChange) {
            syncStatusCallback = rootComponentInstance.sparksStatusDidChange;
            if (rootComponentInstance instanceof React.Component) {
              syncStatusCallback = syncStatusCallback.bind(rootComponentInstance);
            }
          }

          let downloadProgressCallback;
          if (rootComponentInstance && rootComponentInstance.sparksDownloadDidProgress) {
            downloadProgressCallback = rootComponentInstance.sparksDownloadDidProgress;
            if (rootComponentInstance instanceof React.Component) {
              downloadProgressCallback = downloadProgressCallback.bind(rootComponentInstance);
            }
          }

          let handleBinaryVersionMismatchCallback;
          if (rootComponentInstance && rootComponentInstance.sparksOnBinaryVersionMismatch) {
            handleBinaryVersionMismatchCallback = rootComponentInstance.sparksOnBinaryVersionMismatch;
            if (rootComponentInstance instanceof React.Component) {
              handleBinaryVersionMismatchCallback = handleBinaryVersionMismatchCallback.bind(rootComponentInstance);
            }
          }

          Sparks.sync(options, syncStatusCallback, downloadProgressCallback, handleBinaryVersionMismatchCallback);
          if (options.checkFrequency === Sparks.CheckFrequency.ON_APP_RESUME) {
            ReactNative.AppState.addEventListener("change", (newState) => {
              newState === "active" && Sparks.sync(options, syncStatusCallback, downloadProgressCallback);
            });
          }
        }
      }

      render() {
        const props = {...this.props};

        // we can set ref property on class components only (not stateless)
        // check it by render method
        if (RootComponent.prototype.render) {
          props.ref = "rootComponent";
        }

        return <RootComponent {...props} />
      }
    }

    return hoistStatics(extended, RootComponent);
  }

  if (typeof options === "function") {
    // Infer that the root component was directly passed to us.
    return decorator(options);
  } else {
    return decorator;
  }
}

// If the "NativeSparks" variable isn't defined, then
// the app didn't properly install the native module,
// and therefore, it doesn't make sense initializing
// the JS interface when it wouldn't work anyways.
if (NativeSparks) {
  Sparks = sparksify;

  Object.assign(Sparks, {
    AcquisitionSdk: Sdk,
    checkForUpdate,
    getConfiguration,
    getCurrentPackage,
    getUpdateMetadata,
    log,
    notifyAppReady: notifyApplicationReady,
    notifyApplicationReady,
    restartApp,
    setUpTestDependencies,
    sync,
    disallowRestart: NativeSparks.disallow,
    allowRestart: NativeSparks.allow,
    clearUpdates: NativeSparks.clearUpdates,
    InstallMode: {
      IMMEDIATE: NativeSparks.SparksInstallModeImmediate, // Restart the app immediately
      ON_NEXT_RESTART: NativeSparks.SparksInstallModeOnNextRestart, // Don't artificially restart the app. Allow the update to be "picked up" on the next app restart
      ON_NEXT_RESUME: NativeSparks.SparksInstallModeOnNextResume, // Restart the app the next time it is resumed from the background
      ON_NEXT_SUSPEND: NativeSparks.SparksInstallModeOnNextSuspend // Restart the app _while_ it is in the background,
      // but only after it has been in the background for "minimumBackgroundDuration" seconds (0 by default),
      // so that user context isn't lost unless the app suspension is long enough to not matter
    },
    SyncStatus: {
      UP_TO_DATE: 0, // The running app is up-to-date
      UPDATE_INSTALLED: 1, // The app had an optional/mandatory update that was successfully downloaded and is about to be installed.
      UPDATE_IGNORED: 2, // The app had an optional update and the end-user chose to ignore it
      UNKNOWN_ERROR: 3,
      SYNC_IN_PROGRESS: 4, // There is an ongoing "sync" operation in progress.
      CHECKING_FOR_UPDATE: 5,
      AWAITING_USER_ACTION: 6,
      DOWNLOADING_PACKAGE: 7,
      INSTALLING_UPDATE: 8
    },
    CheckFrequency: {
      ON_APP_START: 0,
      ON_APP_RESUME: 1,
      MANUAL: 2
    },
    UpdateState: {
      RUNNING: NativeSparks.SparksUpdateStateRunning,
      PENDING: NativeSparks.SparksUpdateStatePending,
      LATEST: NativeSparks.SparksUpdateStateLatest
    },
    DeploymentStatus: {
      FAILED: "DeploymentFailed",
      SUCCEEDED: "DeploymentSucceeded",
    },
    DEFAULT_UPDATE_DIALOG: {
      appendReleaseDescription: false,
      descriptionPrefix: " Description: ",
      mandatoryContinueButtonLabel: "Continue",
      mandatoryUpdateMessage: "An update is available that must be installed.",
      optionalIgnoreButtonLabel: "Ignore",
      optionalInstallButtonLabel: "Install",
      optionalUpdateMessage: "An update is available. Would you like to install it?",
      title: "Update available"
    },
    DEFAULT_ROLLBACK_RETRY_OPTIONS: {
      delayInHours: 24,
      maxRetryAttempts: 1
    }
  });
} else {
  log("The Sparks module doesn't appear to be properly installed. Please double-check that everything is setup correctly.");
}

module.exports = Sparks;
