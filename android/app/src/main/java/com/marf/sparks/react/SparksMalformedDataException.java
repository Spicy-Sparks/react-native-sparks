package com.marf.sparks.react;

import java.net.MalformedURLException;

public class SparksMalformedDataException extends RuntimeException {
    public SparksMalformedDataException(String path, Throwable cause) {
        super("Unable to parse contents of " + path + ", the file may be corrupted.", cause);
    }
    public SparksMalformedDataException(String url, MalformedURLException cause) {
        super("The package has an invalid downloadUrl: " + url, cause);
    }
}