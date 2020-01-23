#import "Sparks.h"

#if __has_include(<React/RCTConvert.h>)
#import <React/RCTConvert.h>
#else
#import "RCTConvert.h"
#endif

// Extending the RCTConvert class allows the React Native
// bridge to handle args of type "SparksInstallMode"
@implementation RCTConvert (SparksInstallMode)

RCT_ENUM_CONVERTER(SparksInstallMode, (@{ @"SparksInstallModeImmediate": @(SparksInstallModeImmediate),
                                            @"SparksInstallModeOnNextRestart": @(SparksInstallModeOnNextRestart),
                                            @"SparksInstallModeOnNextResume": @(SparksInstallModeOnNextResume),
                                            @"SparksInstallModeOnNextSuspend": @(SparksInstallModeOnNextSuspend) }),
                   SparksInstallModeImmediate, // Default enum value
                   integerValue)

@end
