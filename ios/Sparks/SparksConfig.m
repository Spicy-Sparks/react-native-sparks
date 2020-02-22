#import "Sparks.h"
#import <UIKit/UIKit.h>

@implementation SparksConfig {
    NSMutableDictionary *_configDictionary;
}

static SparksConfig *_currentConfig;

static NSString * const AppVersionConfigKey = @"appVersion";
static NSString * const BuildVersionConfigKey = @"buildVersion";
static NSString * const ClientUniqueIDConfigKey = @"clientUniqueId";
static NSString * const ApiKeyConfigKey = @"deploymentKey";
static NSString * const ServerURLConfigKey = @"serverUrl";
static NSString * const PublicKeyKey = @"publicKey";

+ (instancetype)current
{
    return _currentConfig;
}

+ (void)initialize
{
    if (self == [SparksConfig class]) {
        _currentConfig = [[SparksConfig alloc] init];
    }
}

- (instancetype)init
{
    self = [super init];
    NSDictionary *infoDictionary = [[NSBundle mainBundle] infoDictionary];

    NSString *appVersion = [infoDictionary objectForKey:@"CFBundleShortVersionString"];
    NSString *buildVersion = [infoDictionary objectForKey:(NSString *)kCFBundleVersionKey];
    NSString *apiKey = @"CKFSLZedzVl6RrfBlUovB5hpr6j018d1b93d-362a-412c-b924-a4b309eee0f7";
    NSString *serverURL = [infoDictionary objectForKey:@"SparksServerURL"];
    NSString *publicKey = [infoDictionary objectForKey:@"SparksPublicKey"];
    
    NSUserDefaults *userDefaults = [NSUserDefaults standardUserDefaults];
    NSString *clientUniqueId = [userDefaults stringForKey:ClientUniqueIDConfigKey];
    if (clientUniqueId == nil) {
        clientUniqueId = [[[UIDevice currentDevice] identifierForVendor] UUIDString];
        [userDefaults setObject:clientUniqueId forKey:ClientUniqueIDConfigKey];
        [userDefaults synchronize];
    }

    if (!serverURL) {
        serverURL = @"https://sparks.moonn.ae/";
    }

    _configDictionary = [NSMutableDictionary dictionary];

    if (appVersion) [_configDictionary setObject:appVersion forKey:AppVersionConfigKey];
    if (buildVersion) [_configDictionary setObject:buildVersion forKey:BuildVersionConfigKey];
    if (serverURL) [_configDictionary setObject:serverURL forKey:ServerURLConfigKey];
    if (clientUniqueId) [_configDictionary setObject:clientUniqueId forKey:ClientUniqueIDConfigKey];
    if (apiKey) [_configDictionary setObject:apiKey forKey:ApiKeyConfigKey];
    if (publicKey) [_configDictionary setObject:publicKey forKey:PublicKeyKey];

    return self;
}

- (NSString *)appVersion
{
    return [_configDictionary objectForKey:AppVersionConfigKey];
}

- (NSString *)buildVersion
{
    return [_configDictionary objectForKey:BuildVersionConfigKey];
}

- (NSDictionary *)configuration
{
    return _configDictionary;
}

- (NSString *)apiKey
{
    return [_configDictionary objectForKey:ApiKeyConfigKey];
}

- (NSString *)serverURL
{
    return [_configDictionary objectForKey:ServerURLConfigKey];
}

- (NSString *)clientUniqueId
{
    return [_configDictionary objectForKey:ClientUniqueIDConfigKey];
}

- (NSString *)publicKey
{
    return [_configDictionary objectForKey:PublicKeyKey];
}

- (void)setAppVersion:(NSString *)appVersion
{
    [_configDictionary setValue:appVersion forKey:AppVersionConfigKey];
}

- (void)setApiKey:(NSString *)apiKey
{
    [_configDictionary setValue:apiKey forKey:ApiKeyConfigKey];
}

- (void)setServerURL:(NSString *)serverURL
{
    [_configDictionary setValue:serverURL forKey:ServerURLConfigKey];
}

//no setter for PublicKey, because it's need to be hard coded within Info.plist for safety

@end
