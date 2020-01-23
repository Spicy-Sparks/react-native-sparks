package com.marf.sparks.react;

import android.content.Context;

public class SparksBuilder {
    private String mDeploymentKey;
    private Context mContext;

    private boolean mIsDebugMode;
    private String mServerUrl;
    private Integer mPublicKeyResourceDescriptor;

    public SparksBuilder(String deploymentKey, Context context) {
        this.mDeploymentKey = deploymentKey;
        this.mContext = context;
        this.mServerUrl = Sparks.getServiceUrl();
    }

    public SparksBuilder setIsDebugMode(boolean isDebugMode) {
        this.mIsDebugMode = isDebugMode;
        return this;
    }

    public SparksBuilder setServerUrl(String serverUrl) {
        this.mServerUrl = serverUrl;
        return this;
    }

    public SparksBuilder setPublicKeyResourceDescriptor(int publicKeyResourceDescriptor) {
        this.mPublicKeyResourceDescriptor = publicKeyResourceDescriptor;
        return this;
    }

    public Sparks build() {
        return new Sparks(this.mDeploymentKey, this.mContext, this.mIsDebugMode, this.mServerUrl, this.mPublicKeyResourceDescriptor);
    }
}
