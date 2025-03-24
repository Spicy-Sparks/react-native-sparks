package com.marf.sparks.react;

import com.facebook.react.ReactHost;
import com.facebook.react.runtime.ReactHostDelegate;

/**
 * Provides access to a {@link ReactHostDelegate}
 */
public interface ReactHostHolder {
    ReactHost getReactHost();
}
