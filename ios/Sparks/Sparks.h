#if __has_include(<React/RCTEventEmitter.h>)
#import <React/RCTEventEmitter.h>
#elif __has_include("RCTEventEmitter.h")
#import "RCTEventEmitter.h"
#else
#import "React/RCTEventEmitter.h"   // Required when used as a Pod in a Swift project
#endif

#import <Foundation/Foundation.h>

@interface Sparks : RCTEventEmitter

+ (NSURL *)binaryBundleURL;

+ (NSURL *)initSdk;

+ (NSURL *)bundleURLForResource:(NSString *)resourceName;

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
                  withExtension:(NSString *)resourceExtension;

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
                  withExtension:(NSString *)resourceExtension
                   subdirectory:(NSString *)resourceSubdirectory;

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
                  withExtension:(NSString *)resourceExtension
                   subdirectory:(NSString *)resourceSubdirectory
                         bundle:(NSBundle *)resourceBundle;

+ (NSString *)getApplicationSupportDirectory;

+ (NSString *)bundleAssetsPath;

/*
 * This method allows the version of the app's binary interface
 * to be specified, which would otherwise default to the
 * binary version of the app.
 */
+ (void)overrideAppVersion:(NSString *)appVersion;

/*
 * This method allows dynamically setting the app's
 * deployment key, in addition to setting it via
 * the Info.plist file's SparksApiKey setting.
 */
+ (void)setApiKeyKey:(NSString *)apiKey;

/*
 * This method checks to see whether a specific package hash
 * has previously failed installation.
 */
+ (BOOL)isFailedHash:(NSString*)packageHash;


/*
 * This method is used to get information about the latest rollback.
 * This information will be used to decide whether the application
 * should ignore the update or not.
 */
+ (NSDictionary*)getRollbackInfo;
/*
 * This method is used to save information about the latest rollback.
 * This information will be used to decide whether the application
 * should ignore the update or not.
 */
+ (void)setLatestRollbackInfo:(NSString*)packageHash;
/*
 * This method is used to get the count of rollback for the package
 * using the latest rollback information.
 */
+ (int)getRollbackCountForPackage:(NSString*) packageHash fromLatestRollbackInfo:(NSMutableDictionary*) latestRollbackInfo;

/*
 * This method checks to see whether a specific package hash
 * represents a downloaded and installed update, that hasn't
 * been applied yet via an app restart.
 */
+ (BOOL)isPendingUpdate:(NSString*)packageHash;

// The below methods are only used during tests.
+ (BOOL)isUsingTestConfiguration;
+ (void)setUsingTestConfiguration:(BOOL)shouldUseTestConfiguration;
+ (void)clearUpdates;

@end

@interface SparksConfig : NSObject

@property (copy) NSString *appVersion;
@property (readonly) NSString *buildVersion;
@property (readonly) NSDictionary *configuration;
@property (copy) NSString *apiKey;
@property (copy) NSString *serverURL;
@property (copy) NSString *publicKey;

+ (instancetype)current;

@end

@interface SparksDownloadHandler : NSObject <NSURLConnectionDelegate>

@property (strong) NSOutputStream *outputFileStream;
@property long long expectedContentLength;
@property long long receivedContentLength;
@property dispatch_queue_t operationQueue;
@property (copy) void (^progressCallback)(long long, long long);
@property (copy) void (^doneCallback)(BOOL);
@property (copy) void (^failCallback)(NSError *err);
@property NSString *downloadUrl;

- (id)init:(NSString *)downloadFilePath
operationQueue:(dispatch_queue_t)operationQueue
progressCallback:(void (^)(long long, long long))progressCallback
doneCallback:(void (^)(BOOL))doneCallback
failCallback:(void (^)(NSError *err))failCallback;

- (void)download:(NSString*)url;

@end

@interface SparksErrorUtils : NSObject

+ (NSError *)errorWithMessage:(NSString *)errorMessage;
+ (BOOL)isSparksError:(NSError *)error;

