#import "Sparks.h"

@implementation SparksErrorUtils

static NSString *const SparksErrorDomain = @"SparksError";
static const int SparksErrorCode = -1;

+ (NSError *)errorWithMessage:(NSString *)errorMessage
{
    return [NSError errorWithDomain:SparksErrorDomain
                               code:SparksErrorCode
                           userInfo:@{ NSLocalizedDescriptionKey: NSLocalizedString(errorMessage, nil) }];
}

+ (BOOL)isSparksError:(NSError *)err
{
    return err != nil && [SparksErrorDomain isEqualToString:err.domain];
}

@end