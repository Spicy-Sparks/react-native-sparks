import { NativeEventEmitter } from "react-native";
import RestartManager from "./RestartManager";
import log from "./logging";

module.exports = (NativeSparks) => {
  const remote = (reportStatusDownload) => {
    return {
      async download(downloadProgressCallback) {
        if (!this.downloadUrl) {
          throw new Error("Cannot download an update without a download url");
        }

        let downloadProgressSubscription;
        if (downloadProgressCallback) {
          const sparksEventEmitter = new NativeEventEmitter(NativeSparks);
          // Use event subscription to obtain download progress.
          downloadProgressSubscription = sparksEventEmitter.addListener(
            "SparksDownloadProgress",
            downloadProgressCallback
          );
        }

        // Use the downloaded package info. Native code will save the package info
        // so that the client knows what the current package version is.
        try {
          const updatePackageCopy = Object.assign({}, this);
          Object.keys(updatePackageCopy).forEach((key) => (typeof updatePackageCopy[key] === 'function') && delete updatePackageCopy[key]);

          const downloadedPackage = await NativeSparks.downloadUpdate(updatePackageCopy, !!downloadProgressCallback);

          if (reportStatusDownload) {
            reportStatusDownload(this)
            .catch((err) => {
              log(`Report download status failed: ${err}`);
            });
          }

          return { ...downloadedPackage, ...local };
        } finally {
          downloadProgressSubscription && downloadProgressSubscription.remove();
        }
      },

      isPending: false // A remote package could never be in a pending state
    };
  };

  const local = {
    async install(installMode = NativeSparks.SparksInstallModeOnNextRestart, minimumBackgroundDuration = 0, updateInstalledCallback) {
      const localPackage = this;
      const localPackageCopy = Object.assign({}, localPackage);
      await NativeSparks.installUpdate(localPackageCopy, installMode, minimumBackgroundDuration);
      updateInstalledCallback && updateInstalledCallback();
      if (installMode == NativeSparks.SparksInstallModeImmediate) {
        RestartManager.restartApp(false);
      } else {
        RestartManager.clearPendingRestart();
        localPackage.isPending = true;
      }
    },

    isPending: false
  };

  return { local, remote };
};
