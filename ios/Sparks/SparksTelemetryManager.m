#import "Sparks.h"

static NSString *const AppVersionKey = @"appVersion";
static NSString *const DeploymentFailed = @"DeploymentFailed";
static NSString *const ApiKey = @"apiKey";
static NSString *const DeploymentSucceeded = @"DeploymentSucceeded";
static NSString *const LabelKey = @"label";
static NSString *const LastDeploymentReportKey = @"SPARKS_LAST_DEPLOYMENT_REPORT";
static NSString *const PackageKey = @"package";
static NSString *const PreviousApiKey = @"previousApiKey";
static NSString *const PreviousLabelOrAppVersionKey = @"previousLabelOrAppVersion";
static NSString *const RetryDeploymentReportKey = @"SPARKS_RETRY_DEPLOYMENT_REPORT";
static NSString *const StatusKey = @"status";

@implementation SparksTelemetryManager

+ (NSDictionary *)getBinaryUpdateReport:(NSString *)appVersion
{
    NSString *previousStatusReportIdentifier = [self getPreviousStatusReportIdentifier];
    if (previousStatusReportIdentifier == nil) {
        [self clearRetryStatusReport];
        return @{ AppVersionKey: appVersion };
    } else if (![previousStatusReportIdentifier isEqualToString:appVersion]) {
        if ([self isStatusReportIdentifierSparksLabel:previousStatusReportIdentifier]) {
            NSString *previousApiKey = [self getApiKeyFromStatusReportIdentifier:previousStatusReportIdentifier];
            NSString *previousLabel = [self getVersionLabelFromStatusReportIdentifier:previousStatusReportIdentifier];
            [self clearRetryStatusReport];
            return @{
                      AppVersionKey: appVersion,
                      PreviousApiKey: previousApiKey,
                      PreviousLabelOrAppVersionKey: previousLabel
                    };
        } else {
            [self clearRetryStatusReport];
            // Previous status report was with a binary app version.
            return @{
                      AppVersionKey: appVersion,
                      PreviousLabelOrAppVersionKey: previousStatusReportIdentifier
                    };
        }
    }

    return nil;
}

+ (NSDictionary *)getRetryStatusReport
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSDictionary *retryStatusReport = [preferences objectForKey:RetryDeploymentReportKey];
    if (retryStatusReport) {
        [self clearRetryStatusReport];
        return retryStatusReport;
    } else {
        return nil;
    }
}

+ (NSDictionary *)getRollbackReport:(NSDictionary *)lastFailedPackage
{
    return @{
              PackageKey: lastFailedPackage,
              StatusKey: DeploymentFailed
            };
}

+ (NSDictionary *)getUpdateReport:(NSDictionary *)currentPackage
{
    NSString *currentPackageIdentifier = [self getPackageStatusReportIdentifier:currentPackage];
    NSString *previousStatusReportIdentifier = [self getPreviousStatusReportIdentifier];
    if (currentPackageIdentifier) {
        if (previousStatusReportIdentifier == nil) {
            [self clearRetryStatusReport];
            return @{
                      PackageKey: currentPackage,
                      StatusKey: DeploymentSucceeded
                    };
        } else if (![previousStatusReportIdentifier isEqualToString:currentPackageIdentifier]) {
            [self clearRetryStatusReport];
            if ([self isStatusReportIdentifierSparksLabel:previousStatusReportIdentifier]) {
                NSString *previousApiKey = [self getApiKeyFromStatusReportIdentifier:previousStatusReportIdentifier];
                NSString *previousLabel = [self getVersionLabelFromStatusReportIdentifier:previousStatusReportIdentifier];
                return @{
                          PackageKey: currentPackage,
                          StatusKey: DeploymentSucceeded,
                          PreviousApiKey: previousApiKey,
                          PreviousLabelOrAppVersionKey: previousLabel
                        };
            } else {
                // Previous status report was with a binary app version.
                return @{
                          PackageKey: currentPackage,
                          StatusKey: DeploymentSucceeded,
                          PreviousLabelOrAppVersionKey: previousStatusReportIdentifier
                        };
            }
        }
    }

    return nil;
}

+ (void)recordStatusReported:(NSDictionary *)statusReport
{
    // We don't need to record rollback reports, so exit early if that's what was specified.
    if ([DeploymentFailed isEqualToString:statusReport[StatusKey]]) {
        return;
    }
    
    if (statusReport[AppVersionKey]) {
        [self saveStatusReportedForIdentifier:statusReport[AppVersionKey]];
    } else if (statusReport[PackageKey]) {
        NSString *packageIdentifier = [self getPackageStatusReportIdentifier:statusReport[PackageKey]];
        [self saveStatusReportedForIdentifier:packageIdentifier];
    }
}

+ (void)saveStatusReportForRetry:(NSDictionary *)statusReport
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    [preferences setValue:statusReport forKey:RetryDeploymentReportKey];
    [preferences synchronize];
}

#pragma mark - private methods

+ (void)clearRetryStatusReport
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    [preferences setValue:nil forKey:RetryDeploymentReportKey];
    [preferences synchronize];
}

+ (NSString *)getApiKeyFromStatusReportIdentifier:(NSString *)statusReportIdentifier
{
    return [[statusReportIdentifier componentsSeparatedByString:@":"] firstObject];
}

+ (NSString *)getPackageStatusReportIdentifier:(NSDictionary *)package
{
    // Because apiKeys can be dynamically switched, we use a
    // combination of the apiKey and label as the packageIdentifier.
    NSString *apiKey = [package objectForKey:ApiKey];
    NSString *label = [package objectForKey:LabelKey];
    if (apiKey && label) {
        return [[apiKey stringByAppendingString:@":"] stringByAppendingString:label];
    } else {
        return nil;
    }
}

+ (NSString *)getPreviousStatusReportIdentifier
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSString *sentStatusReportIdentifier = [preferences objectForKey:LastDeploymentReportKey];
    return sentStatusReportIdentifier;
}

+ (NSString *)getVersionLabelFromStatusReportIdentifier:(NSString *)statusReportIdentifier
{
    return [[statusReportIdentifier componentsSeparatedByString:@":"] lastObject];
}

+ (BOOL)isStatusReportIdentifierSparksLabel:(NSString *)statusReportIdentifier
{
    return statusReportIdentifier != nil && [statusReportIdentifier rangeOfString:@":"].location != NSNotFound;
}

+ (void)saveStatusReportedForIdentifier:(NSString *)appVersionOrPackageIdentifier
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    [preferences setValue:appVersionOrPackageIdentifier forKey:LastDeploymentReportKey];
    [preferences synchronize];
}

@end
