#import "Sparks.h"

#if __has_include(<React/RCTConvert.h>)
#import <React/RCTConvert.h>
#else
#import "RCTConvert.h"
#endif

// Extending the RCTConvert class allows the React Native
// bridge to handle args of type "SparksUpdateState"
@implementation RCTConvert (SparksUpdateState)

RCT_ENUM_CONVERTER(SparksUpdateState, (@{ @"SparksUpdateStateRunning": @(SparksUpdateStateRunning),
                                            @"SparksUpdateStatePending": @(SparksUpdateStatePending),
                                            @"SparksUpdateStateLatest": @(SparksUpdateStateLatest)
                                          }),
                   SparksUpdateStateRunning, // Default enum value
                   integerValue)

@end