@end

@interface SparksPackage : NSObject

+ (void)downloadPackage:(NSDictionary *)updatePackage
 expectedBundleFileName:(NSString *)expectedBundleFileName
              publicKey:(NSString *)publicKey
         operationQueue:(dispatch_queue_t)operationQueue
       progressCallback:(void (^)(long long, long long))progressCallback
           doneCallback:(void (^)())doneCallback
           failCallback:(void (^)(NSError *err))failCallback;

+ (NSDictionary *)getCurrentPackage:(NSError **)error;
+ (NSDictionary *)getPreviousPackage:(NSError **)error;
+ (NSString *)getCurrentPackageFolderPath:(NSError **)error;
+ (NSString *)getCurrentPackageBundlePath:(NSError **)error;
+ (NSString *)getCurrentPackageHash:(NSError **)error;

+ (NSDictionary *)getPackage:(NSString *)packageHash
                       error:(NSError **)error;

+ (NSString *)getPackageFolderPath:(NSString *)packageHash;

+ (BOOL)installPackage:(NSDictionary *)updatePackage
   removePendingUpdate:(BOOL)removePendingUpdate
                 error:(NSError **)error;

+ (void)rollbackPackage;

// The below methods are only used during tests.
+ (void)clearUpdates;
+ (void)downloadAndReplaceCurrentBundle:(NSString *)remoteBundleUrl;

@end

@interface SparksTelemetryManager : NSObject

+ (NSDictionary *)getBinaryUpdateReport:(NSString *)appVersion;
+ (NSDictionary *)getRetryStatusReport;
+ (NSDictionary *)getRollbackReport:(NSDictionary *)lastFailedPackage;
+ (NSDictionary *)getUpdateReport:(NSDictionary *)currentPackage;
+ (void)recordStatusReported:(NSDictionary *)statusReport;
+ (void)saveStatusReportForRetry:(NSDictionary *)statusReport;

@end

@interface SparksUpdateUtils : NSObject

+ (BOOL)copyEntriesInFolder:(NSString *)sourceFolder
                 destFolder:(NSString *)destFolder
                      error:(NSError **)error;

+ (NSString *)findMainBundleInFolder:(NSString *)folderPath
                    expectedFileName:(NSString *)expectedFileName
                               error:(NSError **)error;

+ (NSString *)assetsFolderName;
+ (NSString *)getHashForBinaryContents:(NSURL *)binaryBundleUrl
                                 error:(NSError **)error;

+ (NSString *)manifestFolderPrefix;
+ (NSString *)modifiedDateStringOfFileAtURL:(NSURL *)fileURL;

+ (BOOL)isHashIgnoredFor:(NSString *) relativePath;

+ (BOOL)verifyFolderHash:(NSString *)finalUpdateFolder
                   expectedHash:(NSString *)expectedHash
                          error:(NSError **)error;

// remove BEGIN / END tags and line breaks from public key string
+ (NSString *)getKeyValueFromPublicKeyString:(NSString *)publicKeyString;

+ (NSString *)getSignatureFilePath:(NSString *)updateFolderPath;

+ (NSDictionary *) verifyAndDecodeJWT:(NSString *) jwt
               withPublicKey:(NSString *)publicKey
                       error:(NSError **)error;

+ (BOOL)verifyUpdateSignatureFor:(NSString *)updateFolderPath
                    expectedHash:(NSString *)newUpdateHash
                   withPublicKey:(NSString *)publicKeyString
                           error:(NSError **)error;

@end

void CPLog(NSString *formatString, ...);

typedef NS_ENUM(NSInteger, SparksInstallMode) {
    SparksInstallModeImmediate,
    SparksInstallModeOnNextRestart,
    SparksInstallModeOnNextResume,
    SparksInstallModeOnNextSuspend
};

typedef NS_ENUM(NSInteger, SparksUpdateState) {
    SparksUpdateStateRunning,
    SparksUpdateStatePending,
    SparksUpdateStateLatest
};
