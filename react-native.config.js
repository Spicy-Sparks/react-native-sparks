module.exports = {
    dependency: {
        platforms: {
            android: {
                packageInstance:
                    "new Sparks(getResources().getString(R.string.SparksAPIKey), getApplicationContext(), BuildConfig.DEBUG)"
            }
        }
    }
};
